package com.example.ptero.api

import android.util.Log
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private const val TAG = "PterodactylApi"

/**
 * Pterodactyl Client API v1 — Retrofit interface.
 *
 * Base URL is always the panel root + "/api/client/" (trailing slash required by Retrofit).
 *
 * Rules enforced here:
 *  - @Query  for every URL query-string parameter.
 *  - @Path   exclusively for URL path segments enclosed in {braces}.
 *  - No hardcoded query strings in the @GET/@POST annotation values.
 *
 * All functions are suspending and return Response<T> so callers can inspect
 * HTTP status codes before accessing the body. Callers should always go through
 * [safeApiCall] rather than invoking these directly.
 */
interface PterodactylApi {

    // ─── Account ─────────────────────────────────────────────────────────────

    @GET("account")
    suspend fun getAccount(): Response<AccountResponse>

    // ─── Servers ─────────────────────────────────────────────────────────────

    @GET("")
    suspend fun listServers(
        @Query("include")  include: String = "allocations",
        @Query("per_page") perPage: Int    = 50,
        @Query("page")     page: Int       = 1
    ): Response<ServerListResponse>

    @GET("servers/{identifier}")
    suspend fun getServer(
        @Path("identifier") identifier: String,
        @Query("include")   include: String = "allocations"
    ): Response<SingleServerResponse>

    @GET("servers/{identifier}/resources")
    suspend fun getServerResources(
        @Path("identifier") identifier: String
    ): Response<ResourceUsageResponse>

    // ─── Power ───────────────────────────────────────────────────────────────

    @POST("servers/{identifier}/power")
    suspend fun sendPowerSignal(
        @Path("identifier") identifier: String,
        @Body body: PowerSignalRequest
    ): Response<Unit>

    // ─── Console ─────────────────────────────────────────────────────────────

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

    /**
     * ConcurrentHashMap so cache reads/writes are safe when multiple coroutines
     * on Dispatchers.IO call [getApi] simultaneously for different accounts.
     * The old mutableMapOf() was not thread-safe.
     */
    private val clientCache = ConcurrentHashMap<String, PterodactylApi>()

    /**
     * Returns (and caches) a [PterodactylApi] instance keyed on both URL and key.
     * Thread-safe: [ConcurrentHashMap.getOrPut] is atomic for this usage pattern.
     */
    fun getApi(panelUrl: String, apiKey: String): PterodactylApi {
        val cacheKey = "${panelUrl.trim()}|${apiKey.trim()}"
        return clientCache.getOrPut(cacheKey) {
            try {
                buildApi(panelUrl.trim(), apiKey.trim())
            } catch (e: Exception) {
                Log.e(TAG, "getApi: failed to build client for $panelUrl", e)
                throw e
            }
        }
    }

    /** Force-rebuilds the client, e.g. after credentials change. */
    fun invalidate(panelUrl: String, apiKey: String) {
        clientCache.remove("${panelUrl.trim()}|${apiKey.trim()}")
    }

    fun clearAll() = clientCache.clear()

    // ─── Private construction ─────────────────────────────────────────────

    private fun buildApi(panelUrl: String, apiKey: String): PterodactylApi {
        val logging = HttpLoggingInterceptor { message ->
            Log.v(TAG, message)
        }.apply {
            level = HttpLoggingInterceptor.Level.BASIC   // BODY only in debug builds
        }

        val httpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            // Auth + headers interceptor
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Accept", "application/json")
                    .addHeader("Content-Type", "application/json")
                    // Mobile UA to bypass Cloudflare "Just a moment" challenges
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

        val baseUrl = normalizeBaseUrl(panelUrl)
        Log.d(TAG, "buildApi: baseUrl=$baseUrl")

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(httpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(PterodactylApi::class.java)
    }

    /**
     * Normalises any user-supplied panel URL to the canonical Retrofit base URL.
     *
     * Strips any path the user may have accidentally included, removes trailing
     * slashes, then appends exactly "/api/client/" once.
     *
     * Examples:
     *   "https://panel.example.com"             → "https://panel.example.com/api/client/"
     *   "https://panel.example.com/"            → "https://panel.example.com/api/client/"
     *   "https://panel.example.com/api/client"  → "https://panel.example.com/api/client/"
     *   "https://panel.example.com/api/client/" → "https://panel.example.com/api/client/"
     *
     * Bug fix: original code could produce double "/api/client/api/client/" if the user
     * copy-pasted the full API URL. The ordered removeSuffix chain prevents this.
     */
    internal fun normalizeBaseUrl(panelUrl: String): String {
        val cleaned = panelUrl
            .trim()
            .trimEnd('/')
            .let { url ->
                // Strip suffixes from most-specific to least-specific
                when {
                    url.endsWith("/api/client") -> url.dropLast("/api/client".length)
                    url.endsWith("/api")        -> url.dropLast("/api".length)
                    else                        -> url
                }
            }
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
 */
fun openConsoleWebSocket(
    socketUrl: String,
    token: String,
    panelUrl: String,
    listener: WebSocketListener
): WebSocket {
    if (socketUrl.isBlank()) {
        throw IllegalArgumentException("Console WebSocket URL is blank")
    }

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
 * [Success]      — HTTP 2xx with a non-null body (or 204 No Content → Unit).
 * [HttpError]    — HTTP error with a human-readable [userMessage] and raw [code].
 * [NetworkError] — Transport-level failure (DNS, timeout, SSL, etc.).
 * [ParseError]   — Body received but JSON parsing failed.
 */
sealed class ApiResult<out T> {

    data class Success<T>(val data: T) : ApiResult<T>()

    data class HttpError(
        val code: Int,
        val userMessage: String,
        val rawBody: String? = null
    ) : ApiResult<Nothing>()

    data class NetworkError(
        val userMessage: String,
        val cause: Throwable
    ) : ApiResult<Nothing>()

    data class ParseError(
        val userMessage: String = "Unexpected server response — could not parse data.",
        val cause: Throwable
    ) : ApiResult<Nothing>()

    val isSuccess: Boolean get() = this is Success

    val errorMessage: String? get() = when (this) {
        is Success      -> null
        is HttpError    -> userMessage
        is NetworkError -> userMessage
        is ParseError   -> userMessage
    }
}

// ─── HTTP code → human-readable message ──────────────────────────────────────

private fun httpErrorMessage(code: Int, rawBody: String?): String = when (code) {
    400 -> "Bad request — check your panel URL and API key format. (HTTP 400)"
    401 -> "Invalid API key — please check your credentials and try again. (HTTP 401)"
    403 -> {
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
 * Executes [block] on the calling dispatcher (must be Dispatchers.IO at call site)
 * and maps every possible failure mode into a typed [ApiResult].
 *
 * Crash-proofing guarantees:
 *  • All Retrofit/OkHttp exceptions are caught and mapped.
 *  • Gson parse errors are caught separately.
 *  • A null success body on a 2xx response is handled (returns HttpError, not NPE).
 *  • 204 No Content is mapped to Success(Unit) without touching a null body.
 *  • The raw error body string is read inside a nested runCatching so a secondary
 *    IOException reading the error body cannot shadow the original HTTP error.
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
            // Read error body inside its own try/catch — errorBody().string() can throw
            val rawBody = runCatching {
                response.errorBody()?.string()
            }.getOrNull()

            Log.w(TAG, "HTTP ${response.code()} — rawBody=$rawBody")

            ApiResult.HttpError(
                code        = response.code(),
                userMessage = httpErrorMessage(response.code(), rawBody),
                rawBody     = rawBody
            )
        }

    } catch (e: SocketTimeoutException) {
        Log.w(TAG, "safeApiCall: timeout", e)
        ApiResult.NetworkError(
            userMessage = "Connection timed out — check your internet or panel status.",
            cause       = e
        )
    } catch (e: UnknownHostException) {
        Log.w(TAG, "safeApiCall: unknown host", e)
        ApiResult.NetworkError(
            userMessage = "Could not reach the panel — check the URL and your connection.",
            cause       = e
        )
    } catch (e: com.google.gson.JsonSyntaxException) {
        Log.e(TAG, "safeApiCall: JSON parse error", e)
        ApiResult.ParseError(cause = e)
    } catch (e: com.google.gson.JsonIOException) {
        Log.e(TAG, "safeApiCall: JSON IO error", e)
        ApiResult.ParseError(cause = e)
    } catch (e: retrofit2.HttpException) {
        // Retrofit throws this for non-2xx when using non-Response<T> return types.
        // Should not happen with our Response<T> pattern but guard anyway.
        Log.w(TAG, "safeApiCall: Retrofit HttpException code=${e.code()}", e)
        ApiResult.HttpError(
            code        = e.code(),
            userMessage = httpErrorMessage(e.code(), e.message())
        )
    } catch (e: java.io.IOException) {
        Log.w(TAG, "safeApiCall: IO error", e)
        ApiResult.NetworkError(
            userMessage = "Network error — ${e.message ?: "lost connection to the panel."}",
            cause       = e
        )
    } catch (e: Exception) {
        Log.e(TAG, "safeApiCall: unexpected error", e)
        ApiResult.NetworkError(
            userMessage = "Unexpected error — ${e.message ?: "please try again."}",
            cause       = e
        )
    }
}

