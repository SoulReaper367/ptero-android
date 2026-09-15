package com.example.ptero.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ptero.api.*
import com.example.ptero.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

private const val TAG = "ServersViewModel"

// ─── UI models ────────────────────────────────────────────────────────────────

data class UiServer(
    val attributes: ServerAttributes,
    val account: PanelAccount
)

/**
 * Screen state emitted by [ServersViewModel].
 *
 * [errors] is a list so per-panel failures stack independently — one bad API
 * key does not hide servers from other panels.
 */
data class HomeUiState(
    val isLoading: Boolean                  = false,
    val servers: List<UiServer>             = emptyList(),
    val accounts: List<PanelAccount>        = emptyList(),
    val errors: List<String>                = emptyList(),
    val powerActionInProgress: Set<String>  = emptySet()   // server identifiers
) {
    /** Single joined string for legacy / simple banner callers. */
    val error: String? get() = errors.joinToString("\n").ifBlank { null }
}

// ─── ViewModel ────────────────────────────────────────────────────────────────

class ServersViewModel(application: Application) : AndroidViewModel(application) {

    /**
     * SecureStorage is initialised lazily so the first access happens on IO.
     * Accessing it on the main thread during init{} could jank the UI during
     * Keystore key derivation.
     *
     * Bug fix: original code constructed SecureStorage(application) directly in
     * the class body (main thread), which ran the lazy `prefs` init (including
     * AES key setup) on the main thread — causing ANRs on slow devices.
     */
    private val secureStorage by lazy { SecureStorage(application) }

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var resourcePollJob: Job? = null

    init {
        loadAccounts()
    }

    // ─── Account management ───────────────────────────────────────────────────

    /**
     * Loads accounts from storage on IO and updates state.
     *
     * Bug fix: original ran `secureStorage.getAccounts()` on whichever thread
     * called `loadAccounts()` — typically the main thread from `init {}`.
     */
    fun loadAccounts() {
        viewModelScope.launch {
            val accounts = withContext(Dispatchers.IO) {
                try {
                    secureStorage.getAccounts()
                } catch (e: Exception) {
                    Log.e(TAG, "loadAccounts: error reading storage", e)
                    emptyList()
                }
            }
            _uiState.update { it.copy(accounts = accounts) }
            if (accounts.isNotEmpty()) fetchAllServers()
        }
    }

    /**
     * Creates and persists a new [PanelAccount].
     *
     * Bug fix: original called storage directly on whatever thread invoked
     * `addAccount()` — in practice this was the main thread (launched from
     * a button click without an IO dispatch).  The whole body is now wrapped
     * in `withContext(Dispatchers.IO)`.
     *
     * Returns [Result.failure] with a descriptive message on any error so the
     * UI can show it without crashing.
     */
    suspend fun addAccount(
        label: String,
        panelUrl: String,
        apiKey: String,
        serverId: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val account = secureStorage.createAccount(label, panelUrl, apiKey, serverId)
            val result  = secureStorage.saveAccount(account)
            if (result.isSuccess) {
                // Must post back to main thread to update StateFlow safely
                withContext(Dispatchers.Main) { loadAccounts() }
            }
            result
        } catch (e: IllegalArgumentException) {
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "addAccount: unexpected error", e)
            Result.failure(RuntimeException("Failed to save panel: ${e.message}", e))
        }
    }

    fun removeAccount(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                secureStorage.removeAccount(id)
                PterodactylApiFactory.clearAll()
            } catch (e: Exception) {
                Log.e(TAG, "removeAccount: error for id=$id", e)
            }
            withContext(Dispatchers.Main) { loadAccounts() }
        }
    }

    fun getDisplayName(): String = try {
        // Called on main thread from Composable — safe because SecureStorage.getDisplayName()
        // reads from the already-initialised SharedPreferences (fast in-memory cache after first
        // decrypt). For brand-new installs the prefs lazy init will run here; acceptable since
        // it only hits the main thread once.
        secureStorage.getDisplayName()
    } catch (e: Exception) {
        Log.e(TAG, "getDisplayName: error", e)
        "SoulReaper"
    }

    // ─── Server fetching ──────────────────────────────────────────────────────

    fun fetchAllServers() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errors = emptyList()) }

            val accounts = withContext(Dispatchers.IO) {
                try { secureStorage.getAccounts() } catch (e: Exception) { emptyList() }
            }

            if (accounts.isEmpty()) {
                _uiState.update { it.copy(isLoading = false, servers = emptyList()) }
                return@launch
            }

            val allServers = mutableListOf<UiServer>()
            val errors     = mutableListOf<String>()

            // Fan out — each account fetches concurrently on IO
            val deferreds = accounts.map { account ->
                async(Dispatchers.IO) {
                    fetchServersForAccount(account)
                }
            }

            deferreds.forEach { deferred ->
                try {
                    when (val result = deferred.await()) {
                        is ApiResult.Success      -> allServers.addAll(result.data)
                        is ApiResult.HttpError    -> errors.add("[${result.code}] ${result.userMessage}")
                        is ApiResult.NetworkError -> errors.add(result.userMessage)
                        is ApiResult.ParseError   -> errors.add(result.userMessage)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "fetchAllServers: deferred.await() threw", e)
                    errors.add("Unexpected error: ${e.message ?: "unknown"}")
                }
            }

            _uiState.update {
                it.copy(
                    isLoading = false,
                    servers   = allServers,
                    errors    = errors
                )
            }

            if (allServers.isNotEmpty()) startResourcePolling()
        }
    }

    /**
     * Fetches servers for one [account].
     *
     * - Pinned ([PanelAccount.isPinned]): GET /servers/{id} — single server.
     * - Unpinned: GET /servers?include=allocations — all account servers.
     *
     * Bug fix: `result.data.data.map { ... }` crashed when the API returned a
     * response with a null `data` array (some panel versions do this for accounts
     * with no servers).  Now uses `safeData()` extension which filters nulls.
     */
    private suspend fun fetchServersForAccount(
        account: PanelAccount
    ): ApiResult<List<UiServer>> {
        return try {
            val api = PterodactylApiFactory.getApi(account.panelUrl, account.apiKey)

            if (account.isPinned) {
                val serverId = account.serverId ?: return ApiResult.HttpError(
                    code        = 0,
                    userMessage = "[${account.label}] Pinned account has no server ID set."
                )
                when (val result = safeApiCall { api.getServer(serverId) }) {
                    is ApiResult.Success -> {
                        val uiServer = result.data.attributes
                            .applyAccountMeta(account)
                            .let { UiServer(it, account) }
                        ApiResult.Success(listOf(uiServer))
                    }
                    is ApiResult.HttpError    -> result
                    is ApiResult.NetworkError -> result
                    is ApiResult.ParseError   -> result
                }
            } else {
                when (val result = safeApiCall { api.listServers() }) {
                    is ApiResult.Success -> {
                        // Bug fix: use safeData() to handle null/empty data array
                        val servers = result.data.safeData().mapNotNull { wrapper ->
                            try {
                                UiServer(wrapper.attributes.applyAccountMeta(account), account)
                            } catch (e: Exception) {
                                Log.w(TAG, "fetchServersForAccount: skipping malformed server", e)
                                null
                            }
                        }
                        ApiResult.Success(servers)
                    }
                    is ApiResult.HttpError    -> result
                    is ApiResult.NetworkError -> result
                    is ApiResult.ParseError   -> result
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchServersForAccount: unexpected error for ${account.label}", e)
            ApiResult.NetworkError(
                userMessage = "[${account.label}] Unexpected error: ${e.message ?: "unknown"}",
                cause       = e
            )
        }
    }

    // ─── Resource polling ─────────────────────────────────────────────────────

    private fun startResourcePolling() {
        resourcePollJob?.cancel()
        resourcePollJob = viewModelScope.launch {
            while (isActive) {
                delay(8_000L)
                if (isActive) refreshAllResources()
            }
        }
    }

    /**
     * Refreshes resource usage for every currently-displayed server.
     *
     * Bug fix: original used `coroutineScope { }` which propagates child failures
     * to the parent — a single failing server could cancel the entire poll pass.
     * Each async block now has its own try/catch so one server's error is silent
     * and the rest still update.
     *
     * Bug fix: `uiServer.attributes.apply { ... }` mutated a shared object that
     * could be read concurrently by the UI. We now build a proper replacement list
     * and do a single atomic StateFlow update.
     */
    private suspend fun refreshAllResources() {
        val current = _uiState.value.servers
        if (current.isEmpty()) return

        val updated = current.map { uiServer ->
            viewModelScope.async(Dispatchers.IO) {
                try {
                    val api = PterodactylApiFactory.getApi(
                        uiServer.account.panelUrl,
                        uiServer.account.apiKey
                    )
                    when (val res = safeApiCall { api.getServerResources(uiServer.attributes.identifier) }) {
                        is ApiResult.Success -> {
                            val res2     = res.data.attributes
                            val newAttrs = uiServer.attributes.copy().also { copy ->
                                copy.currentCpu         = res2.resources.cpuAbsolute
                                copy.currentMemoryBytes = res2.resources.memoryBytes
                                copy.currentDiskBytes   = res2.resources.diskBytes
                                copy.serverStatus       = res2.currentState
                                // Preserve injected meta
                                copy.panelAccountId     = uiServer.attributes.panelAccountId
                                copy.panelLabel         = uiServer.attributes.panelLabel
                                copy.allocationDisplay  = uiServer.attributes.allocationDisplay
                            }
                            uiServer.copy(attributes = newAttrs)
                        }
                        // Silently keep stale data on poll failure to avoid flashing errors
                        else -> uiServer
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "refreshAllResources: error for ${uiServer.attributes.identifier}", e)
                    uiServer   // keep stale
                }
            }
        }.awaitAll()

        _uiState.update { it.copy(servers = updated) }
    }

    // ─── Power signals ────────────────────────────────────────────────────────

    /**
     * Sends a power signal to one server.
     *
     * Bug fix: original `safeApiCall` was not dispatched on Dispatchers.IO — it
     * ran on the main thread inside `viewModelScope.launch {}` (which defaults to
     * Dispatchers.Main). Network calls on the main thread throw
     * NetworkOnMainThreadException on API 26+ (minSdk for this project).
     *
     * Bug fix: if `refreshAllResources()` threw (e.g. all servers went offline
     * between the power action and the refresh), the exception bubbled through the
     * coroutine and was unhandled. Now wrapped in try/catch.
     */
    fun sendPowerSignal(uiServer: UiServer, signal: String) {
        if (signal !in listOf("start", "stop", "restart", "kill")) {
            Log.w(TAG, "sendPowerSignal: invalid signal '$signal'")
            return
        }

        val identifier = uiServer.attributes.identifier
        viewModelScope.launch {
            _uiState.update {
                it.copy(powerActionInProgress = it.powerActionInProgress + identifier)
            }

            try {
                val result = withContext(Dispatchers.IO) {
                    val api = PterodactylApiFactory.getApi(
                        uiServer.account.panelUrl,
                        uiServer.account.apiKey
                    )
                    safeApiCall { api.sendPowerSignal(identifier, PowerSignalRequest(signal)) }
                }

                when (result) {
                    is ApiResult.HttpError, is ApiResult.NetworkError, is ApiResult.ParseError -> {
                        val msg = result.errorMessage ?: "Power action failed."
                        _uiState.update { state ->
                            state.copy(errors = (state.errors + "[${uiServer.attributes.name}] $msg").takeLast(5))
                        }
                    }
                    else -> { /* Success — resource poll will update the status */ }
                }
            } catch (e: Exception) {
                Log.e(TAG, "sendPowerSignal: unexpected error for $identifier", e)
                _uiState.update { state ->
                    state.copy(errors = (state.errors + "Power action error: ${e.message ?: "unknown"}").takeLast(5))
                }
            }

            // Brief delay for the panel to process the signal before we poll
            delay(1_500L)

            try {
                refreshAllResources()
            } catch (e: Exception) {
                Log.e(TAG, "sendPowerSignal: refreshAllResources threw after power action", e)
            }

            _uiState.update {
                it.copy(powerActionInProgress = it.powerActionInProgress - identifier)
            }
        }
    }

    /** Clears all displayed errors (called by a dismiss button in the UI). */
    fun clearErrors() {
        _uiState.update { it.copy(errors = emptyList()) }
    }

    override fun onCleared() {
        super.onCleared()
        resourcePollJob?.cancel()
    }
}

// ─── Extension — inject panel metadata into ServerAttributes ─────────────────

/**
 * Fills the runtime-only metadata fields on a freshly-deserialised [ServerAttributes].
 *
 * Bug fix: original code used `?.allocations?.data?.firstOrNull { it.attributes.isDefault }`
 * but `data` was a non-nullable List — if Gson left it null (missing JSON field) this threw
 * an NPE.  Fixed by using [AllocationList.safeData] and providing a safe fallback.
 */
private fun ServerAttributes.applyAccountMeta(account: PanelAccount): ServerAttributes {
    panelAccountId = account.id
    panelLabel     = account.label.ifBlank { account.panelUrl }
    // Set initial serverStatus from the JSON "status" field (may be null for older panels)
    if (serverStatus == "offline" && status != null) {
        serverStatus = status
    }
    allocationDisplay = relationships
        ?.allocations
        ?.safeData()
        ?.firstOrNull { it.attributes.isDefault }
        ?.attributes
        ?.displayAddress
        ?: ""
    return this
}
