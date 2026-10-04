package salt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetworkToolsTest {
    private fun req(method: String = "GET", url: String, body: String = "") = RequestSpec(method, url, body = body)

    @Test fun urlPatternsUseWildcardsAndIgnoreTheQueryUnlessAsked() {
        assertTrue(urlMatches("api.example.com/users/*", "https://api.example.com/users/42?x=1"))
        assertTrue(urlMatches("https://api.example.com/users", "https://api.example.com/users?x=1"))
        assertFalse(urlMatches("api.example.com/users", "https://api.example.com/users/42"))
        assertFalse(urlMatches("api.example.com/users", "https://other.com/users"))
        assertTrue(urlMatches("*/search?q=*", "https://a.com/search?q=cats"))
        assertFalse(urlMatches("*/search?q=dogs", "https://a.com/search?q=cats"))
        assertTrue(urlMatches("API.example.com/*", "https://api.EXAMPLE.com/x"))
    }

    @Test fun rulesMatchOnMethodBodyAndEnabled() {
        val rule = MockRule("1", method = "POST", url = "a.com/login", bodyContains = "admin")
        assertTrue(rule.matches(req("POST", "https://a.com/login", """{"user":"Admin"}""")))
        assertFalse(rule.matches(req("GET", "https://a.com/login", "admin")))
        assertFalse(rule.matches(req("POST", "https://a.com/login", "guest")))
        assertFalse(rule.copy(enabled = false).matches(req("POST", "https://a.com/login", "admin")))
        assertTrue(rule.copy(method = "ANY").matches(req("PUT", "https://a.com/login", "admin")))
    }

    @Test fun rulesMatchGraphQlOperationsOnTheSameUrl() {
        val rule = MockRule("1", url = "api.example.com/graphql", operation = "getuser")
        val get = req("POST", "https://api.example.com/graphql", """{"query":"query GetUser { user { id } }"}""")
        val other = req("POST", "https://api.example.com/graphql", """{"query":"query Feed { feed }"}""")
        assertTrue(rule.matches(get))
        assertFalse(rule.matches(other))
        assertEquals("1", listOf(rule).firstMatch(get)?.id)
        assertNull(listOf(rule).firstMatch(other))
    }

    @Test fun mapRemoteSwapsHostAndOptionallyPath() {
        assertEquals("https://staging.x.com/v1/users?id=2", mapRemoteUrl("https://api.x.com/v1/users?id=2", "https://staging.x.com"))
        assertEquals("http://localhost:3000/mock?id=2", mapRemoteUrl("https://api.x.com/v1/users?id=2", "http://localhost:3000/mock"))
        assertNull(mapRemoteUrl("not a url", "https://a.com"))
    }

    @Test fun ruleFromCaptureReplaysTheResponseWithoutTransportHeaders() {
        val capture = Capture(
            7, RequestSpec("POST", "https://api.x.com/graphql?a=1", body = """{"query":"query Me { me { id } }"}"""),
            ResponseSpec(201, listOf(Header("Content-Type", "application/json"), Header("Content-Length", "9"), Header("Content-Encoding", "gzip")), """{"a":1}"""),
        )
        val rule = ruleFromCapture(capture, "r1")
        assertEquals("Me", rule.operation)
        assertEquals("api.x.com/graphql", rule.url)
        assertEquals(201, rule.status)
        assertEquals(listOf("Content-Type"), rule.headers.map { it.name })
        assertEquals("""{"a":1}""", rule.body)
        assertEquals("", ruleFromCapture(capture.copy(response = ResponseSpec(200, body = "<binary, 4 bytes>")), "r2").body)
    }

    @Test fun variablesAreSubstitutedAndUnknownOnesReported() {
        val vars = mapOf("host" to "api.x.com", "token" to "abc")
        val r = RequestSpec("GET", "https://{{host}}/me?k={{ key }}", listOf(Header("Authorization", "Bearer {{token}}")))
        val out = r.substitute(vars)
        assertEquals("https://api.x.com/me?k={{ key }}", out.url)
        assertEquals("Bearer abc", out.headers.single().value)
        assertEquals(setOf("key"), r.missingVariables(vars))
    }

    @Test fun curlRoundTrips() {
        val r = RequestSpec("POST", "https://a.com/x?y=1", listOf(Header("Content-Type", "application/json")), """{"it's":"ok"}""")
        assertEquals(r, parseCurl(r.toCurl()))
        assertEquals("curl 'https://a.com'", RequestSpec(url = "https://a.com").toCurl())
    }

    @Test fun parsesCurlAsCopiedFromBrowsers() {
        val r = parseCurl("""curl 'https://a.com/api' \
            -H 'accept: */*' \
            -H "x-token: t1" \
            --data-raw ${'$'}'{"a":"line1\nline2"}' \
            --compressed -u me:pw""")!!
        assertEquals("POST", r.method)
        assertEquals("https://a.com/api", r.url)
        assertEquals("t1", r.headers.first { it.name == "x-token" }.value)
        assertEquals("{\"a\":\"line1\nline2\"}", r.body)
        assertEquals("Basic bWU6cHc=", r.headers.first { it.name == "Authorization" }.value)
        assertNull(parseCurl("echo hi"))
        assertEquals("GET", parseCurl("curl -s example.com")!!.method)
        assertEquals("https://example.com", parseCurl("curl -s example.com")!!.url)
    }
}
