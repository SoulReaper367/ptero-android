package com.example.ptero.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ptero.api.*
import com.example.ptero.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

// ─── UI models ────────────────────────────────────────────────────────────────

data class UiServer(
    val attributes: ServerAttributes,
    val account: PanelAccount
)

/**
 * Screen state emitted by [ServersViewModel].
 *
 * [errors] is a list rather than a single string so that per-panel failures
 * are shown independently — one bad API key shouldn't hide servers from other panels.
 */
data class HomeUiState(
    val isLoading: Boolean = false,
    val servers: List<UiServer> = emptyList(),
    val accounts: List<PanelAccount> = emptyList(),
    val errors: List<String> = emptyList(),        // human-readable, already mapped from ApiResult
    val powerActionInProgress: Set<String> = emptySet()  // server identifiers
) {
    /** Convenience: single error string for legacy callers / simple banners. */
    val error: String? get() = errors.joinToString("\n").ifBlank { null }
}

// ─── ViewModel ────────────────────────────────────────────────────────────────

class ServersViewModel(application: Application) : AndroidViewModel(application) {

    private val secureStorage = SecureStorage(application)

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var resourcePollJob: Job? = null

    init {
        loadAccounts()
    }

    // ─── Account management ───────────────────────────────────────────────────

    fun loadAccounts() {
        val accounts = secureStorage.getAccounts()
        _uiState.update { it.copy(accounts = accounts) }
        if (accounts.isNotEmpty()) fetchAllServers()
    }

    /**
     * Creates and persists a new [PanelAccount].
     *
     * @param serverId Optional 8-character short server identifier. When provided
     *                 this panel entry only loads that specific server.
     */
    fun addAccount(
        label: String,
        panelUrl: String,
        apiKey: String,
        serverId: String? = null
    ): Result<Unit> {
        return try {
            val account = secureStorage.createAccount(label, panelUrl, apiKey, serverId)
            val result = secureStorage.saveAccount(account)
            if (result.isSuccess) {
                loadAccounts()
            }
            result
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun removeAccount(id: String) {
        secureStorage.removeAccount(id)
        PterodactylApiFactory.clearAll()
        loadAccounts()
    }

    fun getDisplayName(): String = secureStorage.getDisplayName()

    // ─── Server fetching ──────────────────────────────────────────────────────

    fun fetchAllServers() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errors = emptyList()) }

            val accounts = secureStorage.getAccounts()
            if (accounts.isEmpty()) {
                _uiState.update { it.copy(isLoading = false, servers = emptyList()) }
                return@launch
            }

            val allServers = mutableListOf<UiServer>()
            val errors     = mutableListOf<String>()

            // Fan out — each account fetches concurrently
            val deferreds = accounts.map { account ->
                async(Dispatchers.IO) {
                    fetchServersForAccount(account)
                }
            }

            deferreds.forEach { deferred ->
                when (val result = deferred.await()) {
                    is ApiResult.Success     -> allServers.addAll(result.data)
                    is ApiResult.HttpError   -> errors.add("[${getAccountLabel(result)}] ${result.userMessage}")
                    is ApiResult.NetworkError -> errors.add(result.userMessage)
                    is ApiResult.ParseError  -> errors.add(result.userMessage)
                }
            }

            _uiState.update {
                it.copy(
                    isLoading = false,
                    servers   = allServers,
                    errors    = errors
                )
            }

            startResourcePolling()
        }
    }

    /**
     * Fetches servers for [account].
     *
     * - If [PanelAccount.isPinned] is true, calls GET /api/client/servers/{identifier}
     *   to load exactly one server.
     * - Otherwise lists all account servers via GET /api/client/servers?include=allocations.
     */
    private suspend fun fetchServersForAccount(
        account: PanelAccount
    ): ApiResult<List<UiServer>> {
        val api = PterodactylApiFactory.getApi(account.panelUrl, account.apiKey)

        return if (account.isPinned) {
            // Single-server path: fetch by known identifier
            when (val result = safeApiCall { api.getServer(account.serverId!!) }) {
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
            // Full-list path
            when (val result = safeApiCall { api.listServers() }) {
                is ApiResult.Success -> {
                    val servers = result.data.data.map { wrapper ->
                        UiServer(wrapper.attributes.applyAccountMeta(account), account)
                    }
                    ApiResult.Success(servers)
                }
                is ApiResult.HttpError    -> result
                is ApiResult.NetworkError -> result
                is ApiResult.ParseError   -> result
            }
        }
    }

    // ─── Resource polling ────────────────────────────────────────────────     

    private fun startResourcePolling() {
        resourcePollJob?.cancel()
        resourcePollJob = viewModelScope.launch {
            while (isActive) {
                refreshAllResources()
                delay(8_000L)
            }
        }
    }

    private suspend fun refreshAllResources() = coroutineScope {
        val current = _uiState.value.servers
        if (current.isEmpty()) return@coroutineScope

        val updated = current.map { uiServer ->
            async(Dispatchers.IO) {
                val api = PterodactylApiFactory.getApi(
                    uiServer.account.panelUrl,
                    uiServer.account.apiKey
                )
                when (val res = safeApiCall {
                    api.getServerResources(uiServer.attributes.identifier)
                }) {
                    is ApiResult.Success -> {
                        uiServer.attributes.apply {
                            currentCpu         = res.data.attributes.resources.cpuAbsolute
                            currentMemoryBytes = res.data.attributes.resources.memoryBytes
                            currentDiskBytes   = res.data.attributes.resources.diskBytes
                            serverStatus       = res.data.attributes.currentState
                        }
                        uiServer
                    }
                    // Silent failure on poll — keep stale data rather than flashing an error
                    else -> uiServer
                }
            }
        }.awaitAll()

        _uiState.update { it.copy(servers = updated) }
    }

    // ─── Power signals ────────────────────────────────────────────────────────

    fun sendPowerSignal(uiServer: UiServer, signal: String) {
        val identifier = uiServer.attributes.identifier
        viewModelScope.launch {
            _uiState.update {
                it.copy(powerActionInProgress = it.powerActionInProgress + identifier)
            }
            val api = PterodactylApiFactory.getApi(
                uiServer.account.panelUrl,
                uiServer.account.apiKey
            )
            val result = safeApiCall {
                api.sendPowerSignal(identifier, PowerSignalRequest(signal))
            }
            if (result is ApiResult.HttpError || result is ApiResult.NetworkError) {
                val msg = result.errorMessage ?: "Power action failed."
                _uiState.update { state ->
                    state.copy(errors = state.errors + msg)
                }
            }
            delay(1_500L)
            refreshAllResources()
            _uiState.update {
                it.copy(powerActionInProgress = it.powerActionInProgress - identifier)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        resourcePollJob?.cancel()
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /** Returns a short label string for error messages when we have the account in scope. */
    private fun getAccountLabel(result: ApiResult.HttpError): String = ""  // expanded below

    private fun getAccountLabelFor(account: PanelAccount): String =
        account.label.ifBlank { account.panelUrl }
}

// ─── Extension — inject panel metadata into ServerAttributes ─────────────────

/**
 * Fills the runtime-only metadata fields on a freshly-deserialised [ServerAttributes].
 * These fields are not part of the API JSON but are needed by the UI.
 */
private fun ServerAttributes.applyAccountMeta(account: PanelAccount): ServerAttributes {
    panelAccountId = account.id
    panelLabel     = account.label
    allocationDisplay = relationships
        ?.allocations?.data
        ?.firstOrNull { it.attributes.isDefault }
        ?.attributes
        ?.let { alloc -> "${alloc.ipAlias ?: alloc.ip}:${alloc.port}" }
        ?: ""
    return this
}

