package salt

import kotlinx.serialization.Serializable

@Serializable
data class Header(val name: String, val value: String)

/** A request to send (composer) — also the editable form of a captured request. */
@Serializable
data class RequestSpec(
    val method: String = "GET",
    val url: String = "",
    val headers: List<Header> = emptyList(),
    val body: String = "",
)

@Serializable
data class ResponseSpec(
    val status: Int = 0,
    val headers: List<Header> = emptyList(),
    val body: String = "",
    val durationMs: Long = 0,
    val error: String? = null,
)

/** One request/response pair seen by the proxy. [status] is null while in flight or on error. */
@Serializable
data class Capture(
    val id: Long,
    val request: RequestSpec,
    val response: ResponseSpec? = null,
    /** CONNECT tunnel that was not decrypted: only host:port is known. */
    val tunnel: Boolean = false,
)

@Serializable
data class SavedRequest(val id: String, val name: String, val request: RequestSpec)

@Serializable
data class ProxyStatus(val running: Boolean, val port: Int, val decryptsHttps: Boolean, val caPath: String)

@Serializable
data class ProxyToggle(val running: Boolean)

interface NetworkApi {
    suspend fun proxyStatus(): ProxyStatus
    /** Starts/stops the proxy using the port and HTTPS settings of the network section. */
    suspend fun setProxy(running: Boolean): ProxyStatus
    /** Captures with id greater than [after], oldest first. */
    suspend fun captures(after: Long): List<Capture>
    suspend fun clearCaptures()
    suspend fun send(request: RequestSpec): ResponseSpec
    suspend fun savedRequests(): List<SavedRequest>
    suspend fun saveRequests(all: List<SavedRequest>)
}

object NetworkPaths {
    const val STATUS = "/api/network/status"
    const val PROXY = "/api/network/proxy"
    const val CAPTURES = "/api/network/captures"
    const val SEND = "/api/network/send"
    const val SAVED = "/api/network/saved"
}

/** "Name: value" lines <-> headers, for the composer's text editor. */
fun parseHeaders(text: String): List<Header> = text.lineSequence().mapNotNull { line ->
    line.split(":", limit = 2).takeIf { it.size == 2 && it[0].isNotBlank() }?.let { Header(it[0].trim(), it[1].trim()) }
}.toList()

fun List<Header>.format(): String = joinToString("\n") { "${it.name}: ${it.value}" }
