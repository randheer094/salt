package salt

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.host
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing

fun main() {
    val port = System.getenv("SALT_PORT")?.toIntOrNull() ?: 8080
    val storage = SectionStorage().also { it.init() }
    // Bind to loopback only: this server can run arbitrary adb commands.
    embeddedServer(Netty, port = port, host = "127.0.0.1") {
        module(LocalDevToolsApi(storage), storage, NetworkService(storage))
    }.start(wait = true)
}

fun Application.module(api: DevToolsApi, settings: SettingsApi, network: NetworkApi) {
    install(ContentNegotiation) { json() }
    // Blocks DNS-rebinding: a web page can't reach this API through a hostname it controls.
    intercept(ApplicationCallPipeline.Plugins) {
        if (call.request.host() !in setOf("127.0.0.1", "localhost", "[::1]")) {
            call.respondText("Forbidden host", status = HttpStatusCode.Forbidden)
            finish()
        }
    }
    install(StatusPages) {
        exception<NoSuchElementException> { call, e -> call.respondText(e.message.orEmpty(), status = HttpStatusCode.NotFound) }
        exception<IllegalArgumentException> { call, e -> call.respondText(e.message.orEmpty(), status = HttpStatusCode.BadRequest) }
        exception<Exception> { call, e -> call.respondText(e.message ?: e.javaClass.simpleName, status = HttpStatusCode.BadGateway) }
    }
    routing {
        get(ApiPaths.DEVICES) { call.respond(api.listDevices()) }
        post(ApiPaths.ADB) { call.respond(api.adb(call.receive())) }
        post(ApiPaths.ANDROID) { call.respond(api.androidCli(call.receive())) }
        get("${ApiPaths.SCREENSHOT}/{serial}") {
            call.respondBytes(api.screenshot(call.parameters["serial"].orEmpty()), ContentType.Image.PNG)
        }
        get("${ApiPaths.SETTINGS}/{section}") { call.respond(settings.getSettings(call.parameters["section"].orEmpty())) }
        put("${ApiPaths.SETTINGS}/{section}") {
            call.respond(settings.saveSettings(call.parameters["section"].orEmpty(), call.receive<Map<String, String>>()))
        }

        get(NetworkPaths.STATUS) { call.respond(network.proxyStatus()) }
        put(NetworkPaths.PROXY) { call.respond(network.setProxy(call.receive<ProxyToggle>().running)) }
        get(NetworkPaths.CAPTURES) { call.respond(network.captures(call.request.queryParameters["after"]?.toLongOrNull() ?: 0)) }
        delete(NetworkPaths.CAPTURES) { network.clearCaptures(); call.respond(HttpStatusCode.NoContent) }
        post(NetworkPaths.SEND) { call.respond(network.send(call.receive())) }
        get(NetworkPaths.SAVED) { call.respond(network.savedRequests()) }
        put(NetworkPaths.SAVED) { network.saveRequests(call.receive()); call.respond(HttpStatusCode.NoContent) }

        staticResources("/", "web")
    }
}
