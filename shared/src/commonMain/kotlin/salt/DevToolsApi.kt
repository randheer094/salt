package salt

/**
 * The single contract between UI and tooling. The server implements it by spawning
 * processes; the web client implements it over HTTP. UI code only sees this interface.
 */
interface DevToolsApi {
    suspend fun listDevices(): List<Device>
    suspend fun adb(request: CommandRequest): CommandResult
    suspend fun androidCli(request: CommandRequest): CommandResult

    /** PNG bytes of the device screen. */
    suspend fun screenshot(serial: String): ByteArray

    /** Opens the scrcpy window for [serial] on the machine running the server; returns a short status message. */
    suspend fun openScrcpy(serial: String): String

    /** What each android setting actually resolves to right now, by setting key; blank when nothing is found. */
    suspend fun resolvedPaths(): Map<String, String>

    /** Captures the screen into the android section's screenshots folder; returns the file path. */
    suspend fun saveScreenshot(serial: String): String
}

object ApiPaths {
    const val DEVICES = "/api/devices"
    const val ADB = "/api/adb"
    const val ANDROID = "/api/android"
    const val SCREENSHOT = "/api/screenshot"
    const val RESOLVED = "/api/android/resolved"
    const val SCRCPY = "/api/android/scrcpy"
    const val SETTINGS = "/api/settings"
}
