package salt

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpCallValidator
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import salt.ui.Services

/** Every salt API over HTTP to the local server. Ktor picks the engine per platform. */
class HttpDevToolsApi(private val baseUrl: String) : Services {
    private val client = HttpClient {
        install(ContentNegotiation) { json() }
        // Server errors carry a plain-text message; surface it as the exception message.
        install(HttpCallValidator) {
            validateResponse { if (!it.status.isSuccess()) error(it.bodyAsText().ifBlank { "HTTP ${it.status.value}" }) }
        }
    }

    private suspend inline fun <reified T> get(path: String): T = client.get(baseUrl + path).body()

    private suspend inline fun <reified R, reified B : Any> post(path: String, payload: B): R =
        client.post(baseUrl + path) { contentType(ContentType.Application.Json); setBody(payload) }.body()

    private suspend inline fun <reified R, reified B : Any> put(path: String, payload: B): R =
        client.put(baseUrl + path) { contentType(ContentType.Application.Json); setBody(payload) }.body()

    override suspend fun listDevices(): List<Device> = get(ApiPaths.DEVICES)
    override suspend fun adb(request: CommandRequest): CommandResult = post(ApiPaths.ADB, request)
    override suspend fun androidCli(request: CommandRequest): CommandResult = post(ApiPaths.ANDROID, request)
    override suspend fun screenshot(serial: String): ByteArray = get("${ApiPaths.SCREENSHOT}/$serial")

    override suspend fun getSettings(sectionId: String): Map<String, String> = get("${ApiPaths.SETTINGS}/$sectionId")
    override suspend fun saveSettings(sectionId: String, values: Map<String, String>): Map<String, String> =
        put("${ApiPaths.SETTINGS}/$sectionId", values)

    override suspend fun proxyStatus(): ProxyStatus = get(NetworkPaths.STATUS)
    override suspend fun setProxy(running: Boolean): ProxyStatus = put(NetworkPaths.PROXY, ProxyToggle(running))
    override suspend fun captures(after: Long): List<Capture> = get("${NetworkPaths.CAPTURES}?after=$after")
    override suspend fun clearCaptures() { client.delete(baseUrl + NetworkPaths.CAPTURES) }
    override suspend fun send(request: RequestSpec): ResponseSpec = post(NetworkPaths.SEND, request)
    override suspend fun savedRequests(): List<SavedRequest> = get(NetworkPaths.SAVED)
    override suspend fun saveRequests(all: List<SavedRequest>) { put<Unit, _>(NetworkPaths.SAVED, all) }
}
