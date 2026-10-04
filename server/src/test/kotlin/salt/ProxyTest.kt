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
            assertEquals("POST", c.request.method)
            assertEquals(url, c.request.url)
            assertEquals("hello", c.request.body)
            assertEquals(201, c.response?.status)
            assertTrue(c.response!!.body.endsWith("hello"))
        } finally {
            proxy.stop(); origin.stop(0)
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
