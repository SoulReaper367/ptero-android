package com.example.ptero.data

import com.google.gson.annotations.SerializedName

/**
 * Represents a single Pterodactyl panel connection stored in SecureStorage.
 *
 * [serverId] is an optional 8-character Pterodactyl short identifier (e.g. "a1b2c3d4").
 * When set the app fetches ONLY that server via GET /api/client/servers/{identifier}
 * instead of listing all account servers.
 *
 * ## Crash-proofing notes
 * All fields that arrive from the Pterodactyl JSON API have been made nullable where
 * the API documentation marks them optional, or where real-world panels have been
 * observed to omit them.  Gson sets missing nullable fields to null rather than
 * throwing, so the app never crashes on an incomplete JSON payload.
 *
 * Runtime-only mutable fields (currentCpu, etc.) are on [ServerAttributes] rather
 * than here so that the immutable stored model stays clean.
 */
data class PanelAccount(
    val id: String,               // Unique local UUID generated on creation
    val label: String,            // Display name shown in the UI, e.g. "Host 1"
    val panelUrl: String,         // https://panel.example.com  (no trailing slash)
    val apiKey: String,           // ptlc_xxxxxxxxxxxxxxxx
    val serverId: String? = null  // Optional 8-char server short ID to pin a specific server
) {
    /** True when this account is pinned to a single server identifier. */
    val isPinned: Boolean get() = !serverId.isNullOrBlank()
}

// ─── Pterodactyl API response models ─────────────────────────────────────────

data class ServerListResponse(
    @SerializedName("data") val data: List<ServerWrapper>?,   // nullable: empty panels return []
    @SerializedName("meta") val meta: PaginationMeta?
) {
    /** Safe accessor — always returns a non-null list. */
    fun safeData(): List<ServerWrapper> = data?.filterNotNull() ?: emptyList()
}

/**
 * Single-server response returned by GET /api/client/servers/{identifier}.
 */
data class SingleServerResponse(
    @SerializedName("object") val `object`: String?,
    @SerializedName("attributes") val attributes: ServerAttributes
)

data class ServerWrapper(
    @SerializedName("object") val `object`: String?,
    @SerializedName("attributes") val attributes: ServerAttributes
)

data class ServerAttributes(
    @SerializedName("server_owner")  val serverOwner: Boolean  = false,
    @SerializedName("identifier")    val identifier: String    = "",
    @SerializedName("internal_id")   val internalId: Int       = 0,
    @SerializedName("uuid")          val uuid: String          = "",
    @SerializedName("name")          val name: String          = "Unknown Server",
    @SerializedName("node")          val node: String          = "",
    @SerializedName("sftp_details")  val sftpDetails: SftpDetails?  = null,
    @SerializedName("description")   val description: String   = "",
    @SerializedName("limits")        val limits: ServerLimits  = ServerLimits(),
    @SerializedName("invocation")    val invocation: String?   = null,
    @SerializedName("docker_image")  val dockerImage: String?  = null,
    @SerializedName("egg_features")  val eggFeatures: List<String>? = null,
    @SerializedName("feature_limits") val featureLimits: FeatureLimits = FeatureLimits(),
    @SerializedName("status")        val status: String?       = null,
    @SerializedName("is_suspended")  val isSuspended: Boolean  = false,
    @SerializedName("is_installing") val isInstalling: Boolean = false,
    @SerializedName("relationships") val relationships: ServerRelationships? = null
) {
    // ── Runtime-only fields injected after the API response is parsed ─────────
    // These are NOT part of the JSON; they are set locally in applyAccountMeta()
    // and refreshed by resource polling.

    @Transient var currentCpu: Double         = 0.0
    @Transient var currentMemoryBytes: Long   = 0L
    @Transient var currentDiskBytes: Long     = 0L
    @Transient var serverStatus: String       = status ?: "offline"
    @Transient var panelAccountId: String     = ""
    @Transient var panelLabel: String         = ""
    @Transient var allocationDisplay: String  = ""

    /**
     * Safe copy that preserves the runtime transient fields across list replacements.
     * Use this when you have a fresh [ServerAttributes] from the network but want to
     * keep the polled resource values so the UI doesn't flicker to zero.
     */
    fun withRuntimeFieldsFrom(previous: ServerAttributes): ServerAttributes {
        currentCpu         = previous.currentCpu
        currentMemoryBytes = previous.currentMemoryBytes
        currentDiskBytes   = previous.currentDiskBytes
        // serverStatus is refreshed from the resources endpoint — keep the live value
        panelAccountId     = previous.panelAccountId
        panelLabel         = previous.panelLabel
        allocationDisplay  = previous.allocationDisplay
        return this
    }
}

data class SftpDetails(
    @SerializedName("ip")   val ip: String  = "",
    @SerializedName("port") val port: Int   = 22
)

data class ServerLimits(
    @SerializedName("memory") val memory: Long = 0L,  // MB; 0 = unlimited
    @SerializedName("swap")   val swap: Long   = 0L,
    @SerializedName("disk")   val disk: Long   = 0L,  // MB
    @SerializedName("io")     val io: Int      = 0,
    @SerializedName("cpu")    val cpu: Int     = 0    // %; 0 = unlimited
)

data class FeatureLimits(
    @SerializedName("databases")  val databases: Int  = 0,
    @SerializedName("allocations") val allocations: Int = 0,
    @SerializedName("backups")    val backups: Int    = 0
)

data class ServerRelationships(
    @SerializedName("allocations") val allocations: AllocationList? = null
)

data class AllocationList(
    @SerializedName("data") val data: List<AllocationWrapper>? = null
) {
    fun safeData(): List<AllocationWrapper> = data?.filterNotNull() ?: emptyList()
}

data class AllocationWrapper(
    @SerializedName("object")     val `object`: String?         = null,
    @SerializedName("attributes") val attributes: AllocationAttributes
)

data class AllocationAttributes(
    @SerializedName("id")         val id: Int         = 0,
    @SerializedName("ip")         val ip: String      = "",
    @SerializedName("ip_alias")   val ipAlias: String? = null,
    @SerializedName("port")       val port: Int       = 0,
    @SerializedName("notes")      val notes: String?  = null,
    @SerializedName("is_default") val isDefault: Boolean = false
) {
    /** Returns the publicly-visible IP (alias preferred) and port as "ip:port". */
    val displayAddress: String get() = "${ipAlias?.takeIf { it.isNotBlank() } ?: ip}:$port"
}

data class PaginationMeta(
    @SerializedName("pagination") val pagination: Pagination? = null
)

data class Pagination(
    @SerializedName("total")        val total: Int       = 0,
    @SerializedName("count")        val count: Int       = 0,
    @SerializedName("per_page")     val perPage: Int     = 0,
    @SerializedName("current_page") val currentPage: Int = 0,
    @SerializedName("total_pages")  val totalPages: Int  = 1
)

// ─── Resource usage (from /resources endpoint) ───────────────────────────────

data class ResourceUsageResponse(
    @SerializedName("object")     val `object`: String?       = null,
    @SerializedName("attributes") val attributes: ResourceAttributes
)

data class ResourceAttributes(
    @SerializedName("current_state") val currentState: String  = "offline",
    @SerializedName("is_suspended")  val isSuspended: Boolean  = false,
    @SerializedName("resources")     val resources: ResourceStats = ResourceStats()
)

data class ResourceStats(
    @SerializedName("memory_bytes")     val memoryBytes: Long     = 0L,
    @SerializedName("cpu_absolute")     val cpuAbsolute: Double   = 0.0,
    @SerializedName("disk_bytes")       val diskBytes: Long       = 0L,
    @SerializedName("network_rx_bytes") val networkRxBytes: Long  = 0L,
    @SerializedName("network_tx_bytes") val networkTxBytes: Long  = 0L,
    @SerializedName("uptime")           val uptime: Long          = 0L
)

// ─── Power signal request ─────────────────────────────────────────────────────

data class PowerSignalRequest(
    @SerializedName("signal") val signal: String  // "start" | "stop" | "restart" | "kill"
)

// ─── WebSocket auth token ─────────────────────────────────────────────────────

data class WebSocketTokenResponse(
    @SerializedName("data") val data: WebSocketTokenData
)

data class WebSocketTokenData(
    @SerializedName("token")  val token: String  = "",
    @SerializedName("socket") val socket: String = ""
)

// ─── Console WebSocket frames ─────────────────────────────────────────────────

data class WsInboundEvent(
    val event: String              = "",
    val args: List<String>?        = null
)

data class WsOutboundEvent(
    val event: String,
    val args: List<String>
)

// ─── Account info ─────────────────────────────────────────────────────────────

data class AccountResponse(
    @SerializedName("object")     val `object`: String?          = null,
    @SerializedName("attributes") val attributes: AccountAttributes
)

data class AccountAttributes(
    @SerializedName("id")         val id: Int        = 0,
    @SerializedName("admin")      val admin: Boolean = false,
    @SerializedName("username")   val username: String   = "",
    @SerializedName("email")      val email: String      = "",
    @SerializedName("first_name") val firstName: String  = "",
    @SerializedName("last_name")  val lastName: String   = "",
    @SerializedName("language")   val language: String   = "en"
)
