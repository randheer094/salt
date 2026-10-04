package salt

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

class NetworkService(private val storage: SectionStorage) : NetworkApi {
    private val sdata = storage.sdata(Sections.network)
    private val store = CaptureStore()
    private val mitm by lazy { Mitm(sdata) }
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val savedFile = File(sdata, "saved-requests.json")
    private val lock = Mutex()
    private var proxy: ProxyServer? = null
    private var port = 0

    override suspend fun proxyStatus() = status()

    override suspend fun setProxy(running: Boolean): ProxyStatus = lock.withLock {
        proxy?.stop(); proxy = null
        if (running) {
            val s = storage.getSettings(Sections.network.id)
            port = s["proxyPort"]?.toIntOrNull() ?: error("Invalid proxy port")
            val decrypt = s["captureHttps"] == "true"
            if (decrypt) mitm.ensureCa()
            val p = ProxyServer(store, if (decrypt) mitm else null)
            p.start(port) // throws BindException if the port is taken
            proxy = p
        }
        status()
    }

    override suspend fun captures(after: Long) = store.after(after)

    override suspend fun clearCaptures() = store.clear()

    override suspend fun send(request: RequestSpec): ResponseSpec = try {
        Upstream.toSpec(Upstream.exchange(request.method, request.url, request.headers, request.body.toByteArray()))
    } catch (e: Exception) {
        // IllegalArgumentException covers malformed URLs; report instead of failing the request.
        ResponseSpec(error = e.message ?: e.javaClass.simpleName)
    }

    override suspend fun savedRequests(): List<SavedRequest> =
        if (savedFile.exists()) runCatching { json.decodeFromString(ListSerializer(SavedRequest.serializer()), savedFile.readText()) }.getOrDefault(emptyList()) else emptyList()

    override suspend fun saveRequests(all: List<SavedRequest>) =
        savedFile.writeText(json.encodeToString(ListSerializer(SavedRequest.serializer()), all))

    private fun status(): ProxyStatus {
        val p = proxy
        return ProxyStatus(p?.running == true, port, p?.decryptsHttps == true, File(sdata, "ca.pem").path)
    }
}
