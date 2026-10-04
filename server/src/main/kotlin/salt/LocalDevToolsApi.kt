package salt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Runs the real `adb` and `android` binaries on this machine, configured by the "android" section settings. */
class LocalDevToolsApi(private val settings: SettingsApi, private val storage: SectionStorage) : DevToolsApi {

    override suspend fun listDevices(): List<Device> {
        val r = run(Tool.ADB, CommandRequest(listOf("devices", "-l")))
        if (!r.ok) error(r.stderr.ifBlank { "adb exited with ${r.exitCode}" })
        return parseDevices(r.stdout)
    }

    override suspend fun adb(request: CommandRequest): CommandResult = run(Tool.ADB, request)

    override suspend fun androidCli(request: CommandRequest): CommandResult = run(Tool.ANDROID, request)

    override suspend fun screenshot(serial: String): ByteArray {
        val raw = exec(Tool.ADB, CommandRequest(listOf("exec-out", "screencap", "-p"), serial))
        if (raw.exitCode != 0) error(raw.stderr.decodeToString().ifBlank { "screencap failed (exit ${raw.exitCode})" })
        return raw.stdout
    }

    override suspend fun saveScreenshot(serial: String): String = withContext(Dispatchers.IO) {
        val png = screenshot(serial)
        // Output data (not system data): lives beside settings.json, not under sdata/.
        val dir = File(storage.dir(Sections.android), "screenshots").also { it.mkdirs() }
        val stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyMMddHHmmss"))
        File(dir, "$stamp-${serial.replace(Regex("[^A-Za-z0-9._-]"), "_")}.png").also { it.writeBytes(png) }.path
    }

    private class Tools(val sdk: String?, val adb: String, val android: String, val scrcpy: String)

    /** Setting first, then the environment / PATH: the single place that decides which binaries run. */
    private fun tools(s: Map<String, String>): Tools {
        val sdk = s["sdkPath"]?.ifBlank { null } ?: System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        return Tools(
            sdk,
            s["adbPath"]?.ifBlank { null } ?: locate("adb", sdk?.let { "$it/platform-tools" }),
            s["androidCliPath"]?.ifBlank { null } ?: locate("android", null),
            s["scrcpyPath"]?.ifBlank { null } ?: locate("scrcpy", null),
        )
    }

    override suspend fun openScrcpy(serial: String): String = withContext(Dispatchers.IO) {
        require(Regex("[A-Za-z0-9._:-]+").matches(serial)) { "Invalid device serial" }
        val t = tools(settings.getSettings(Sections.android.id))
        if (!File(t.scrcpy).canExecute()) error("scrcpy not found. Install it (for example brew install scrcpy) or set its path in Android settings.")
        // Detached: the scrcpy window outlives this request and is closed from its own window.
        ProcessBuilder(t.scrcpy, "-s", serial)
            .redirectInput(File("/dev/null")).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD)
            .apply { environment()["ADB"] = t.adb }
            .start()
        "Started scrcpy for $serial"
    }

    override suspend fun resolvedPaths(): Map<String, String> = withContext(Dispatchers.IO) {
        val t = tools(settings.getSettings(Sections.android.id))
        mapOf(
            "sdkPath" to t.sdk.orEmpty().takeIf { File(it).isDirectory }.orEmpty(),
            "adbPath" to t.adb.takeIf { File(it).canExecute() }.orEmpty(),
            "androidCliPath" to t.android.takeIf { File(it).canExecute() }.orEmpty(),
            "scrcpyPath" to t.scrcpy.takeIf { File(it).canExecute() }.orEmpty(),
        )
    }

    private enum class Tool { ADB, ANDROID }

    private class Raw(val label: String, val exitCode: Int, val stdout: ByteArray, val stderr: ByteArray)

    private suspend fun run(tool: Tool, request: CommandRequest): CommandResult {
        val r = exec(tool, request)
        return CommandResult(r.label, r.exitCode, r.stdout.decodeToString(), r.stderr.decodeToString())
    }

    private suspend fun exec(tool: Tool, request: CommandRequest): Raw {
        val s = settings.getSettings(Sections.android.id)
        val t = tools(s)
        val sdk = t.sdk
        val bin = if (tool == Tool.ADB) t.adb else t.android
        // The android CLI has no device selector; adb takes it as a leading -s.
        val args = (if (tool == Tool.ADB) request.serial?.let { listOf("-s", it) }.orEmpty() else emptyList()) + request.args
        val timeoutMs = (request.timeoutSeconds ?: s["timeoutSeconds"]?.toIntOrNull() ?: 60).coerceAtLeast(1) * 1000L
        return spawn(bin, args, timeoutMs, sdk?.let { mapOf("ANDROID_HOME" to it) }.orEmpty())
    }

    /**
     * Output goes to temp files rather than pipes: `adb` may fork a daemon that inherits the pipe and keeps it
     * open forever, which would block a read-to-EOF. Files also make the timeout reliable (no blocked readers).
     */
    private suspend fun spawn(bin: String, args: List<String>, timeoutMs: Long, env: Map<String, String>): Raw =
        withContext(Dispatchers.IO) {
            val label = (listOf(File(bin).name) + args).joinToString(" ")
            val out = Files.createTempFile("salt-out", null)
            val err = Files.createTempFile("salt-err", null)
            try {
                val proc = try {
                    ProcessBuilder(listOf(bin) + args)
                        .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                        .redirectOutput(out.toFile()).redirectError(err.toFile())
                        .apply { environment().putAll(env) }
                        .start()
                } catch (e: IOException) {
                    return@withContext Raw(label, -1, ByteArray(0), "Cannot start $bin: ${e.message}".encodeToByteArray())
                }
                if (!proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                    proc.destroyForcibly()
                    return@withContext Raw(label, -1, ByteArray(0), "Timed out after ${timeoutMs / 1000}s".encodeToByteArray())
                }
                Raw(label, proc.exitValue(), Files.readAllBytes(out), Files.readAllBytes(err))
            } finally {
                Files.deleteIfExists(out); Files.deleteIfExists(err)
            }
        }

    /** Looks in [preferredDir] first, then PATH; falls back to the bare name so the error is explicit. */
    private fun locate(name: String, preferredDir: String?): String {
        val dirs = listOfNotNull(preferredDir) + System.getenv("PATH").orEmpty().split(File.pathSeparator)
        return dirs.map { File(it, name) }.firstOrNull { it.canExecute() }?.path ?: name
    }
}
