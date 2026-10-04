package salt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class NetworkService(private val storage: SectionStorage) : NetworkApi {
    private val sdata = storage.sdata(Sections.network)
    // Traffic is kept for 24 hours across restarts.
    private val store = CaptureStore(File(sdata, "traffic"))
    private val mitm by lazy { Mitm(sdata) }
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val savedFile = File(sdata, "saved-requests.json")
    private val rulesFile = File(sdata, "mock-rules.json")
    private val envFile = File(sdata, "environments.json")
    @Volatile private var rules: List<MockRule> = readList(rulesFile, MockRule.serializer())
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
            val p = ProxyServer(store, if (decrypt) mitm else null) { rules }
            p.start(port) // throws BindException if the port is taken
            proxy = p
        }
        status()
    }

    override suspend fun captures(after: Long) = store.after(after)

    override suspend fun capture(id: Long) = store.get(id)

    override suspend fun clearCaptures() = store.clear()

    /** Sent requests are logged like proxied ones, so a resend appears in the traffic list. */
    override suspend fun send(request: RequestSpec): Capture = withContext(Dispatchers.IO) {
        val id = store.add(request)
        val response = try {
            Upstream.toSpec(Upstream.exchange(request.method, request.url, request.headers, request.body.toByteArray()))
        } catch (e: Exception) {
            // IllegalArgumentException covers malformed URLs; report instead of failing the request.
            ResponseSpec(error = e.message ?: e.javaClass.simpleName)
        }
        store.complete(id, response)
        store.get(id) ?: Capture(id, request, response)
    }

    private fun <T> readList(file: File, item: KSerializer<T>): List<T> =
        if (file.exists()) runCatching { json.decodeFromString(ListSerializer(item), file.readText()) }.getOrDefault(emptyList()) else emptyList()

    /** Write-then-rename so a crash can't leave a half-written file. */
    private fun writeAtomic(file: File, text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    override suspend fun savedRequests(): List<SavedRequest> = readList(savedFile, SavedRequest.serializer())

    override suspend fun saveRequests(all: List<SavedRequest>) =
        writeAtomic(savedFile, json.encodeToString(ListSerializer(SavedRequest.serializer()), all))

    override suspend fun mockRules(): List<MockRule> = rules

    override suspend fun saveMockRules(all: List<MockRule>) {
        writeAtomic(rulesFile, json.encodeToString(ListSerializer(MockRule.serializer()), all))
        rules = all
    }

    override suspend fun environments(): Environments =
        if (envFile.exists()) runCatching { json.decodeFromString(Environments.serializer(), envFile.readText()) }.getOrDefault(Environments()) else Environments()

    override suspend fun saveEnvironments(all: Environments) = writeAtomic(envFile, json.encodeToString(Environments.serializer(), all))

    private fun status(): ProxyStatus {
        val p = proxy
        return ProxyStatus(p?.running == true, port, p?.decryptsHttps == true, File(sdata, "ca.pem").path)
    }
}
