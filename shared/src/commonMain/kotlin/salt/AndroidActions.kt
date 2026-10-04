package salt

/**
 * Every Android feature expressed as plain adb / android-CLI argument lists. The server only executes
 * them, so adding a feature never touches server code.
 */
object AndroidActions {
    /** Quotes [s] for the device shell (adb joins `shell` args and hands them to `sh -c`). */
    fun shq(s: String) = "'" + s.replace("'", "'\\''") + "'"

    fun deviceProps(serial: String) = adb(serial, "shell", "getprop")

    fun battery(serial: String) = adb(serial, "shell", "dumpsys", "battery")

    fun deepLink(serial: String, uri: String, pkg: String? = null) = adb(
        serial, "shell", "am", "start", "-W", "-a", "android.intent.action.VIEW", "-d", shq(uri),
        *listOfNotNull(pkg?.takeIf { it.isNotBlank() }?.let(::shq)).toTypedArray(),
    )

    fun listPackages(serial: String, includeSystem: Boolean) =
        adb(serial, "shell", "pm", "list", "packages", if (includeSystem) "-s" else "-3")

    fun launchApp(serial: String, pkg: String) =
        adb(serial, "shell", "monkey", "-p", shq(pkg), "-c", "android.intent.category.LAUNCHER", "1")

    fun forceStop(serial: String, pkg: String) = adb(serial, "shell", "am", "force-stop", shq(pkg))

    fun clearData(serial: String, pkg: String) = adb(serial, "shell", "pm", "clear", shq(pkg))

    fun uninstall(serial: String, pkg: String) = adb(serial, "uninstall", pkg)

    /** [hostPath] is a path on the machine running the salt server. */
    fun installApk(serial: String, hostPath: String) =
        adb(serial, "install", "-r", "-t", hostPath, timeoutSeconds = 300)

    fun tap(serial: String, x: Int, y: Int) = adb(serial, "shell", "input", "tap", x.toString(), y.toString())

    /** A swipe from (x1, y1) to (x2, y2); with the same point twice it is a long press. */
    fun swipe(serial: String, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) =
        adb(serial, "shell", "input", "swipe", x1.toString(), y1.toString(), x2.toString(), y2.toString(), durationMs.toString())

    fun keyEvent(serial: String, key: String) = adb(serial, "shell", "input", "keyevent", key)

    fun typeText(serial: String, text: String) =
        adb(serial, "shell", "input", "text", shq(text.replace(" ", "%s")))

    fun logcat(serial: String, lines: Int = 500) =
        adb(serial, "shell", "logcat", "-d", "-v", "threadtime", "-t", lines.toString())

    fun clearLogcat(serial: String) = adb(serial, "logcat", "-c")

    /** Routes the device's http proxy to the host via `adb reverse`, so it works for emulators and USB devices alike. */
    fun setProxy(serial: String, port: Int) = listOf(
        adb(serial, "reverse", "tcp:$port", "tcp:$port"),
        adb(serial, "shell", "settings", "put", "global", "http_proxy", "127.0.0.1:$port"),
    )

    fun clearProxy(serial: String) = listOf(adb(serial, "shell", "settings", "put", "global", "http_proxy", ":0"))

    /** [hostPath] is a path on the machine running the salt server. */
    fun pushFile(serial: String, hostPath: String, devicePath: String) = adb(serial, "push", hostPath, devicePath)

    fun reboot(serial: String, mode: String? = null) = adb(serial, "reboot", *listOfNotNull(mode).toTypedArray())

    fun setFontScale(serial: String, scale: String) = listOf(adb(serial, "shell", "settings", "put", "system", "font_scale", scale))

    fun resetDisplay(serial: String) = listOf(adb(serial, "shell", "wm", "size", "reset"), adb(serial, "shell", "wm", "density", "reset"))

    /** Applies a toggle: each command runs as `adb shell <command>`. */
    fun toggle(serial: String, commands: List<List<String>>) = commands.map { adb(serial, "shell", *it.toTypedArray()) }

    /** A fixed diagnostic: [command] is passed to `adb shell` as one string, so pipes work. */
    fun diagnostic(serial: String, command: String) = adb(serial, "shell", command)

    // Wireless debugging talks to the adb server, not to a device, so there is no serial.
    fun connect(hostPort: String) = CommandRequest(listOf("connect", hostPort))

    fun pair(hostPort: String, code: String) = CommandRequest(listOf("pair", hostPort, code))

    fun disconnectAll() = CommandRequest(listOf("disconnect"))

    fun listEmulators() = CommandRequest(listOf("emulator", "list"))

    fun listEmulatorProfiles() = CommandRequest(listOf("emulator", "create", "--list-profiles"))

    fun createEmulator(profile: String) = CommandRequest(listOf("emulator", "create", profile), timeoutSeconds = 300)

    fun removeEmulator(name: String) = CommandRequest(listOf("emulator", "remove", name))

    fun startEmulator(name: String) = CommandRequest(listOf("emulator", "start", name), timeoutSeconds = 300)

    fun stopEmulator(name: String) = CommandRequest(listOf("emulator", "stop", name))

    fun sdkInstalled() = CommandRequest(listOf("sdk", "list"))

    /** [pattern] supports `*`, e.g. `platforms*` or `system-images/android-34*`. */
    fun sdkSearch(pattern: String) = CommandRequest(listOf("sdk", "list", "--all", pattern))

    fun sdkInstall(id: String) = CommandRequest(listOf("sdk", "install", id), timeoutSeconds = 1800)

    fun sdkUpdate(id: String) = CommandRequest(listOf("sdk", "update", id), timeoutSeconds = 1800)

    fun sdkRemove(id: String) = CommandRequest(listOf("sdk", "remove", id), timeoutSeconds = 600)

    private fun adb(serial: String, vararg args: String, timeoutSeconds: Int? = null) =
        CommandRequest(args.toList(), serial, timeoutSeconds)}

/** Priority letter (V/D/I/W/E/F) of a `logcat -v threadtime` line, or null for continuation lines. */
fun logLevel(line: String): Char? = Regex("""^\S+ \S+\s+\d+\s+\d+ ([VDIWEF]) """).find(line)?.groupValues?.get(1)?.single()

/** `[key]: [value]` lines from `getprop`. */
fun parseGetprop(output: String): Map<String, String> = output.lineSequence().mapNotNull { line ->
    Regex("""^\[(.+?)]: \[(.*)]$""").find(line.trim())?.destructured?.let { (k, v) -> k to v }
}.toMap()

/** `package:com.example` lines from `pm list packages`, sorted. */
fun parsePackages(output: String): List<String> = output.lineSequence()
    .map { it.trim() }.filter { it.startsWith("package:") }
    .map { it.removePrefix("package:") }.sorted().toList()

/** One bare identifier per line (AVD names, device profiles). */
fun parseNames(output: String): List<String> =
    output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && ' ' !in it }.toList()

class SdkPackage(val id: String, val version: String, val newerVersion: String?, val description: String)

private val sdkLine = Regex("""^\s{2}(\S+)\s+(\S+)(?:\s+->\s+(\S+))?\s{2,}(.*?)\s*$""")

/** Rows of `android sdk list`: `  id  version  [-> newer]  description`. Section headers have no indent and are skipped. */
fun parseSdkPackages(output: String): List<SdkPackage> = output.lineSequence().mapNotNull { line ->
    sdkLine.find(line)?.destructured?.let { (id, v, newer, desc) -> SdkPackage(id, v, newer.ifEmpty { null }, desc) }
}.toList()

/** A one-tap developer setting. [on]/[off] are lists of `adb shell` commands. */
class Toggle(
    val label: String, val hint: String, val on: List<List<String>>, val off: List<List<String>>,
    /** Shell command whose output tells the current state; [isOn] reads that output. */
    val probe: String, val isOn: (String) -> Boolean,
)

private fun settingToggle(label: String, hint: String, ns: String, key: String, on: String = "1", off: String = "0") = Toggle(
    label, hint, listOf(listOf("settings", "put", ns, key, on)), listOf(listOf("settings", "put", ns, key, off)),
    "settings get $ns $key", { it.trim() == on },
)

val deviceToggles = listOf(
    Toggle("Wi-Fi", "", listOf(listOf("svc", "wifi", "enable")), listOf(listOf("svc", "wifi", "disable")), "settings get global wifi_on", { it.trim() == "1" || it.trim() == "2" }),
    Toggle("Mobile data", "", listOf(listOf("svc", "data", "enable")), listOf(listOf("svc", "data", "disable")), "settings get global mobile_data", { it.trim() == "1" }),
    Toggle("Airplane mode", "Android 11+", listOf(listOf("cmd", "connectivity", "airplane-mode", "enable")), listOf(listOf("cmd", "connectivity", "airplane-mode", "disable")), "settings get global airplane_mode_on", { it.trim() == "1" }),
    Toggle("Dark mode", "", listOf(listOf("cmd", "uimode", "night", "yes")), listOf(listOf("cmd", "uimode", "night", "no")), "cmd uimode night", { it.contains("yes") }),
    Toggle(
        "Animations", "Off makes UI tests faster and steadier",
        listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale").map { listOf("settings", "put", "global", it, "1") },
        listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale").map { listOf("settings", "put", "global", it, "0") },
        // "null" means never set, i.e. the default 1.0.
        "settings get global animator_duration_scale", { it.trim() !in setOf("0", "0.0", "0.00") },
    ),
    settingToggle("Show taps", "", "system", "show_touches"),
    settingToggle("Pointer location", "Coordinates overlay", "system", "pointer_location"),
    settingToggle("Stay awake while charging", "", "global", "stay_on_while_plugged_in", on = "7"),
    settingToggle("Don't keep activities", "Destroys activities on leave, to test state restoration", "global", "always_finish_activities"),
)

/** One adb call that prints `index=value` for every toggle, so opening the screen costs a single round trip. */
fun readToggles(serial: String) =
    CommandRequest(listOf("shell", deviceToggles.indices.joinToString("; ") { "echo $it=\$(${deviceToggles[it].probe})" }), serial)

/** Current state per toggle index; a toggle missing from [output] stays unknown. */
fun parseToggleStates(output: String): Map<Int, Boolean> = output.lineSequence().mapNotNull { line ->
    val (i, v) = line.split("=", limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
    i.trim().toIntOrNull()?.let { idx -> deviceToggles.getOrNull(idx)?.let { idx to it.isOn(v) } }
}.toMap()

fun density(serial: String) = CommandRequest(listOf("shell", "wm", "density"), serial)

/** `uiautomator dump` printed to stdout (the /dev/tty trick), for the layout inspector. */
fun uiDump(serial: String) = CommandRequest(listOf("exec-out", "uiautomator", "dump", "/dev/tty"), serial, timeoutSeconds = 30)

val fontScales =listOf("0.85", "1.0", "1.15", "1.3", "2.0")

/** Fixed read-only reports for the Diagnostics tab. */
val diagnostics = listOf(
    "Battery" to "dumpsys battery",
    "Memory" to "dumpsys meminfo -s",
    "Foreground activity" to "dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity'",
    "Network" to "ip -4 addr",
    "Display" to "wm size; wm density",
    "Storage" to "df -h /data",
    "Uptime & load" to "uptime",
    "Top processes" to "top -b -n 1 -m 15",
)

/** One-line device summary, e.g. "Android 14 · API 34 · arm64-v8a". Missing properties are skipped. */
fun deviceSummary(props: Map<String, String>): String = listOfNotNull(
    props["ro.build.version.release"]?.takeIf { it.isNotBlank() }?.let { "Android $it" },
    props["ro.build.version.sdk"]?.takeIf { it.isNotBlank() }?.let { "API $it" },
    props["ro.product.cpu.abi"]?.takeIf { it.isNotBlank() },
).joinToString(" · ")
