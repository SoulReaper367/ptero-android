package com.example.ptero.data

import com.google.gson.annotations.SerializedName

/**
 * Represents a single Pterodactyl panel connection stored in SecureStorage.
 *
 * [serverId] is an optional 8-character Pterodactyl short identifier (e.g. "a1b2c3d4").
 * When set the app fetches ONLY that server via GET /api/client/servers/{identifier}
 * instead of listing all account servers. This is useful for hosting providers that
 * issue scoped API keys or for users who manage a single known server.
 *
 * Backward-compat note: existing JSON blobs without "server_id" will deserialise fine
 * because Gson leaves missing nullable fields as null.
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
    @SerializedName("data") val data: List<ServerWrapper>,
    @SerializedName("meta") val meta: PaginationMeta?
)

/**
 * Single-server response returned by GET /api/client/servers/{identifier}.
 * The shape is the same object-wrapper the list endpoint uses per-item.
 */
data class SingleServerResponse(
    @SerializedName("object") val `object`: String,
    @SerializedName("attributes") val attributes: ServerAttributes
)

data class ServerWrapper(
    @SerializedName("object") val `object`: String,
    @SerializedName("attributes") val attributes: ServerAttributes
)

data class ServerAttributes(
    @SerializedName("server_owner") val serverOwner: Boolean,
    @SerializedName("identifier") val identifier: String,
    @SerializedName("internal_id") val internalId: Int,
    @SerializedName("uuid") val uuid: String,
    @SerializedName("name") val name: String,
    @SerializedName("node") val node: String,
    @SerializedName("sftp_details") val sftpDetails: SftpDetails?,
    @SerializedName("description") val description: String,
    @SerializedName("limits") val limits: ServerLimits,
    @SerializedName("invocation") val invocation: String?,
    @SerializedName("docker_image") val dockerImage: String?,
    @SerializedName("egg_features") val eggFeatures: List<String>?,
    @SerializedName("feature_limits") val featureLimits: FeatureLimits,
    @SerializedName("status") val status: String?,
    @SerializedName("is_suspended") val isSuspended: Boolean,
    @SerializedName("is_installing") val isInstalling: Boolean,
    @SerializedName("relationships") val relationships: ServerRelationships?
) {
    // Resolved at runtime from ResourceUsage or injected after fetch
    var currentCpu: Double = 0.0
    var currentMemoryBytes: Long = 0L
    var currentDiskBytes: Long = 0L
    var serverStatus: String = "offline"  // offline | starting | running | stopping
    var panelAccountId: String = ""       // injected after fetch
    var panelLabel: String = ""           // injected after fetch — "Host 1" etc.
    var allocationDisplay: String = ""    // "192.168.1.1:25565"
}

data class SftpDetails(
    @SerializedName("ip") val ip: String,
    @SerializedName("port") val port: Int
)

data class ServerLimits(
    @SerializedName("memory") val memory: Long,  // MB
    @SerializedName("swap") val swap: Long,
    @SerializedName("disk") val disk: Long,       // MB
    @SerializedName("io") val io: Int,
    @SerializedName("cpu") val cpu: Int           // %
)

data class FeatureLimits(
    @SerializedName("databases") val databases: Int,
    @SerializedName("allocations") val allocations: Int,
    @SerializedName("backups") val backups: Int
)

data class ServerRelationships(
    @SerializedName("allocations") val allocations: AllocationList?
)

data class AllocationList(
    @SerializedName("data") val data: List<AllocationWrapper>
)

data class AllocationWrapper(
    @SerializedName("object") val `object`: String,
    @SerializedName("attributes") val attributes: AllocationAttributes
)

data class AllocationAttributes(
    @SerializedName("id") val id: Int,
    @SerializedName("ip") val ip: String,
    @SerializedName("ip_alias") val ipAlias: String?,
    @SerializedName("port") val port: Int,
    @SerializedName("notes") val notes: String?,
    @SerializedName("is_default") val isDefault: Boolean
)

data class PaginationMeta(
    @SerializedName("pagination") val pagination: Pagination
)

data class Pagination(
    @SerializedName("total") val total: Int,
    @SerializedName("count") val count: Int,
    @SerializedName("per_page") val perPage: Int,
    @SerializedName("current_page") val currentPage: Int,
    @SerializedName("total_pages") val totalPages: Int
)

// ─── Resource usage (from /resources endpoint) ───────────────────────────────

data class ResourceUsageResponse(
    @SerializedName("object") val `object`: String,
    @SerializedName("attributes") val attributes: ResourceAttributes
)

data class ResourceAttributes(
    @SerializedName("current_state") val currentState: String,
    @SerializedName("is_suspended") val isSuspended: Boolean,
    @SerializedName("resources") val resources: ResourceStats
)

data class ResourceStats(
    @SerializedName("memory_bytes") val memoryBytes: Long,
    @SerializedName("cpu_absolute") val cpuAbsolute: Double,
    @SerializedName("disk_bytes") val diskBytes: Long,
    @SerializedName("network_rx_bytes") val networkRxBytes: Long,
    @SerializedName("network_tx_bytes") val networkTxBytes: Long,
    @SerializedName("uptime") val uptime: Long
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
    @SerializedName("token") val token: String,
    @SerializedName("socket") val socket: String
)

// ─── Console WebSocket frames ─────────────────────────────────────────────────

data class WsInboundEvent(
    val event: String,
    val args: List<String>?
)

data class WsOutboundEvent(
    val event: String,
    val args: List<String>
)

// ─── Account info ─────────────────────────────────────────────────────────────

data class AccountResponse(
    @SerializedName("object") val `object`: String,
    @SerializedName("attributes") val attributes: AccountAttributes
)

data class AccountAttributes(
    @SerializedName("id") val id: Int,
    @SerializedName("admin") val admin: Boolean,
    @SerializedName("username") val username: String,
    @SerializedName("email") val email: String,
    @SerializedName("first_name") val firstName: String,
    @SerializedName("last_name") val lastName: String,
    @SerializedName("language") val language: String
)
