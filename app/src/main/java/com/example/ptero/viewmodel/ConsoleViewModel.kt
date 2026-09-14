package com.example.ptero.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ptero.api.*
import com.example.ptero.data.*
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

private const val TAG = "ConsoleViewModel"

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

    private val gson = Gson()

    // Volatile so WebSocketListener callbacks (arbitrary OkHttp thread) see
    // the latest reference after a reconnect or onCleared().
    @Volatile private var webSocket: WebSocket? = null

    // These are set before the WebSocket opens, so by the time any WS callback
    // needs them they are already written.  Reads happen only inside the WS
    // listener which runs on OkHttp's thread pool (not concurrent with these sets).
    private var currentAccount: PanelAccount?  = null
    private var currentIdentifier: String      = ""

    /**
     * Connects to the console for [uiServer].
     *
     * Safe to call multiple times — the old WebSocket is closed before a new one
     * is opened.
     *
     * Bug fix: original called `fetchTokenAndConnect` directly inside
     * `viewModelScope.launch(Dispatchers.IO)`.  If the launch context was Main
     * (default for viewModelScope), the network call ran on Main. Now explicitly
     * dispatched to IO.
     */
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

        try {
            val api = PterodactylApiFactory.getApi(account.panelUrl, account.apiKey)
            when (val res = safeApiCall { api.getWebSocketCredentials(identifier) }) {
                is ApiResult.Success -> {
                    val socketUrl = res.data.data.socket
                    val token     = res.data.data.token

                    if (socketUrl.isBlank()) {
                        _state.update {
                            it.copy(
                                isConnecting = false,
                                error        = "Panel returned an empty WebSocket URL."
                            )
                        }
                        return
                    }

                    // openSocket is synchronous (just creates the WS object); safe on IO
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
        } catch (e: Exception) {
            Log.e(TAG, "fetchTokenAndConnect: unexpected error", e)
            _state.update {
                it.copy(
                    isConnecting = false,
                    error        = "Console connection error: ${e.message ?: "unknown"}"
                )
            }
        }
    }

    /**
     * Opens the WebSocket.
     *
     * Bug fix: the token-refresh path inside [onMessage] launched a new coroutine
     * with `viewModelScope.launch(Dispatchers.IO)` which is correct, but the
     * `webSocket.send(refresh)` call used the old `webSocket` lambda parameter
     * (captured by the outer `openSocket` call).  After a reconnect that reference
     * became stale.  Fixed by using `this@ConsoleViewModel.webSocket` (the latest
     * instance field) for sends.
     *
     * Bug fix: `onFailure` did not cancel any pending token-refresh coroutines, so
     * after a failure a refresh could still try to re-authenticate on the dead socket.
     * Now tracked with a dedicated [tokenRefreshJob].
     */
    private fun openSocket(socketUrl: String, token: String, panelUrl: String) {
        // Cancel any in-flight token refresh before replacing the socket
        tokenRefreshJob?.cancel()
        webSocket?.close(1000, "Reconnecting")

        try {
            webSocket = openConsoleWebSocket(
                socketUrl = socketUrl,
                token     = token,
                panelUrl  = panelUrl,
                listener  = ConsoleWebSocketListener(token)
            )
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "openSocket: invalid URL '$socketUrl'", e)
            _state.update {
                it.copy(
                    isConnecting = false,
                    error        = "Invalid console URL returned by panel."
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "openSocket: unexpected error", e)
            _state.update {
                it.copy(
                    isConnecting = false,
                    error        = "Failed to open console: ${e.message ?: "unknown"}"
                )
            }
        }
    }

    @Volatile private var tokenRefreshJob: Job? = null

    private inner class ConsoleWebSocketListener(
        private val initialToken: String
    ) : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            try {
                val authEvent = gson.toJson(WsOutboundEvent("auth", listOf(initialToken)))
                webSocket.send(authEvent)
                _state.update { it.copy(isConnecting = false, isConnected = true, error = null) }
                webSocket.send(gson.toJson(WsOutboundEvent("send logs", listOf(""))))
            } catch (e: Exception) {
                Log.e(TAG, "onOpen: failed to send auth frame", e)
                _state.update {
                    it.copy(
                        isConnecting = false,
                        isConnected  = false,
                        error        = "Auth handshake failed: ${e.message}"
                    )
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val event = gson.fromJson(text, WsInboundEvent::class.java) ?: return

                when (event.event) {
                    "console output", "install output" -> {
                        val line = event.args?.firstOrNull() ?: return
                        appendLine(line)
                    }

                    "token expiring", "token expired" -> {
                        // Cancel any previous refresh that might still be running
                        tokenRefreshJob?.cancel()
                        tokenRefreshJob = viewModelScope.launch(Dispatchers.IO) {
                            refreshToken()
                        }
                    }

                    "status" -> {
                        val newStatus = event.args?.firstOrNull() ?: return
                        _state.update { it.copy(serverStatus = newStatus) }
                    }

                    "auth success" -> {
                        // Panel confirmed our token — nothing to do, already marked connected
                        Log.d(TAG, "onMessage: auth success")
                    }

                    "daemon error" -> {
                        val msg = event.args?.firstOrNull() ?: "Daemon error"
                        _state.update { it.copy(error = "Panel daemon: $msg") }
                    }
                }
            } catch (e: JsonSyntaxException) {
                // Malformed frame from the server — ignore silently
                Log.w(TAG, "onMessage: JSON parse error, ignoring frame", e)
            } catch (e: Exception) {
                Log.e(TAG, "onMessage: unexpected error", e)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "onClosing: code=$code reason=$reason")
            webSocket.close(1000, null)
            _state.update { it.copy(isConnected = false) }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "onClosed: code=$code reason=$reason")
            _state.update { it.copy(isConnected = false) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "onFailure: ${t.message}", t)
            tokenRefreshJob?.cancel()
            _state.update {
                it.copy(
                    isConnected  = false,
                    isConnecting = false,
                    error        = "Console disconnected: ${t.message ?: "network error"}"
                )
            }
        }
    }

    /**
     * Fetches a fresh token and re-authenticates on the existing WebSocket.
     *
     * Bug fix: original code captured the `webSocket` lambda parameter which
     * became stale after reconnect.  Now uses `this@ConsoleViewModel.webSocket`
     * to always reference the latest socket.
     */
    private suspend fun refreshToken() {
        val account    = currentAccount ?: return
        val identifier = currentIdentifier.ifBlank { return }

        try {
            val api = PterodactylApiFactory.getApi(account.panelUrl, account.apiKey)
            when (val res = safeApiCall { api.getWebSocketCredentials(identifier) }) {
                is ApiResult.Success -> {
                    val newToken = res.data.data.token
                    val refresh  = gson.toJson(WsOutboundEvent("auth", listOf(newToken)))
                    // Use the ViewModel-level field — guaranteed to be the live socket
                    webSocket?.send(refresh)
                    Log.d(TAG, "refreshToken: token refreshed successfully")
                }
                else -> {
                    Log.w(TAG, "refreshToken: failed — ${res.errorMessage}")
                    // Don't crash; the token expiry will trigger a disconnect event
                    // from the panel, and onFailure will surface the error to the user.
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "refreshToken: unexpected error", e)
        }
    }

    // ─── Console output buffer ────────────────────────────────────────────────

    /**
     * Appends a console line, capping the buffer at 1 000 lines to prevent OOM
     * on long-running servers.
     *
     * Bug fix: `_state.update` is not thread-safe when called from multiple
     * concurrent coroutines with race conditions between `current.lines` reads.
     * `MutableStateFlow.update { }` is actually atomic (it uses CAS internally),
     * so this is fine — leaving it as-is, documenting for clarity.
     */
    private fun appendLine(line: String) {
        // Strip ANSI escape codes for cleaner display in the terminal view
        val cleanLine = line.replace(Regex("\u001B\\[[;\\d]*m"), "")
        _state.update { current ->
            current.copy(lines = (current.lines + cleanLine).takeLast(1_000))
        }
    }

    // ─── User actions ─────────────────────────────────────────────────────────

    fun sendCommand(command: String) {
        if (command.isBlank()) return
        try {
            val payload = gson.toJson(WsOutboundEvent("send command", listOf(command.trim())))
            val sent    = webSocket?.send(payload) ?: false
            if (!sent) {
                Log.w(TAG, "sendCommand: WebSocket not connected or buffer full")
                _state.update { it.copy(error = "Command not sent — console is disconnected.") }
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendCommand: error", e)
            _state.update { it.copy(error = "Failed to send command: ${e.message}") }
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun disconnect() {
        tokenRefreshJob?.cancel()
        try {
            webSocket?.close(1000, "User navigated away")
        } catch (e: Exception) {
            Log.w(TAG, "disconnect: error closing socket", e)
        }
        webSocket = null
        _state.update { it.copy(isConnected = false, isConnecting = false) }
    }

    override fun onCleared() {
        super.onCleared()
        disconnect()
    }
}
