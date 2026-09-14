package com.example.ptero.api

import com.example.ptero.data.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Pterodactyl Client API v1 — Retrofit interface.
 *
 * Base URL is always the panel root + "/api/client/" (trailing slash required by Retrofit).
 * Example: https://panel.example.com/api/client/
 *
 * Rules enforced here:
 *  - @Query  for every URL query-string parameter (never embedded in the path string).
 *  - @Path   exclusively for URL path segments enclosed in {braces}.
 *  - No hardcoded query strings in the @GET/@POST annotation values.
 *
 * All functions are suspending and return Response<T> so the ViewModel can inspect
 * HTTP status codes before accessing the body.
 */
interface PterodactylApi {

    // ─── Account ─────────────────────────────────────────────────────────────

    @GET("account")
    suspend fun getAccount(): Response<AccountResponse>

    // ─── Servers ─────────────────────────────────────────────────────────────

    /**
     * Lists all servers accessible by the API key.
     *
     * "include=allocations" is a @Query so it remains a proper query parameter.
     * "per_page" is also a @Query so it can be overridden if needed.
     */
    @GET("")
    suspend fun listServers(
        @Query("include")  include: String = "allocations",
        @Query("per_page") perPage: Int    = 50,
        @Query("page")     page: Int       = 1
    ): Response<ServerListResponse>

    /**
     * Fetches a single server by its 8-character short identifier.
     * Used when the panel account has a pinned [PanelAccount.serverId].
     * Requesting allocations via include avoids a second round-trip.
     */
    @GET("servers/{identifier}")
    suspend fun getServer(
        @Path("identifier") identifier: String,
        @Query("include")   include: String = "allocations"
    ): Response<SingleServerResponse>

    /**
     * Full resource usage snapshot for a single server.
     * Pterodactyl identifier is the short 8-char string shown in the panel.
     */
    @GET("servers/{identifier}/resources")
    suspend fun getServerResources(
        @Path("identifier") identifier: String
    ): Response<ResourceUsageResponse>

    // ─── Power ───────────────────────────────────────────────────────────────

    /**
     * Sends a power signal. Valid values: "start", "stop", "restart", "kill".
     * Returns 204 No Content on success — body will be null / Unit.
     */
    @POST("servers/{identifier}/power")
    suspend fun sendPowerSignal(
        @Path("identifier") identifier: String,
        @Body body: PowerSignalRequest
    ): Response<Unit>

    // ─── Console ─────────────────────────────────────────────────────────────

    /**
     * Requests a short-lived WebSocket token for a server console stream.
     */
    @GET("servers/{identifier}/websocket")
    suspend fun getWebSocketCredentials(
        @Path("identifier") identifier: String
    ): Response<WebSocketTokenResponse>

    // ─── Command ─────────────────────────────────────────────────────────────

    @POST("servers/{identifier}/command")
    suspend fun sendCommand(
        @Path("identifier") identifier: String,
        @Body body: Map<String, String>
    ): Response<Unit>
}

// ─── Factory ─────────────────────────────────────────────────────────────────

object PterodactylApiFactory {

    private val clientCache = mutableMapOf<String, PterodactylApi>()

    /**
     * Returns (and caches) a [PterodactylApi] instance keyed on both URL and key
     * so swapping credentials for the same panel gets a fresh client.
     */
    fun getApi(panelUrl: String, apiKey: String): PterodactylApi {
        val cacheKey = "$panelUrl|$apiKey"
        return clientCache.getOrPut(cacheKey) { buildApi(panelUrl, apiKey) }
    }

    /** Force-rebuilds the client, e.g. after credentials change in Settings. */
    fun invalidate(panelUrl: String, apiKey: String) {
        clientCache.remove("$panelUrl|$apiKey")
    }

    fun clearAll() = clientCache.clear()

    // ─── Private construction ─────────────────────────────────────────────

    private fun buildApi(panelUrl: String, apiKey: String): PterodactylApi {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        val httpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Accept", "application/json")
                    .addHeader("Content-Type", "application/json")
                    // Prevents Cloudflare / WAF from returning 403 "Just a moment…"
                    // challenge pages that would otherwise block default OkHttp UA strings.
                    .addHeader(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 10; Mobile) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Chrome/120.0.0.0 Mobile Safari/537.36"
                    )
                    .build()
                chain.proceed(req)
            }
            .addInterceptor(logging)
            .build()

        return Retrofit.Builder()
            .baseUrl(normalizeBaseUrl(panelUrl))
            .client(httpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(PterodactylApi::class.java)
    }

    /**
     * Normalises any user-supplied panel URL to the canonical Retrofit base URL.
     *
     * Strips any path the user may have accidentally included (/api/client, etc.),
     * removes trailing slashes, then appends exactly "/api/client/" once.
     *
     * Examples:
     *   "https://panel.example.com"             → "https://panel.example.com/api/client/"
     *   "https://panel.example.com/"            → "https://panel.example.com/api/client/"
     *   "https://panel.example.com/api/client"  → "https://panel.example.com/api/client/"
     *   "https://panel.example.com/api/client/" → "https://panel.example.com/api/client/"
     */
    internal fun normalizeBaseUrl(panelUrl: String): String {
        val cleaned = panelUrl
            .trim()
            .trimEnd('/')
            .removeSuffix("/api/client")   // order matters — strip the longer suffix first
            .removeSuffix("/api")
            .trimEnd('/')
        return "$cleaned/api/client/"
    }
}

// ─── WebSocket helper ─────────────────────────────────────────────────────────

/**
 * Opens a Pterodactyl console WebSocket and returns the live [WebSocket] handle.
 *
 * The panel uses a JWT-based auth handshake immediately after the connection
 * upgrade, so [token] must be sent as the first outbound frame.
 *
 * A mobile User-Agent header is included here for the same Cloudflare-bypass
 * reason as in the HTTP client above.
 */
fun openConsoleWebSocket(
    socketUrl: String,
    token: String,
    panelUrl: String,
    listener: WebSocketListener
): WebSocket {
    val wsClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)  // 0 = no timeout; keep-alive stream
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    val request = Request.Builder()
        .url(socketUrl)
        .addHeader("Origin", panelUrl)
        .addHeader(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 10; Mobile) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"
        )
        .build()

    return wsClient.newWebSocket(request, listener)
}

// ─── ApiResult — sealed error hierarchy ──────────────────────────────────────

/**
 * A typed wrapper for every network call result.
 *
 * [Success]      — HTTP 2xx with a non-null body (or 204 No Content mapped to Unit).
 * [HttpError]    — HTTP error with a human-readable [userMessage] and raw [code].
 * [NetworkError] — Transport-level failure (DNS, timeout, SSL, etc.).
 * [ParseError]   — Body received but JSON parsing failed.
 */
sealed class ApiResult<out T> {

    data class Success<T>(val data: T) : ApiResult<T>()

    data class HttpError(
        val code: Int,
        val userMessage: String,        // Displayed directly in the UI
        val rawBody: String? = null     // Full error body kept for debug logging
    ) : ApiResult<Nothing>()

    data class NetworkError(
        val userMessage: String,
        val cause: Throwable
    ) : ApiResult<Nothing>()

    data class ParseError(
        val userMessage: String = "Unexpected server response — could not parse data.",
        val cause: Throwable
    ) : ApiResult<Nothing>()

    // ─── Convenience accessors ────────────────────────────────────────────

    val isSuccess: Boolean get() = this is Success
    val errorMessage: String? get() = when (this) {
        is Success      -> null
        is HttpError    -> userMessage
        is NetworkError -> userMessage
        is ParseError   -> userMessage
    }
}

// ─── HTTP code → human-readable message ──────────────────────────────────────

/**
 * Maps an HTTP status code plus optional raw error body into a clear user-facing
 * string. Raw JSON blobs and HTML challenge pages are never shown to the user.
 */
private fun httpErrorMessage(code: Int, rawBody: String?): String = when (code) {
    400 -> "Bad request — check your panel URL and API key format. (HTTP 400)"
    401 -> "Invalid API key — please check your credentials and try again. (HTTP 401)"
    403 -> {
        // Cloudflare returns 403 with an HTML body containing "Just a moment" or "cf-ray"
        if (rawBody != null &&
            (rawBody.contains("cf-ray", ignoreCase = true) ||
             rawBody.contains("Just a moment", ignoreCase = true) ||
             rawBody.startsWith("<!DOCTYPE", ignoreCase = true))) {
            "Cloudflare blocked the connection — try again or check your panel's firewall. (HTTP 403)"
        } else {
            "Access denied — your API key may lack permissions. (HTTP 403)"
        }
    }
    404 -> "Server not found — check the server identifier or panel URL. (HTTP 404)"
    409 -> "Action conflict — the server may already be in the requested state. (HTTP 409)"
    422 -> "Invalid request data — check the panel URL format. (HTTP 422)"
    429 -> "Rate limited — too many requests. Wait a moment and try again. (HTTP 429)"
    500 -> "Internal panel error — the Pterodactyl server returned HTTP 500."
    502 -> "Bad gateway — your panel may be offline or restarting. (HTTP 502)"
    503 -> "Panel unavailable — service is down or under maintenance. (HTTP 503)"
    504 -> "Gateway timeout — the panel did not respond in time. (HTTP 504)"
    else -> "Unexpected error (HTTP $code)"
}

// ─── safeApiCall — central call wrapper ──────────────────────────────────────

/**
 * Executes [block] and maps every possible failure mode into a typed [ApiResult].
 *
 * Call sites never need try/catch themselves. All network I/O should flow through
 * this function so error categorisation stays consistent across the app.
 */
suspend fun <T> safeApiCall(block: suspend () -> Response<T>): ApiResult<T> {
    return try {
        val response = block()

        if (response.isSuccessful) {
            val body = response.body()
            when {
                body != null -> ApiResult.Success(body)
                response.code() == 204 -> {
                    @Suppress("UNCHECKED_CAST")
                    ApiResult.Success(Unit as T)
                }
                else -> ApiResult.HttpError(
                    code        = response.code(),
                    userMessage = "The server returned an empty response (HTTP ${response.code()})."
                )
            }
        } else {
            val rawBody = runCatching { response.errorBody()?.string() }.getOrNull()
            ApiResult.HttpError(
                code        = response.code(),
                userMessage = httpErrorMessage(response.code(), rawBody),
                rawBody     = rawBody
            )
        }

    } catch (e: SocketTimeoutException) {
        ApiResult.NetworkError(
            userMessage = "Connection timed out — check your internet or panel status.",
            cause       = e
        )
    } catch (e: UnknownHostException) {
        ApiResult.NetworkError(
            userMessage = "Could not reach the panel — check the URL and your connection.",
            cause       = e
        )
    } catch (e: com.google.gson.JsonSyntaxException) {
        ApiResult.ParseError(cause = e)
    } catch (e: com.google.gson.JsonIOException) {
        ApiResult.ParseError(cause = e)
    } catch (e: java.io.IOException) {
        ApiResult.NetworkError(
            userMessage = "Network error — ${e.message ?: "lost connection to the panel."}",
            cause       = e
        )
    } catch (e: Exception) {
        ApiResult.NetworkError(
            userMessage = "Unexpected error — ${e.message ?: "please try again."}",
            cause       = e
        )
    }
}
