package salt

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream
import javax.net.ssl.SSLSocket

/** Bounded in-memory log of captures. ponytail: not persisted; append to sdata/ if history across restarts matters. */
class CaptureStore(private val max: Int = 500) {
    private val items = ArrayDeque<Capture>()
    private var nextId = 1L

    @Synchronized fun add(request: RequestSpec, tunnel: Boolean = false): Long {
        val id = nextId++
        items.addLast(Capture(id, request, tunnel = tunnel))
        if (items.size > max) items.removeFirst()
        return id
    }

    @Synchronized fun complete(id: Long, response: ResponseSpec) {
        val i = items.indexOfFirst { it.id == id }
        if (i >= 0) items[i] = items[i].copy(response = response)
    }

    @Synchronized fun after(id: Long): List<Capture> = items.filter { it.id > id }

    @Synchronized fun clear() = items.clear()
}

/** Talks to the real internet on behalf of both the proxy and the composer. */
object Upstream {
    // Never follow redirects: a proxy must hand them to the client untouched.
    private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(15)).build()

    // Headers java.net.http refuses to set, or that describe the client<->proxy hop rather than the request.
    private val skip = setOf(
        "host", "content-length", "connection", "proxy-connection", "proxy-authorization", "keep-alive",
        "transfer-encoding", "te", "upgrade", "expect", "trailer",
    )

    class Raw(val status: Int, val headers: List<Header>, val body: ByteArray, val durationMs: Long)

    fun exchange(method: String, url: String, headers: List<Header>, body: ByteArray): Raw {
        val req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
            .method(method.uppercase(), if (body.isEmpty()) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofByteArray(body))
        headers.filter { it.name.lowercase() !in skip }.forEach { req.header(it.name, it.value) }
        val start = System.currentTimeMillis()
        val res = client.send(req.build(), HttpResponse.BodyHandlers.ofByteArray())
        // HTTP/2 responses carry a ":status" pseudo-header that isn't a real header.
        val out = res.headers().map().filterKeys { !it.startsWith(":") }.flatMap { (k, vs) -> vs.map { Header(k, it) } }
        return Raw(res.statusCode(), out, res.body(), System.currentTimeMillis() - start)
    }

    fun toSpec(raw: Raw) = ResponseSpec(raw.status, raw.headers, bodyText(raw.headers, raw.body), raw.durationMs)

    /** Text for display: gunzips if needed, and refuses binary. ponytail: no brotli/deflate; shows a size instead. */
    fun bodyText(headers: List<Header>, body: ByteArray): String {
        if (body.isEmpty()) return ""
        val encoding = headers.firstOrNull { it.name.equals("content-encoding", true) }?.value?.lowercase()
        val plain = when (encoding) {
            null, "identity" -> body
            "gzip" -> runCatching { GZIPInputStream(body.inputStream()).readNBytes(MAX_BODY + 1) }.getOrNull() ?: return "<undecodable gzip, ${body.size} bytes>"
            else -> return "<$encoding body, ${body.size} bytes>"
        }
        if (plain.take(512).any { it == 0.toByte() }) return "<binary, ${plain.size} bytes>"
        val text = plain.take(MAX_BODY).toByteArray().decodeToString()
        return if (plain.size > MAX_BODY) "$text\n…truncated" else text
    }

    private const val MAX_BODY = 256 * 1024 // 500 captures x 2 bodies x 256 KB bounds memory at ~250 MB worst case
}

private class ParsedRequest(val method: String, val target: String, val headers: List<Header>, val body: ByteArray) {
    fun header(name: String) = headers.firstOrNull { it.name.equals(name, true) }?.value
}

/** ponytail: HTTP/1.1 request-response only. No WebSocket/SSE streaming (responses are buffered), no HTTP/2 to the client. */
class ProxyServer(private val store: CaptureStore, private val mitm: Mitm?) {
    private var server: ServerSocket? = null
    private val pool = Executors.newVirtualThreadPerTaskExecutor()

    val running get() = server?.isClosed == false
    val decryptsHttps get() = mitm != null

    fun start(port: Int) {
        // Loopback only: reached from the device through `adb reverse`, never from the network.
        val s = ServerSocket(port, 50, InetAddress.getLoopbackAddress())
        server = s
        pool.execute {
            while (!s.isClosed) {
                val c = runCatching { s.accept() }.getOrNull() ?: break
                pool.execute { runCatching { c.use { handle(it) } } }
            }
        }
    }

    fun stop() { server?.close() }

    private fun handle(client: Socket) {
        client.soTimeout = 120_000
        val input = BufferedInputStream(client.getInputStream())
        val first = readRequest(input) ?: return
        if (first.method != "CONNECT") return serve(first, input, client.getOutputStream(), "http", null)

        val host = first.target.substringBeforeLast(':')
        val port = first.target.substringAfterLast(':', "443").toIntOrNull() ?: 443
        client.getOutputStream().apply { write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray()); flush() }
        if (mitm == null) return tunnel(client, input, host, port)

        val ssl = mitm.contextFor(host).socketFactory.createSocket(client, null, client.port, true) as SSLSocket
        ssl.useClientMode = false
        ssl.use {
            // Apps that pin certificates fail here; the handshake error is the signal, so record it.
            val ok = runCatching { it.startHandshake() }
            if (ok.isFailure) {
                store.complete(store.add(RequestSpec("CONNECT", "https://${first.target}")), ResponseSpec(error = "TLS handshake failed (client rejects the Salt CA or pins certificates): ${ok.exceptionOrNull()?.message}"))
                return
            }
            val sslIn = BufferedInputStream(it.inputStream)
            readRequest(sslIn)?.let { req -> serve(req, sslIn, it.outputStream, "https", first.target) }
        }
    }

    /** Answers [first] and every keep-alive request that follows it on the same connection. */
    private fun serve(first: ParsedRequest, input: InputStream, out: OutputStream, scheme: String, authority: String?) {
        var req: ParsedRequest? = first
        while (req != null) {
            respond(req, out, scheme, authority)
            req = readRequest(input)
        }
    }

    private fun respond(req: ParsedRequest, out: OutputStream, scheme: String, authority: String?) {
        val host = (authority ?: req.header("host")).orEmpty().removeSuffix(":443")
        val url = if (req.target.startsWith("http")) req.target else "$scheme://$host${req.target}"
        val id = store.add(RequestSpec(req.method, url, req.headers, Upstream.bodyText(req.headers, req.body)))
        try {
            val raw = Upstream.exchange(req.method, url, req.headers, req.body)
            store.complete(id, Upstream.toSpec(raw))
            val hopByHop = setOf("connection", "transfer-encoding", "content-length", "keep-alive")
            val head = StringBuilder("HTTP/1.1 ${raw.status} \r\n")
            raw.headers.filter { it.name.lowercase() !in hopByHop && !it.name.startsWith(":") }.forEach { head.append("${it.name}: ${it.value}\r\n") }
            head.append("Content-Length: ${raw.body.size}\r\nConnection: keep-alive\r\n\r\n")
            out.write(head.toString().toByteArray()); out.write(raw.body); out.flush()
        } catch (e: Exception) {
            store.complete(id, ResponseSpec(error = e.message ?: e.javaClass.simpleName))
            val msg = "salt proxy: ${e.message}".toByteArray()
            out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: ${msg.size}\r\nConnection: close\r\n\r\n".toByteArray() + msg); out.flush()
        }
    }

    /** Blind CONNECT passthrough, used when HTTPS decryption is off. */
    private fun tunnel(client: Socket, clientIn: InputStream, host: String, port: Int) {
        val id = store.add(RequestSpec("CONNECT", "$host:$port"), tunnel = true)
        val upstream = runCatching { Socket(host, port) }.getOrElse {
            store.complete(id, ResponseSpec(error = it.message)); return
        }
        upstream.use {
            store.complete(id, ResponseSpec(status = 200))
            val back = pool.submit { runCatching { it.getInputStream().transferTo(client.getOutputStream()) } }
            runCatching { clientIn.transferTo(it.getOutputStream()) }
            back.cancel(true)
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.size() == 0) null else sb.toString(Charsets.ISO_8859_1)
            if (b == '\n'.code) return sb.toString(Charsets.ISO_8859_1).trimEnd('\r')
            sb.write(b)
        }
    }

    private fun readRequest(input: InputStream): ParsedRequest? {
        val line = runCatching { readLine(input) }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val parts = line.split(" ")
        if (parts.size < 2) return null
        val headers = generateSequence { readLine(input)?.takeIf { it.isNotEmpty() } }
            .mapNotNull { l -> l.split(":", limit = 2).takeIf { it.size == 2 }?.let { Header(it[0].trim(), it[1].trim()) } }.toList()
        val tmp = ParsedRequest(parts[0], parts[1], headers, ByteArray(0))
        val body = when {
            tmp.header("transfer-encoding")?.contains("chunked", true) == true -> readChunked(input)
            else -> input.readNBytes(tmp.header("content-length")?.toIntOrNull() ?: 0)
        }
        return ParsedRequest(parts[0], parts[1], headers, body)
    }

    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val size = readLine(input)?.substringBefore(';')?.trim()?.toIntOrNull(16) ?: break
            if (size == 0) { while (readLine(input)?.isNotEmpty() == true) { /* skip trailers */ }; break }
            out.write(input.readNBytes(size)); readLine(input)
        }
        return out.toByteArray()
    }
}
