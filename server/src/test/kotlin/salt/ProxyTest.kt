package salt

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProxyTest {
    @Test
    fun capturesPlainHttpAndForwardsBody() {
        val origin = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/echo") { ex ->
                val reply = "got:" + ex.requestBody.readAllBytes().decodeToString()
                ex.sendResponseHeaders(201, reply.length.toLong())
                ex.responseBody.use { it.write(reply.toByteArray()) }
            }
            start()
        }
        val store = CaptureStore()
        val proxy = ProxyServer(store, mitm = null)
        val port = ServerSocket(0).use { it.localPort }
        proxy.start(port)
        try {
            val client = HttpClient.newBuilder().proxy(ProxySelector.of(InetSocketAddress("127.0.0.1", port))).build()
            val url = "http://127.0.0.1:${origin.address.port}/echo"
            val res = client.send(
                HttpRequest.newBuilder(URI.create(url)).POST(HttpRequest.BodyPublishers.ofString("hello")).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(201, res.statusCode())
            assertEquals("got:hello", res.body())

            val c = store.after(0).single()
            assertEquals("POST", c.method)
            assertEquals(url, c.url)
            assertEquals(201, c.status)
            val full = store.get(c.id)!!
            assertEquals("hello", full.request.body)
            assertTrue(full.response!!.body.endsWith("hello"))
        } finally {
            proxy.stop(); origin.stop(0)
        }
    }

    @Test
    fun rulesMockMapAndBlockWithoutTouchingTheRealServerWhereTheyShouldNot() {
        var hits = 0
        fun origin(label: String) = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                hits++
                ex.sendResponseHeaders(200, label.length.toLong())
                ex.responseBody.use { it.write(label.toByteArray()) }
            }
            start()
        }
        val real = origin("real"); val staging = origin("staging")
        val store = CaptureStore()
        val rules = listOf(
            MockRule("m", name = "fake", url = "127.0.0.1:${real.address.port}/mocked", status = 418, body = "teapot", headers = listOf(Header("X-Mock", "1"))),
            MockRule("r", action = MockAction.MAP_REMOTE, url = "127.0.0.1:${real.address.port}/mapped", target = "http://127.0.0.1:${staging.address.port}"),
            MockRule("b", action = MockAction.BLOCK, url = "127.0.0.1:${real.address.port}/blocked"),
            MockRule("off", enabled = false, url = "127.0.0.1:${real.address.port}/plain", body = "never"),
        )
        val proxy = ProxyServer(store, mitm = null) { rules }
        val port = ServerSocket(0).use { it.localPort }
        proxy.start(port)
        try {
            val client = HttpClient.newBuilder().proxy(ProxySelector.of(InetSocketAddress("127.0.0.1", port))).build()
            fun get(path: String) = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:${real.address.port}$path")).build(), HttpResponse.BodyHandlers.ofString(),
            )
            val mocked = get("/mocked")
            assertEquals(418, mocked.statusCode()); assertEquals("teapot", mocked.body()); assertEquals("1", mocked.headers().firstValue("x-mock").get())
            assertEquals(0, hits)                                 // answered locally
            assertEquals("staging", get("/mapped").body())        // rerouted
            assertEquals(403, get("/blocked").statusCode())
            assertEquals("real", get("/plain").body())            // disabled rule ignored
            assertEquals(listOf("m", "r", "b", null), store.after(0).map { it.mocked })
        } finally {
            proxy.stop(); real.stop(0); staging.stop(0)
        }
    }

    @Test
    fun caIssuesLeafContext() {
        val dir = java.nio.file.Files.createTempDirectory("salt-ca").toFile()
        val mitm = Mitm(dir)
        mitm.contextFor("example.com") // generates CA + leaf; would throw on any X.509 misuse
        assertTrue(mitm.caPem.readText().startsWith("-----BEGIN CERTIFICATE-----"))
        assertEquals(mitm.contextFor("example.com"), mitm.contextFor("example.com")) // cached
    }
}
