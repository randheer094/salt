package salt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GraphQlTest {
    private fun post(body: String, type: String = "application/json") =
        RequestSpec("POST", "https://api.example.com/graphql", listOf(Header("Content-Type", type)), body)

    @Test fun jsonPostWithOperationNameAndVariables() {
        val op = graphQlOperations(post("""{"operationName":"GetUser","query":"query GetUser(${'$'}id: ID!) { user(id: ${'$'}id) { name } }","variables":{"id":"7"}}""")).single()
        assertEquals("query", op.type)
        assertEquals("GetUser", op.name)
        assertTrue(op.variables!!.contains("\"id\": \"7\""))
    }

    @Test fun nameAndTypeComeFromTheQueryWhenOperationNameIsMissing() {
        val op = graphQlOperations(post("""{"query":"mutation Save { save { ok } }"}""")).single()
        assertEquals("mutation", op.type)
        assertEquals("Save", op.name)
        assertNull(op.variables)
    }

    @Test fun anonymousShorthandIsAQuery() {
        val op = graphQlOperations(post("""{"query":"{ me { id } }"}""")).single()
        assertEquals("query", op.type)
        assertNull(op.name)
    }

    @Test fun batchedRequestGivesOneOperationEach() {
        val ops = graphQlOperations(post("""[{"query":"query A { a }"},{"query":"mutation B { b }"}]"""))
        assertEquals(listOf("A", "B"), ops.map { it.name })
        assertEquals("A +1", ops.title())
    }

    @Test fun getRequestReadsTheQueryString() {
        val req = RequestSpec("GET", "https://api.example.com/graphql?query=query%20Q%20%7B%20a%20%7D&variables=%7B%22x%22%3A1%7D")
        val op = graphQlOperations(req).single()
        assertEquals("Q", op.name)
        assertTrue(op.variables!!.contains("\"x\": 1"))
    }

    @Test fun persistedQueryHasNoQueryText() {
        val op = graphQlOperations(post("""{"operationName":"Feed","extensions":{"persistedQuery":{"version":1,"sha256Hash":"abcdef123456"}}}""")).single()
        assertTrue(op.persisted)
        assertNull(op.query)
        assertEquals("Feed", op.name)
    }

    @Test fun applicationGraphqlBodyIsTheQuery() {
        assertEquals("Raw", graphQlOperations(post("query Raw { a }", "application/graphql")).single().name)
    }

    @Test fun otherJsonApisAreNotGraphQl() {
        assertTrue(graphQlOperations(post("""{"query":"shoes","page":2}""")).isEmpty())
        assertTrue(graphQlOperations(post("not json")).isEmpty())
        assertTrue(graphQlOperations(post("""[{"query":"query A { a }"},{"id":1}]""")).isEmpty())
    }

    @Test fun resultsCarryDataAndErrorsWithPaths() {
        val r = ResponseSpec(200, body = """{"data":{"a":null},"errors":[{"message":"Not found","path":["a","b"]}]}""")
        val g = graphQlResults(r, 1).single()!!
        assertEquals(listOf("Not found (a.b)"), g.errors)
        assertTrue(g.data!!.contains("\"a\": null"))
    }

    @Test fun batchResponseMatchesByIndexAndNonGraphQlBodiesAreNull() {
        val r = ResponseSpec(200, body = """[{"data":{"a":1}},{"errors":[{"message":"boom"}]}]""")
        val results = graphQlResults(r, 2)
        assertTrue(results[0]!!.errors.isEmpty())
        assertEquals(listOf("boom"), results[1]!!.errors)
        assertNull(graphQlResults(ResponseSpec(200, body = "<html>"), 1).single())
    }

    @Test fun formatsMinifiedQueries() {
        val q = "query GetUser(\$id: ID!) { user(id: \$id) { name ...F ... on Admin { level } } }"
        assertEquals(
            "query GetUser(\$id: ID!) {\n  user(id: \$id) {\n    name\n    ...F\n    ... on Admin {\n      level\n    }\n  }\n}",
            formatGraphQl(q),
        )
    }

    @Test fun formattedQueriesAreLeftAlone() {
        assertEquals("{\n  a\n}", formatGraphQl("  {\n  a\n}  "))
    }
}
