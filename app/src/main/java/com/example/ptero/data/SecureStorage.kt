package com.example.ptero.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

/**
 * SecureStorage wraps EncryptedSharedPreferences to persist PanelAccount data
 * (including API keys) safely on-device using AES-256-GCM.
 *
 * All reads and writes are synchronous — call from a coroutine dispatcher
 * (Dispatchers.IO) to avoid blocking the main thread.
 */
class SecureStorage(context: Context) {

    companion object {
        private const val PREFS_FILE = "ptero_secure_prefs"
        private const val KEY_ACCOUNTS = "panel_accounts"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val MAX_ACCOUNTS = 4
    }

    private val gson = Gson()

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // ─── Panel Accounts ───────────────────────────────────────────────────────

    fun getAccounts(): List<PanelAccount> {
        val json = prefs.getString(KEY_ACCOUNTS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<PanelAccount>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveAccount(account: PanelAccount): Result<Unit> {
        val current = getAccounts().toMutableList()
        if (current.size >= MAX_ACCOUNTS && current.none { it.id == account.id }) {
            return Result.failure(
                IllegalStateException("Maximum of $MAX_ACCOUNTS panel accounts reached.")
            )
        }
        val index = current.indexOfFirst { it.id == account.id }
        if (index >= 0) {
            current[index] = account
        } else {
            current.add(account)
        }
        persistAccounts(current)
        return Result.success(Unit)
    }

    fun removeAccount(id: String) {
        val updated = getAccounts().filter { it.id != id }
        persistAccounts(updated)
    }

    fun createAccount(
        label: String,
        panelUrl: String,
        apiKey: String
    ): PanelAccount = PanelAccount(
        id = UUID.randomUUID().toString(),
        label = label,
        panelUrl = panelUrl.trimEnd('/'),
        apiKey = apiKey.trim()
    )

    private fun persistAccounts(accounts: List<PanelAccount>) {
        prefs.edit()
            .putString(KEY_ACCOUNTS, gson.toJson(accounts))
            .apply()
    }

    // ─── Display Name ─────────────────────────────────────────────────────────

    fun getDisplayName(): String =
        prefs.getString(KEY_DISPLAY_NAME, "SoulReaper") ?: "SoulReaper"

    fun setDisplayName(name: String) {
        prefs.edit().putString(KEY_DISPLAY_NAME, name).apply()
    }

    // ─── Utility ──────────────────────────────────────────────────────────────

    fun clearAll() {
        prefs.edit().clear().apply()
    }
}
