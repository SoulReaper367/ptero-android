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
import java.util.concurrent.TimeUnit

/**
 * Pterodactyl Client API v1 interface.
 * Base URL example: https://panel.example.com/api/client/
 *
 * All suspending functions return Response<T> so the ViewModel can inspect
 * HTTP status codes (e.g., 403 Forbidden) before accessing the body.
 */
interface PterodactylApi {

    // ─── Account ─────────────────────────────────────────────────────────────

    @GET("account")
    suspend fun getAccount(): Response<AccountResponse>

    // ─── Servers ─────────────────────────────────────────────────────────────

    /**
     * Lists all servers accessible by the API key.
     * Requests the allocations relationship so we get IP/port data in one call.
     */
    @GET("servers?include=allocations&per_page=50")
    suspend fun listServers(
        @Query("page") page: Int = 1
    ): Response<ServerListResponse>

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
     * Send a power signal. Valid signals: "start", "stop", "restart", "kill"
     * Returns 204 No Content on success.
     */
    @POST("servers/{identifier}/power")
    suspend fun sendPowerSignal(
        @Path("identifier") identifier: String,
        @Body body: PowerSignalRequest
    ): Response<Unit>

    // ─── Console ─────────────────────────────────────────────────────────────

    /**
     * Request a short-lived WebSocket token for a server console stream.
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
     * Returns (and caches) a PterodactylApi instance for the given panel URL + API key.
     * Cache key is the panel URL so swapping keys for the same panel invalidates correctly.
     */
    fun getApi(panelUrl: String, apiKey: String): PterodactylApi {
        val cacheKey = "$panelUrl|$apiKey"
        return clientCache.getOrPut(cacheKey) {
            buildApi(panelUrl, apiKey)
        }
    }

    /** Force-rebuild the client (e.g., after credentials change). */
    fun invalidate(panelUrl: String, apiKey: String) {
        clientCache.remove("$panelUrl|$apiKey")
    }

    fun clearAll() = clientCache.clear()

    private fun buildApi(panelUrl: String, apiKey: String): PterodactylApi {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Accept", "application/json")
                    .addHeader("Content-Type", "application/json")
                    // Prevents Cloudflare / WAF from returning 403 "Just a moment..." challenge pages
                    .addHeader(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                    )
                    .build()
                chain.proceed(request)
            }
            .addInterceptor(logging)
            .build()

        // Safely normalize panel URL even if user includes /api/client/ or trailing slashes
        val cleanBase = panelUrl.trimEnd('/')
            .removeSuffix("/api/client")
            .removeSuffix("/api/client/")

        val baseUrl = "$cleanBase/api/client/"

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(PterodactylApi::class.java)
    }
}

// ─── WebSocket helper ─────────────────────────────────────────────────────────

/**
 * Opens a Pterodactyl console WebSocket and returns the [WebSocket] instance.
 * The panel uses a token-based auth handshake immediately after connection.
 */
fun openConsoleWebSocket(
    socketUrl: String,
    token: String,
    panelUrl: String,
    listener: WebSocketListener
): WebSocket {
    val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)   // infinite — keep alive
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    val request = Request.Builder()
        .url(socketUrl)
        .addHeader("Origin", panelUrl)
        .addHeader(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        )
        .build()

    return client.newWebSocket(request, listener)
}

// ─── API result wrapper ───────────────────────────────────────────────────────

sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Error(val code: Int, val message: String) : ApiResult<Nothing>()
    data class NetworkError(val cause: Throwable) : ApiResult<Nothing>()
}

suspend fun <T> safeApiCall(block: suspend () -> Response<T>): ApiResult<T> {
    return try {
        val response = block()
        if (response.isSuccessful) {
            val body = response.body()
            if (body != null) {
                ApiResult.Success(body)
            } else if (response.code() == 204) {
                @Suppress("UNCHECKED_CAST")
                ApiResult.Success(Unit as T)
            } else {
                ApiResult.Error(response.code(), "Empty response body")
            }
        } else {
            ApiResult.Error(
                response.code(),
                response.errorBody()?.string() ?: "HTTP ${response.code()}"
            )
        }
    } catch (e: Exception) {
        ApiResult.NetworkError(e)
    }
}

