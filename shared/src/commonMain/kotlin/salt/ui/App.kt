package salt.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import salt.DevToolsApi
import salt.Device
import salt.NetworkApi
import salt.NoScriptEngine
import salt.ScriptEngine
import salt.Sections
import salt.SettingsApi
import salt.ToolSection
import salt.ui.design.AppShell
import salt.ui.design.Badge
import salt.ui.design.Divider
import salt.ui.design.HStack
import salt.ui.design.Label
import salt.ui.design.SaltTheme
import salt.ui.design.SideNav
import salt.ui.design.TextStyleKind
import salt.ui.design.Tabs
import salt.ui.design.Tone

/** Everything the UI needs from the outside world. Implemented over HTTP in the browser. */
interface Services : DevToolsApi, SettingsApi, NetworkApi

typealias UiTab = Pair<String, @Composable () -> Unit>

@Composable
fun App(services: Services, only: String? = null, scripts: ScriptEngine = NoScriptEngine) = SaltTheme {
    // `only` (from the URL path) narrows the app to one section; unknown or disabled ids fall back to all.
    val sections = Sections.enabled.filter { only == null || it.id == only }.ifEmpty { Sections.enabled }
    var current by remember { mutableStateOf(0) }
    // The device is app-wide: Android tools and Network (proxy setup) both act on it.
    var device by remember { mutableStateOf<Device?>(null) }
    val section = sections[current]
    AppShell(
        brand = "Salt",
        sections = sections.map { it.title.take(1) to it.title },
        selected = current,
        onSelect = { current = it },
        topBarTitle = if (sections.size > 1) section.title else "Salt ${section.title}",
        topBarTrailing = { DeviceChip(device) },
    ) {
        key(section.id) { SectionContent(section, services, scripts, device) { device = it } }
    }
}

@Composable
private fun DeviceChip(device: Device?) = HStack {
    if (device == null) Label("No device selected", kind = TextStyleKind.Caption)
    else {
        Badge(if (device.isEmulator) "Emulator" else "Device", Tone.Success)
        Label(device.model ?: device.serial, kind = TextStyleKind.Caption)
    }
}

@Composable
private fun SectionContent(section: ToolSection, services: Services, scripts: ScriptEngine, device: Device?, onSelect: (Device) -> Unit) {
    var index by remember { mutableStateOf(0) }
    val tools: List<UiTab> = when (section.id) {
        Sections.android.id -> androidTabs(services, device, onSelect)
        Sections.network.id -> networkTabs(services, device?.serial, scripts) { index = it }
        else -> emptyList()
    }
    // Every section gets a Settings tab, rendered generically from its schema.
    val settingsTab: UiTab = "Settings" to { SettingsScreen(section, services, resolved = { if (section.id == Sections.android.id) services.resolvedPaths() else emptyMap() }) }
    val tabs = tools + settingsTab
    val i = index.coerceAtMost(tabs.lastIndex)
    if (section.id == Sections.android.id) {
        // Nine tools don't fit a tab row; a grouped side list scales and shows what belongs together.
        Row {
            SideNav(androidNav + (null to listOf("Settings")), tabs[i].first, { t -> index = tabs.indexOfFirst { it.first == t } })
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.weight(1f)) { tabs[i].second() }
        }
    } else Column {
        // Only the visible tab's content is composed.
        Tabs(tabs.map { it.first }, i, { index = it })
        Divider()
        tabs[i].second()
    }
}
