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
}

object ApiPaths {
    const val DEVICES = "/api/devices"
    const val ADB = "/api/adb"
    const val ANDROID = "/api/android"
    const val SCREENSHOT = "/api/screenshot"
    const val SETTINGS = "/api/settings"
}
