package salt

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

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
    /** Id of the [MockRule] that answered or rerouted this request, null for real traffic. */
    val mocked: String? = null,
    /** When the request was seen, epoch milliseconds. */
    val time: Long = 0,
)

@Serializable
data class SavedRequest(
    val id: String,
    val name: String,
    val request: RequestSpec,
    /** Script run before sending (can change the request and environment variables). */
    val pre: String = "",
    /** Script run after the response arrives (assertions with pm.test). */
    val tests: String = "",
)

/** What a [MockRule] does with a matching request. */
@Serializable
enum class MockAction {
    /** Answer locally with the rule's status, headers and body; the server is never contacted. */
    MOCK,
    /** Send the request to another host (and optionally path) instead. */
    MAP_REMOTE,
    /** Refuse the request with a 403. */
    BLOCK,
}

/** One proxy rule. Rules are tried in order and the first enabled match wins. Blank match fields match anything. */
@Serializable
data class MockRule(
    val id: String,
    val name: String = "",
    val enabled: Boolean = true,
    val action: MockAction = MockAction.MOCK,
    /** GET, POST, ... or ANY. */
    val method: String = "ANY",
    /** Wildcard (*) pattern on the URL. Without a scheme it matches any scheme; the query is ignored unless the pattern has a "?". */
    val url: String = "",
    /** GraphQL operation name (case-insensitive); blank matches any request. */
    val operation: String = "",
    /** The request body must contain this text. */
    val bodyContains: String = "",
    val status: Int = 200,
    val headers: List<Header> = listOf(Header("Content-Type", "application/json")),
    val body: String = "",
    /** Wait this long before answering (MOCK) or before forwarding (MAP_REMOTE), to test slow networks. */
    val delayMs: Long = 0,
    /** MAP_REMOTE: replacement scheme://host[:port][/path]. A path here replaces the original path. */
    val target: String = "",
)

@Serializable
data class Environment(val name: String, val vars: Map<String, String> = emptyMap())

/** Named sets of variables for {{placeholders}}; [active] picks the one in use. */
@Serializable
data class Environments(val active: String = "", val envs: List<Environment> = emptyList()) {
    val activeVars: Map<String, String> get() = envs.firstOrNull { it.name == active }?.vars.orEmpty()
}

@Serializable
data class ProxyStatus(val running: Boolean, val port: Int, val decryptsHttps: Boolean, val caPath: String)

@Serializable
data class ProxyToggle(val running: Boolean)

interface NetworkApi {
    suspend fun proxyStatus(): ProxyStatus
    /** Starts/stops the proxy using the port and HTTPS settings of the network section. */
    suspend fun setProxy(running: Boolean): ProxyStatus
    /** List rows (no bodies) of captures with id greater than [after], oldest first. The log keeps the last 24 hours. */
    suspend fun captures(after: Long): List<CaptureRow>
    /** The full request and response of one capture, or null once it has aged out. */
    suspend fun capture(id: Long): Capture?
    suspend fun clearCaptures()
    /** Sends [request] now and records it in the log like proxied traffic, so a resend shows up in the list. */
    suspend fun send(request: RequestSpec): Capture
    suspend fun savedRequests(): List<SavedRequest>
    suspend fun saveRequests(all: List<SavedRequest>)
    suspend fun mockRules(): List<MockRule>
    /** Takes effect immediately for the running proxy. */
    suspend fun saveMockRules(all: List<MockRule>)
    suspend fun environments(): Environments
    suspend fun saveEnvironments(all: Environments)
}

object NetworkPaths {
    const val STATUS = "/api/network/status"
    const val PROXY = "/api/network/proxy"
    const val CAPTURES = "/api/network/captures"
    const val CAPTURE = "/api/network/capture"
    const val SEND = "/api/network/send"
    const val SAVED = "/api/network/saved"
    const val MOCKS = "/api/network/mocks"
    const val ENVS = "/api/network/environments"
}

private val prettyJsonFormat = Json { prettyPrint = true }

/** [text] re-indented when it is a JSON object or array; null otherwise. */
fun prettyJson(text: String): String? {
    val t = text.trim()
    if (!t.startsWith("{") && !t.startsWith("[")) return null
    return runCatching { prettyJsonFormat.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(t)) }.getOrNull()
}

/** "Name: value" lines <-> headers, for the composer's text editor. */
fun parseHeaders(text: String): List<Header> = text.lineSequence().mapNotNull { line ->
    line.split(":", limit = 2).takeIf { it.size == 2 && it[0].isNotBlank() }?.let { Header(it[0].trim(), it[1].trim()) }
}.toList()

fun List<Header>.format(): String = joinToString("\n") { "${it.name}: ${it.value}" }
