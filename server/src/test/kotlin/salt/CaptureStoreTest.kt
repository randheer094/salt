package salt

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaptureStoreTest {
    private val hour = 60 * 60 * 1000L
    private fun dir() = Files.createTempDirectory("salt-traffic").toFile()

    private fun CaptureStore.record(url: String, status: Int = 200, body: String = "ok", method: String = "GET"): Long {
        val id = add(RequestSpec(method, url))
        complete(id, ResponseSpec(status, body = body))
        return id
    }

    @Test fun listsRowsWithoutBodiesAndServesDetailsOnDemand() {
        val store = CaptureStore(dir())
        val id = store.record("https://a.com/x", 201, "big body")
        val row = store.after(0).single()
        assertEquals(listOf(201, 8), listOf(row.status, row.size))
        assertEquals("big body", store.get(id)!!.response!!.body)
        assertTrue(row.clock.matches(Regex("""\d\d:\d\d:\d\d""")))
    }

    @Test fun inFlightCapturesAreVisibleAndCompleteInPlace() {
        val store = CaptureStore(dir())
        val id = store.add(RequestSpec("GET", "https://a.com"))
        assertTrue(store.after(0).single().pending)
        assertEquals(null, store.get(id)!!.response)
        store.complete(id, ResponseSpec(204))
        assertEquals(listOf(204), store.after(0).map { it.status })
        assertEquals(listOf(id), store.after(0).map { it.id }) // same row, not a second one
    }

    @Test fun logSurvivesARestartAndKeepsIds() {
        val d = dir()
        val first = CaptureStore(d)
        val a = first.record("https://a.com/1", body = "one")
        val b = first.record("https://a.com/2", 404)
        val second = CaptureStore(d)
        assertEquals(listOf(a, b), second.after(0).map { it.id })
        assertEquals("one", second.get(a)!!.response!!.body)
        assertEquals(b + 1, second.add(RequestSpec("GET", "https://a.com/3"))) // ids continue
    }

    @Test fun entriesOlderThan24HoursAreDroppedOnLoadAndPruned() {
        val d = dir()
        var clock = 1_000 * hour
        val first = CaptureStore(d, now = { clock })
        val old = first.record("https://a.com/old")
        clock += 20 * hour
        val recent = first.record("https://a.com/recent")
        clock += 6 * hour // "old" is now 26h old, "recent" 6h
        val reloaded = CaptureStore(d, now = { clock })
        assertEquals(listOf(recent), reloaded.after(0).map { it.id })
        assertNull(reloaded.get(old))

        // A running store prunes too (at most every ten minutes), so memory does not grow for ever.
        clock += 30 * hour
        reloaded.record("https://a.com/new")
        assertEquals(listOf("https://a.com/new"), reloaded.after(0).map { it.url })
    }

    @Test fun clearRemovesRowsAndFiles() {
        val d = dir()
        val store = CaptureStore(d)
        store.record("https://a.com")
        store.clear()
        assertTrue(store.after(0).isEmpty())
        assertTrue(CaptureStore(d).after(0).isEmpty())
    }

    @Test fun graphQlInfoIsComputedOncePerRow() {
        val store = CaptureStore()
        val id = store.add(RequestSpec("POST", "https://a.com/graphql", listOf(Header("Content-Type", "application/json")), """{"query":"query GetUser { user { id } }"}"""))
        store.complete(id, ResponseSpec(200, body = """{"data":null,"errors":[{"message":"nope"}]}"""))
        val row = store.after(0).single()
        assertEquals(listOf("GetUser"), row.operations.map { it.name })
        assertTrue(row.gqlErrors)
    }
}
