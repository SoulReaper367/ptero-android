package com.example.ptero.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ptero.api.*
import com.example.ptero.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class UiServer(
    val attributes: ServerAttributes,
    val account: PanelAccount
)

data class HomeUiState(
    val isLoading: Boolean = false,
    val servers: List<UiServer> = emptyList(),
    val accounts: List<PanelAccount> = emptyList(),
    val error: String? = null,
    val powerActionInProgress: Set<String> = emptySet()  // identifiers
)

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
        if (accounts.isNotEmpty()) {
            fetchAllServers()
        }
    }

    fun addAccount(label: String, panelUrl: String, apiKey: String): Result<Unit> {
        val account = secureStorage.createAccount(label, panelUrl, apiKey)
        val result = secureStorage.saveAccount(account)
        if (result.isSuccess) loadAccounts()
        return result
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
            _uiState.update { it.copy(isLoading = true, error = null) }
            val accounts = secureStorage.getAccounts()
            if (accounts.isEmpty()) {
                _uiState.update { it.copy(isLoading = false, servers = emptyList()) }
                return@launch
            }

            val allServers = mutableListOf<UiServer>()
            val errors = mutableListOf<String>()

            val deferreds = accounts.map { account ->
                async(Dispatchers.IO) {
                    fetchServersForAccount(account)
                }
            }

            deferreds.forEach { deferred ->
                when (val result = deferred.await()) {
                    is ApiResult.Success -> allServers.addAll(result.data)
                    is ApiResult.Error   -> errors.add("HTTP ${result.code}: ${result.message}")
                    is ApiResult.NetworkError -> errors.add(result.cause.message ?: "Network error")
                }
            }

            _uiState.update {
                it.copy(
                    isLoading = false,
                    servers   = allServers,
                    error     = errors.joinToString("\n").ifBlank { null }
                )
            }

            // Kick off resource polling for all servers
            startResourcePolling()
        }
    }

    private suspend fun fetchServersForAccount(
        account: PanelAccount
    ): ApiResult<List<UiServer>> {
        val api = PterodactylApiFactory.getApi(account.panelUrl, account.apiKey)
        return safeApiCall { api.listServers(1) }.let { result ->
            when (result) {
                is ApiResult.Success -> {
                    val servers = result.data.data.map { wrapper ->
                        val attrs = wrapper.attributes.apply {
                            panelAccountId = account.id
                            panelLabel     = account.label
                            // Resolve default allocation display
                            val defaultAlloc = relationships
                                ?.allocations?.data
                                ?.firstOrNull { it.attributes.isDefault }
                                ?.attributes
                            allocationDisplay = if (defaultAlloc != null) {
                                "${defaultAlloc.ipAlias ?: defaultAlloc.ip}:${defaultAlloc.port}"
                            } else ""
                        }
                        UiServer(attrs, account)
                    }
                    ApiResult.Success(servers)
                }
                is ApiResult.Error        -> result
                is ApiResult.NetworkError -> result
            }
        }
    }

    // ─── Resource polling ─────────────────────────────────────────────────────

    private fun startResourcePolling() {
        resourcePollJob?.cancel()
        resourcePollJob = viewModelScope.launch {
            while (isActive) {
                refreshAllResources()
                delay(8_000L) // Poll every 8 seconds
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
                when (val res = safeApiCall { api.getServerResources(uiServer.attributes.identifier) }) {
                    is ApiResult.Success -> {
                        uiServer.attributes.currentCpu         = res.data.attributes.resources.cpuAbsolute
                        uiServer.attributes.currentMemoryBytes = res.data.attributes.resources.memoryBytes
                        uiServer.attributes.currentDiskBytes   = res.data.attributes.resources.diskBytes
                        uiServer.attributes.serverStatus       = res.data.attributes.currentState
                        uiServer
                    }
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
            safeApiCall { api.sendPowerSignal(identifier, PowerSignalRequest(signal)) }
            delay(1_500L) // Brief pause before re-polling so state updates
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
}
