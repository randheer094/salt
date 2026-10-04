package salt

import kotlinx.serialization.Serializable

@Serializable
data class Device(
    val serial: String,
    val state: String,
    val attributes: Map<String, String> = emptyMap(),
) {
    val model: String? get() = attributes["model"]?.replace('_', ' ')
    val isOnline: Boolean get() = state == "device"
    val isEmulator: Boolean get() = serial.startsWith("emulator-")
}

@Serializable
data class CommandRequest(
    val args: List<String>,
    /** Target device for adb commands; ignored by the android CLI. */
    val serial: String? = null,
    /** Overrides the configured command timeout (e.g. emulator boot). */
    val timeoutSeconds: Int? = null,
)

@Serializable
data class CommandResult(
    val command: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val ok: Boolean get() = exitCode == 0
}
