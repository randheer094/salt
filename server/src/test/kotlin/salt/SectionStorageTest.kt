package salt

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SectionStorageTest {
    private fun storage() = SectionStorage(Files.createTempDirectory("salt-test").toFile())

    @Test
    fun initCreatesSdataPerSection() {
        val s = storage().also { it.init() }
        Sections.all.forEach { assertTrue(java.io.File(s.root, "${it.id}/sdata").isDirectory) }
    }

    @Test
    fun settingsDefaultThenPersist() = runBlocking {
        val s = storage()
        assertEquals("60", s.getSettings("android")["timeoutSeconds"])
        s.saveSettings("android", mapOf("timeoutSeconds" to "5"))
        assertEquals("5", s.getSettings("android")["timeoutSeconds"])
    }

    @Test
    fun rejectsInvalidAndUnknown() = runBlocking {
        val s = storage()
        assertFailsWith<IllegalArgumentException> { s.saveSettings("android", mapOf("timeoutSeconds" to "abc")) }
        assertFailsWith<NoSuchElementException> { s.getSettings("../etc") }
        Unit
    }
}
