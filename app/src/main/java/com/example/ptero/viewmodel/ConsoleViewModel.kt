package com.example.ptero.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ptero.api.*
import com.example.ptero.data.*
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

data class ConsoleUiState(
    val serverName: String      = "",
    val serverStatus: String    = "offline",
    val panelLabel: String      = "",
    val lines: List<String>     = emptyList(),
    val isConnected: Boolean    = false,
    val isConnecting: Boolean   = false,
    val error: String?          = null
)

class ConsoleViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(ConsoleUiState())
    val state: StateFlow<ConsoleUiState> = _state.asStateFlow()

    private var webSocket: WebSocket? = null
    private val gson = Gson()
    private var currentAccount: PanelAccount? = null
    private var currentIdentifier: String = ""

    fun connect(uiServer: UiServer) {
        currentAccount    = uiServer.account
        currentIdentifier = uiServer.attributes.identifier
        _state.update {
            it.copy(
                serverName   = uiServer.attributes.name,
                serverStatus = uiServer.attributes.serverStatus,
                panelLabel   = uiServer.attributes.panelLabel,
                lines        = emptyList(),
                isConnecting = true,
                isConnected  = false,
                error        = null
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            fetchTokenAndConnect(uiServer)
        }
    }

    private suspend fun fetchTokenAndConnect(uiServer: UiServer) {
        val account    = uiServer.account
        val identifier = uiServer.attributes.identifier
        val api        = PterodactylApiFactory.getApi(account.panelUrl, account.apiKey)

        when (val res = safeApiCall { api.getWebSocketCredentials(identifier) }) {
            is ApiResult.Success -> {
                val socketUrl = res.data.data.socket
                val token     = res.data.data.token
                openSocket(socketUrl, token, account.panelUrl)
            }
            else -> {
                _state.update {
                    it.copy(
                        isConnecting = false,
                        error        = res.errorMessage ?: "Failed to get console token."
                    )
                }
            }
        }
    }

    private fun openSocket(socketUrl: String, token: String, panelUrl: String) {
        webSocket?.close(1000, "Reconnecting")
        webSocket = openConsoleWebSocket(
            socketUrl = socketUrl,
            token     = token,
            panelUrl  = panelUrl,
            listener  = object : WebSocketListener() {

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val authEvent = gson.toJson(
                        WsOutboundEvent("auth", listOf(token))
                    )
                    webSocket.send(authEvent)
                    _state.update { it.copy(isConnecting = false, isConnected = true) }
                    webSocket.send(gson.toJson(WsOutboundEvent("send logs", listOf(""))))
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val event = gson.fromJson(text, WsInboundEvent::class.java)
                        when (event.event) {
                            "console output", "install output" -> {
                                val line = event.args?.firstOrNull() ?: return
                                appendLine(line)
                            }
                            "token expiring" -> {
                                viewModelScope.launch(Dispatchers.IO) {
                                    val account = currentAccount ?: return@launch
                                    val api = PterodactylApiFactory.getApi(
                                        account.panelUrl, account.apiKey
                                    )
                                    when (val res = safeApiCall {
                                        api.getWebSocketCredentials(currentIdentifier)
                                    }) {
                                        is ApiResult.Success -> {
                                            val newToken = res.data.data.token
                                            val refresh  = gson.toJson(
                                                WsOutboundEvent("auth", listOf(newToken))
                                            )
                                            webSocket.send(refresh)
                                        }
                                        else -> Unit
                                    }
                                }
                            }
                            "status" -> {
                                val newStatus = event.args?.firstOrNull() ?: return
                                _state.update { it.copy(serverStatus = newStatus) }
                            }
                        }
                    } catch (_: Exception) { /* malformed frame — ignore */ }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    _state.update { it.copy(isConnected = false) }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    _state.update {
                        it.copy(
                            isConnected  = false,
                            isConnecting = false,
                            error        = "WebSocket error: ${t.message}"
                        )
                    }
                }
            }
        )
    }

    private fun appendLine(line: String) {
        _state.update { current ->
            val newLines = (current.lines + line).takeLast(1_000)
            current.copy(lines = newLines)
        }
    }

    fun sendCommand(command: String) {
        if (command.isBlank()) return
        val payload = gson.toJson(WsOutboundEvent("send command", listOf(command)))
        webSocket?.send(payload)
    }

    fun disconnect() {
        webSocket?.close(1000, "User navigated away")
        webSocket = null
        _state.update { it.copy(isConnected = false) }
    }

    override fun onCleared() {
        super.onCleared()
        disconnect()
    }
}
