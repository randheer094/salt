package salt

import kotlinx.serialization.json.Json
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The traffic log. Every finished capture is appended to a JSON-lines file in [dir] (one file per hour) and kept for
 * [retentionMs]; memory holds only a small row per capture plus the file position of its full details. In-flight
 * captures live in memory until they finish. With no [dir] everything stays in memory (tests).
 */
class CaptureStore(
    private val dir: File? = null,
    private val retentionMs: Long = 24 * 60 * 60 * 1000L,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class Place(val file: File, val offset: Long, val length: Int)

    private val rows = ArrayDeque<CaptureRow>()
    private val pending = HashMap<Long, Capture>()
    private val places = HashMap<Long, Place>()
    private val inMemory = HashMap<Long, Capture>()
    private var nextId = 1L
    private var lastPrune = 0L
    private val json = Json { ignoreUnknownKeys = true }
    private val zone = ZoneId.systemDefault()
    private val hourFile = DateTimeFormatter.ofPattern("yyyyMMddHH").withZone(zone)
    private val timeOfDay = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(zone)
    private val withWeekday = DateTimeFormatter.ofPattern("EEE HH:mm:ss").withZone(zone)

    init {
        dir?.mkdirs()
        load()
    }

    private fun clock(time: Long): String {
        val sameDay = LocalDate.ofInstant(Instant.ofEpochMilli(time), zone) == LocalDate.ofInstant(Instant.ofEpochMilli(now()), zone)
        return (if (sameDay) timeOfDay else withWeekday).format(Instant.ofEpochMilli(time))
    }

    private fun files() = dir?.listFiles { f -> f.name.startsWith("traffic-") && f.name.endsWith(".jsonl") }?.sortedBy { it.name }.orEmpty()

    /** Rebuilds the rows from the files of the retention window; unreadable lines are skipped. */
    private fun load() {
        val cutoff = now() - retentionMs
        for (file in files()) {
            if (file.lastModified() < cutoff) { file.delete(); continue }
            val bytes = file.readBytes()
            var start = 0
            while (start < bytes.size) {
                var end = start
                while (end < bytes.size && bytes[end] != '\n'.code.toByte()) end++
                val capture = runCatching { json.decodeFromString<Capture>(String(bytes, start, end - start, Charsets.UTF_8)) }.getOrNull()
                if (capture != null && capture.time >= cutoff) {
                    rows.addLast(capture.toRow(clock(capture.time)))
                    places[capture.id] = Place(file, start.toLong(), end - start)
                    nextId = maxOf(nextId, capture.id + 1)
                }
                start = end + 1
            }
        }
        rows.sortBy { it.id }
    }

    /** Drops entries and files older than the retention window. Cheap to call often; it runs at most every ten minutes. */
    private fun pruneIfDue() {
        if (now() - lastPrune < 10 * 60 * 1000L) return
        lastPrune = now()
        val cutoff = now() - retentionMs
        while (rows.isNotEmpty() && rows.first().time < cutoff) places.remove(rows.removeFirst().id)
        files().forEach { if (it.lastModified() < cutoff) it.delete() }
    }

    @Synchronized fun add(request: RequestSpec, tunnel: Boolean = false): Long {
        pruneIfDue()
        val id = nextId++
        val capture = Capture(id, request, tunnel = tunnel, time = now())
        pending[id] = capture
        rows.addLast(capture.toRow(clock(capture.time)))
        return id
    }

    @Synchronized fun complete(id: Long, response: ResponseSpec, mocked: String? = null) {
        val done = (pending.remove(id) ?: return).copy(response = response, mocked = mocked)
        val file = dir?.let { File(it, "traffic-" + hourFile.format(Instant.ofEpochMilli(done.time)) + ".jsonl") }
        if (file == null) inMemory[id] = done
        else {
            val line = json.encodeToString(Capture.serializer(), done).toByteArray(Charsets.UTF_8)
            val offset = file.length()
            file.appendBytes(line + '\n'.code.toByte())
            places[id] = Place(file, offset, line.size)
        }
        val i = rows.indexOfFirst { it.id == id }
        if (i >= 0) rows[i] = done.toRow(clock(done.time))
    }

    /** List rows with id greater than [id], oldest first. */
    @Synchronized fun after(id: Long): List<CaptureRow> = rows.filter { it.id > id }

    /** Full details of one capture, or null once it has aged out. */
    @Synchronized fun get(id: Long): Capture? {
        pending[id]?.let { return it }
        inMemory[id]?.let { return it }
        val place = places[id] ?: return null
        return runCatching {
            val bytes = ByteArray(place.length)
            RandomAccessFile(place.file, "r").use { it.seek(place.offset); it.readFully(bytes) }
            json.decodeFromString<Capture>(String(bytes, Charsets.UTF_8))
        }.getOrNull()
    }

    @Synchronized fun clear() {
        rows.clear(); pending.clear(); places.clear(); inMemory.clear()
        files().forEach { it.delete() }
    }
}
