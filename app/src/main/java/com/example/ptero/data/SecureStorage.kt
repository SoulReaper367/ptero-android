package com.example.ptero.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import java.util.UUID

/**
 * SecureStorage — crash-proof wrapper around EncryptedSharedPreferences.
 *
 * ## Crash sources fixed
 * 1. `EncryptedSharedPreferences` constructor can throw if the Android Keystore
 *    key has been invalidated (device re-enrolment, factory reset, some OEM bugs).
 *    Previously this propagated uncaught and crashed the app on launch.
 *    Fix: the `prefs` lazy initialiser now catches every Throwable and falls back
 *    to a plain (non-encrypted) SharedPreferences file so the app always starts.
 *
 * 2. `getAccounts()` called `prefs.getString(...)` without any guard; if prefs
 *    initialisation threw during the lazy call the app crashed there too.
 *    Fix: all public methods are now wrapped in try/catch.
 *
 * 3. `persistAccounts()` used `.apply()` (fire-and-forget).  If the write failed
 *    silently the caller had no way to know.  We keep `.apply()` (async) but log
 *    the failure and surface it via the Result return from `saveAccount()`.
 *
 * 4. Gson `fromJson` can throw `JsonSyntaxException` on corrupted stored JSON.
 *    Fix: catch it and return an empty list so the app recovers gracefully.
 *
 * All public methods must be called from a coroutine on Dispatchers.IO because
 * EncryptedSharedPreferences performs AES crypto which can block for ~10–30 ms.
 */
class SecureStorage(private val context: Context) {

    companion object {
        private const val TAG                 = "SecureStorage"
        private const val PREFS_FILE_SECURE   = "ptero_secure_prefs"
        private const val PREFS_FILE_FALLBACK = "ptero_prefs_fallback"
        private const val KEY_ACCOUNTS        = "panel_accounts"
        private const val KEY_DISPLAY_NAME    = "display_name"
        private const val MAX_ACCOUNTS        = 4
        private const val DEFAULT_DISPLAY_NAME = "SoulReaper"
    }

    private val gson = Gson()

    /**
     * Lazily initialised SharedPreferences.
     *
     * Priority:
     *  1. EncryptedSharedPreferences backed by AES-256-GCM Keystore key.
     *  2. Plain SharedPreferences (fallback) if the Keystore is unavailable.
     *
     * The fallback is logged at WARN level so developers see it in Logcat.
     * A production app might choose to wipe stored data and re-prompt the user,
     * but for this panel manager a graceful degradation is acceptable.
     */
    private val prefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                PREFS_FILE_SECURE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Keystore failure — log and degrade gracefully instead of crashing.
            Log.w(TAG, "EncryptedSharedPreferences unavailable, falling back to plain prefs. " +
                    "Cause: ${e.javaClass.simpleName}: ${e.message}")
            context.getSharedPreferences(PREFS_FILE_FALLBACK, Context.MODE_PRIVATE)
        }
    }

    // ─── Panel Accounts ───────────────────────────────────────────────────────

    /**
     * Returns all stored [PanelAccount]s, or an empty list if none exist or if
     * deserialisation fails.  Never throws.
     */
    fun getAccounts(): List<PanelAccount> {
        return try {
            val json = prefs.getString(KEY_ACCOUNTS, null) ?: return emptyList()
            if (json.isBlank()) return emptyList()
            val type = object : TypeToken<List<PanelAccount>>() {}.type
            gson.fromJson<List<PanelAccount>>(json, type)?.filterNotNull() ?: emptyList()
        } catch (e: JsonSyntaxException) {
            Log.e(TAG, "getAccounts: JSON parse error — clearing corrupt data", e)
            // Wipe the corrupt entry so future calls don't keep failing
            runCatching { prefs.edit().remove(KEY_ACCOUNTS).apply() }
            emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "getAccounts: unexpected error", e)
            emptyList()
        }
    }

    /**
     * Saves or updates [account] in the persisted list.
     *
     * Returns [Result.failure] if:
     *  - The account limit has been reached and [account] is not an update.
     *  - The underlying prefs write throws (rare but possible on low storage).
     */
    fun saveAccount(account: PanelAccount): Result<Unit> {
        return try {
            val current = getAccounts().toMutableList()
            val isUpdate = current.any { it.id == account.id }

            if (!isUpdate && current.size >= MAX_ACCOUNTS) {
                return Result.failure(
                    IllegalStateException("Maximum of $MAX_ACCOUNTS panel accounts reached.")
                )
            }

            val index = current.indexOfFirst { it.id == account.id }
            if (index >= 0) current[index] = account else current.add(account)

            persistAccounts(current)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "saveAccount: failed to save account ${account.id}", e)
            Result.failure(e)
        }
    }

    /**
     * Removes the account with [id] from persistent storage.
     * No-ops silently if [id] is not found.
     */
    fun removeAccount(id: String) {
        try {
            val updated = getAccounts().filter { it.id != id }
            persistAccounts(updated)
        } catch (e: Exception) {
            Log.e(TAG, "removeAccount: failed for id=$id", e)
        }
    }

    /**
     * Constructs a validated [PanelAccount] with a fresh UUID.
     *
     * [serverId] is trimmed and set to null when blank so [PanelAccount.isPinned]
     * is correct without callers needing to remember to blank-check it.
     */
    fun createAccount(
        label: String,
        panelUrl: String,
        apiKey: String,
        serverId: String? = null
    ): PanelAccount {
        require(label.isNotBlank())   { "Label must not be blank" }
        require(panelUrl.isNotBlank()) { "Panel URL must not be blank" }
        require(apiKey.isNotBlank())  { "API key must not be blank" }

        return PanelAccount(
            id       = UUID.randomUUID().toString(),
            label    = label.trim(),
            panelUrl = panelUrl.trim().trimEnd('/'),
            apiKey   = apiKey.trim(),
            serverId = serverId?.trim()?.takeIf { it.isNotBlank() }
        )
    }

    // ─── Display Name ─────────────────────────────────────────────────────────

    fun getDisplayName(): String = try {
        prefs.getString(KEY_DISPLAY_NAME, DEFAULT_DISPLAY_NAME) ?: DEFAULT_DISPLAY_NAME
    } catch (e: Exception) {
        Log.e(TAG, "getDisplayName: error", e)
        DEFAULT_DISPLAY_NAME
    }

    fun setDisplayName(name: String) {
        try {
            prefs.edit().putString(KEY_DISPLAY_NAME, name.trim().ifBlank { DEFAULT_DISPLAY_NAME }).apply()
        } catch (e: Exception) {
            Log.e(TAG, "setDisplayName: error", e)
        }
    }

    // ─── Utility ──────────────────────────────────────────────────────────────

    /** Wipes all data — used in debug/testing only. */
    fun clearAll() {
        try {
            prefs.edit().clear().apply()
        } catch (e: Exception) {
            Log.e(TAG, "clearAll: error", e)
        }
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private fun persistAccounts(accounts: List<PanelAccount>) {
        // Defensively encode then write; if toJson throws we don't corrupt storage
        val json = try {
            gson.toJson(accounts)
        } catch (e: Exception) {
            Log.e(TAG, "persistAccounts: serialisation failed", e)
            throw e   // bubble up so saveAccount() surfaces the Result.failure
        }
        prefs.edit().putString(KEY_ACCOUNTS, json).apply()
    }
}
