package salt

import kotlinx.serialization.Serializable

@Serializable
enum class SettingType { TEXT, NUMBER, BOOL }

/** One configurable value. Values travel as strings; [type] drives validation and the UI control. */
@Serializable
data class SettingDef(
    val key: String,
    val label: String,
    val type: SettingType = SettingType.TEXT,
    val default: String = "",
    val description: String = "",
)

/** A top-level tool section. Its [id] is also its directory name under ~/.salt. */
@Serializable
data class ToolSection(
    val id: String,
    val title: String,
    val description: String,
    val settings: List<SettingDef> = emptyList(),
    val enabled: Boolean = true,
) {
    val defaults: Map<String, String> get() = settings.associate { it.key to it.default }
}

/** Single source of truth for sections: the UI renders from it, the server stores and validates against it. */
object Sections {
    val personal = ToolSection(
        id = "personal",
        title = "Personal",
        description = "Personal development items",
        enabled = false,
    )

    val android = ToolSection(
        id = "android",
        title = "Android",
        description = "adb, emulators, SDK and the Android CLI",
        settings = listOf(
            SettingDef("sdkPath", "SDK path", SettingType.TEXT, description = "Empty = ANDROID_HOME / ANDROID_SDK_ROOT"),
            SettingDef("adbPath", "adb binary", SettingType.TEXT, description = "Empty = <sdk>/platform-tools/adb, then PATH"),
            SettingDef("androidCliPath", "android CLI binary", SettingType.TEXT, description = "Empty = PATH lookup"),
            SettingDef("scrcpyPath", "scrcpy binary", SettingType.TEXT, description = "Optional. Empty = PATH lookup"),
            SettingDef("timeoutSeconds", "Command timeout (s)", SettingType.NUMBER, default = "60"),
        ),
    )

    val network = ToolSection(
        id = "network",
        title = "Network",
        description = "Intercept network calls and compose fresh requests (Proxyman + Postman)",
        settings = listOf(
            SettingDef("proxyPort", "Proxy port", SettingType.NUMBER, default = "9090"),
            SettingDef("captureHttps", "Decrypt HTTPS", SettingType.BOOL, default = "false"),
        ),
    )

    val all = listOf(personal, android, network)
    val enabled get() = all.filter { it.enabled }
    fun find(id: String): ToolSection? = all.firstOrNull { it.id == id }
}

/** Validates [values] against [section]; returns an error message per offending key. */
fun validateSettings(section: ToolSection, values: Map<String, String>): Map<String, String> {
    val errors = mutableMapOf<String, String>()
    val known = section.settings.associateBy { it.key }
    for ((k, v) in values) {
        val def = known[k]
        if (def == null) { errors[k] = "Unknown setting"; continue }
        when (def.type) {
            SettingType.NUMBER -> if (v.isNotBlank() && v.toLongOrNull() == null) errors[k] = "Must be a number"
            SettingType.BOOL -> if (v != "true" && v != "false") errors[k] = "Must be true or false"
            else -> {}
        }
    }
    return errors
}

/** Per-section settings persistence. Returned maps are always merged over the section defaults. */
interface SettingsApi {
    suspend fun getSettings(sectionId: String): Map<String, String>
    suspend fun saveSettings(sectionId: String, values: Map<String, String>): Map<String, String>
}
