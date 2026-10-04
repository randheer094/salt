package salt

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * File-based storage (no database). Layout, per section id:
 *
 *   <root>/<id>/settings.json   user-editable settings
 *   <root>/<id>/sdata/          system-managed data (caches, captures, history, ...)
 *
 * Section ids are validated against [Sections] so callers can't escape [root].
 */
class SectionStorage(val root: File = defaultRoot()) : SettingsApi {
    private val json = Json { prettyPrint = true }
    private val serializer = MapSerializer(String.serializer(), String.serializer())

    fun dir(section: ToolSection): File = File(root, section.id).also { it.mkdirs() }

    fun sdata(section: ToolSection): File = File(dir(section), "sdata").also { it.mkdirs() }

    /** Creates the directory tree for every section up front. */
    fun init() = Sections.all.forEach { sdata(it) }

    override suspend fun getSettings(sectionId: String): Map<String, String> = read(requireSection(sectionId))

    override suspend fun saveSettings(sectionId: String, values: Map<String, String>): Map<String, String> {
        val section = requireSection(sectionId)
        val errors = validateSettings(section, values)
        if (errors.isNotEmpty()) throw IllegalArgumentException(errors.entries.joinToString("; ") { "${it.key}: ${it.value}" })
        val merged = section.defaults + values
        val target = File(dir(section), "settings.json")
        // Write-then-rename so a crash can't leave a half-written settings file.
        val tmp = File(target.parentFile, "settings.json.tmp")
        tmp.writeText(json.encodeToString(serializer, merged))
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        return merged
    }

    private fun read(section: ToolSection): Map<String, String> {
        val file = File(dir(section), "settings.json")
        val stored = if (file.exists()) {
            runCatching { json.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyMap())
        } else emptyMap()
        // Drop keys no longer in the schema; fill new ones from defaults.
        return section.defaults + stored.filterKeys { it in section.defaults }
    }

    private fun requireSection(id: String): ToolSection =
        Sections.find(id) ?: throw NoSuchElementException("Unknown section: $id")

    companion object {
        fun defaultRoot(): File =
            File(System.getenv("SALT_HOME") ?: (System.getProperty("user.home") + "/.salt"))
    }
}
