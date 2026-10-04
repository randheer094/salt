package salt.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.decodeToImageBitmap
import salt.AndroidActions
import salt.CommandRequest
import salt.CommandResult
import salt.DevToolsApi
import salt.Device
import salt.deviceInfoKeys
import salt.logLevel
import salt.SdkPackage
import salt.deviceToggles
import salt.diagnostics
import salt.fontScales
import salt.parseGetprop
import salt.parseNames
import salt.parsePackages
import salt.parseSdkPackages
import salt.ui.design.Badge
import salt.ui.design.ButtonKind
import salt.ui.design.Check
import salt.ui.design.Code
import salt.ui.design.ConfirmButton
import salt.ui.design.EmptyState
import salt.ui.design.HStack
import salt.ui.design.Label
import salt.ui.design.Notice
import salt.ui.design.Page
import salt.ui.design.Panel
import salt.ui.design.SaltButton
import salt.ui.design.SaltTextField
import salt.ui.design.SaltTheme
import salt.ui.design.Space
import salt.ui.design.TextStyleKind
import salt.ui.design.Tone
import salt.ui.design.Select
import salt.ui.design.VStack
import salt.ui.design.Wrap

fun androidTabs(api: DevToolsApi, device: Device?, onSelect: (Device) -> Unit): List<UiTab> {
    val serial = device?.serial
    return listOf(
        "Devices" to { DevicesScreen(api, device, onSelect) },
        "Apps" to { NeedsDevice(serial) { AppsScreen(api, it) } },
        "Deep links" to { NeedsDevice(serial) { DeepLinkScreen(api, it) } },
        "Screen" to { NeedsDevice(serial) { ScreenScreen(api, it) } },
        "Logcat" to { NeedsDevice(serial) { LogcatScreen(api, it) } },
        "Device" to { NeedsDevice(serial) { DeviceToolsScreen(api, it) } },
        "Diagnostics" to { NeedsDevice(serial) { DiagnosticsScreen(api, it) } },
        "Emulators" to { EmulatorsScreen(api) },
        "SDK" to { SdkScreen(api) },
    )
}

/** Runs [requests] in order through adb; returns the first failure, else the last result. */
private suspend fun DevToolsApi.adbAll(requests: List<CommandRequest>): CommandResult {
    val results = requests.map { adb(it) }
    return results.firstOrNull { !it.ok } ?: results.last()
}

/** Gate for tabs that act on one device. */
@Composable
fun NeedsDevice(serial: String?, content: @Composable (String) -> Unit) {
    if (serial == null) EmptyState("Select a device on the Devices tab first.") else content(serial)
}

@Composable
private fun DevicesScreen(api: DevToolsApi, selected: Device?, onSelect: (Device) -> Unit) {
    var devices by remember { mutableStateOf<List<Device>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var props by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var hostPort by remember { mutableStateOf("") }
    var pairHostPort by remember { mutableStateOf("") }
    var pairCode by remember { mutableStateOf("") }
    val wireless = rememberRunner()

    LaunchedEffect(refresh) {
        error = null
        runCatching { api.listDevices() }
            .onSuccess { list ->
                devices = list
                // Convenience: a lone online device needs no manual selection.
                if (selected == null) list.singleOrNull { it.isOnline }?.let(onSelect)
            }
            .onFailure { error = it.message ?: "Failed to load devices" }
    }
    LaunchedEffect(selected?.serial) {
        props = emptyMap()
        val s = selected?.serial ?: return@LaunchedEffect
        runCatching { api.adb(AndroidActions.deviceProps(s)) }.onSuccess { props = parseGetprop(it.stdout) }
    }

    Page(Modifier.verticalScroll(rememberScrollState())) {
        SaltButton("Refresh", { refresh++ }, kind = ButtonKind.Secondary)
        error?.let { Notice(it) }
        if (error == null && devices.isEmpty()) EmptyState("No devices connected. Start an emulator or plug in a device with USB debugging.")
        devices.forEach { d ->
            Panel {
                HStack {
                    VStack(Modifier.weight(1f), Space.xs) {
                        Label(d.model ?: d.serial, kind = TextStyleKind.Heading)
                        Label("${d.serial}${if (d.isEmulator) " · emulator" else ""}", kind = TextStyleKind.Caption)
                    }
                    Badge(d.state, if (d.isOnline) Tone.Success else Tone.Warning)
                    SaltButton(if (d.serial == selected?.serial) "Selected" else "Select", { onSelect(d) }, enabled = d.isOnline && d.serial != selected?.serial)
                }
            }
        }
        if (props.isNotEmpty()) {
            Label("Selected device", kind = TextStyleKind.Heading)
            deviceInfoKeys.forEach { (label, key) -> Label("$label: ${props[key].orEmpty()}") }
        }
        Label("Wireless debugging", kind = TextStyleKind.Heading)
        Label("On the phone: Developer options → Wireless debugging. Pair once with the code, then connect.", kind = TextStyleKind.Caption)
        HStack {
            SaltTextField(pairHostPort, { pairHostPort = it }, "Pairing address (ip:port)", Modifier.weight(1f))
            SaltTextField(pairCode, { pairCode = it }, "Pairing code", Modifier.weight(1f))
            SaltButton("Pair", { wireless.run { api.adb(AndroidActions.pair(pairHostPort.trim(), pairCode.trim())) } }, kind = ButtonKind.Secondary, enabled = pairHostPort.isNotBlank() && pairCode.isNotBlank() && !wireless.running)
        }
        fun connect() { if (hostPort.isNotBlank()) wireless.run(after = { refresh++ }) { api.adb(AndroidActions.connect(hostPort.trim())) } }
        HStack {
            SaltTextField(hostPort, { hostPort = it }, "Device address (ip:port)", Modifier.weight(1f), onEnter = ::connect)
            SaltButton("Connect", ::connect, enabled = hostPort.isNotBlank() && !wireless.running)
            SaltButton("Disconnect all", { wireless.run(after = { refresh++ }) { api.adb(AndroidActions.disconnectAll()) } }, kind = ButtonKind.Secondary, enabled = !wireless.running)
        }
        OutputView(wireless)
    }
}

@Composable
private fun AppsScreen(api: DevToolsApi, serial: String) {
    var packages by remember { mutableStateOf<List<String>>(emptyList()) }
    var filter by remember { mutableStateOf("") }
    var system by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    var apkPath by remember { mutableStateOf("") }
    val runner = rememberRunner()

    LaunchedEffect(serial, system, reload) {
        runCatching { api.adb(AndroidActions.listPackages(serial, system)) }.onSuccess { packages = parsePackages(it.stdout) }
    }

    fun act(request: CommandRequest, reloadAfter: Boolean = false) {
        runner.run(after = { if (reloadAfter && it?.ok == true) reload++ }) { api.adb(request) }
    }

    Page(Modifier.fillMaxSize()) {
        HStack {
            SaltTextField(apkPath, { apkPath = it }, "APK path on this machine", Modifier.weight(1f), onEnter = { act(AndroidActions.installApk(serial, apkPath.trim()), true) })
            SaltButton("Install", { act(AndroidActions.installApk(serial, apkPath.trim()), true) }, enabled = apkPath.isNotBlank() && !runner.running)
        }
        HStack {
            SaltTextField(filter, { filter = it }, "Filter packages", Modifier.weight(1f))
            Check(system, { system = it }, "System apps")
            SaltButton("Reload", { reload++ }, kind = ButtonKind.Secondary)
        }
        val shown = packages.filter { it.contains(filter.trim(), ignoreCase = true) }
        Label("${shown.size} packages", kind = TextStyleKind.Caption)
        LazyColumn(Modifier.weight(1f), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Space.xs)) {
            items(shown, key = { it }) { pkg ->
                HStack(Modifier.fillMaxWidth(), Space.xs) {
                    Label(pkg, Modifier.weight(1f))
                    SaltButton("Launch", { act(AndroidActions.launchApp(serial, pkg)) }, kind = ButtonKind.Secondary)
                    SaltButton("Stop", { act(AndroidActions.forceStop(serial, pkg)) }, kind = ButtonKind.Secondary)
                    ConfirmButton("Clear data", "Confirm clear", { act(AndroidActions.clearData(serial, pkg)) })
                    ConfirmButton("Uninstall", "Confirm remove", { act(AndroidActions.uninstall(serial, pkg), true) })
                }
            }
        }
        Column(Modifier.heightIn(max = 180.dp)) { OutputView(runner) }
    }
}

@Composable
private fun DeepLinkScreen(api: DevToolsApi, serial: String) {
    var uri by remember { mutableStateOf("") }
    var pkg by remember { mutableStateOf("") }
    // ponytail: session-only history; persist under ~/.salt/android/sdata when it should survive reloads.
    val recent = remember { mutableStateListOf<String>() }
    val runner = rememberRunner()

    fun launch() {
        val link = uri.trim()
        if (link.isEmpty() || !runner.run { api.adb(AndroidActions.deepLink(serial, link, pkg)) }) return
        recent.remove(link); recent.add(0, link)
    }

    Page {
        Label("Opens a URI on the device via ACTION_VIEW (deep links, app links, http URLs).", kind = TextStyleKind.Caption)
        SaltTextField(uri, { uri = it }, "URI, e.g. myapp://profile/42", Modifier.fillMaxWidth(), onEnter = ::launch)
        SaltTextField(pkg, { pkg = it }, "Restrict to package (optional)", Modifier.fillMaxWidth(), onEnter = ::launch)
        SaltButton(if (runner.running) "Launching…" else "Launch", ::launch, enabled = uri.isNotBlank() && !runner.running)
        if (recent.isNotEmpty()) {
            Label("Recent", kind = TextStyleKind.Heading)
            recent.take(8).forEach { r -> SaltButton(r, { uri = r }, kind = ButtonKind.Secondary) }
        }
        OutputView(runner)
    }
}

@Composable
private fun ScreenScreen(api: DevToolsApi, serial: String) {
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var live by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }
    var text by remember { mutableStateOf("") }
    val runner = rememberRunner()

    LaunchedEffect(serial, tick, live) {
        runCatching { api.screenshot(serial).decodeToImageBitmap() }
            .onSuccess { image = it; error = null }
            .onFailure { error = it.message ?: "Screenshot failed" }
        if (live) { delay(1000); tick++ }
    }

    HStack(Modifier.fillMaxSize().padding(Space.lg), Space.lg) {
        VStack(Modifier.weight(1f)) {
            HStack {
                SaltButton("Screenshot", { tick++ })
                Check(live, { live = it }, "Live (1s)")
            }
            error?.let { Notice(it) }
            image?.let { Image(it, "Device screen", Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
        }
        VStack(Modifier.width(260.dp)) {
            Label("Controls", kind = TextStyleKind.Heading)
            listOf("Home" to "KEYCODE_HOME", "Back" to "KEYCODE_BACK", "Recents" to "KEYCODE_APP_SWITCH", "Power" to "KEYCODE_POWER").forEach { (label, key) ->
                SaltButton(label, { runner.run { api.adb(AndroidActions.keyEvent(serial, key)) }; tick++ }, Modifier.fillMaxWidth(), ButtonKind.Secondary)
            }
            fun send() { if (text.isNotEmpty()) runner.run { api.adb(AndroidActions.typeText(serial, text)) } }
            SaltTextField(text, { text = it }, "Type text", Modifier.fillMaxWidth(), onEnter = ::send)
            SaltButton("Send", ::send, enabled = text.isNotEmpty())
            OutputView(runner)
        }
    }
}

@Composable
private fun LogcatScreen(api: DevToolsApi, serial: String) {
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var filter by remember { mutableStateOf("") }
    var auto by remember { mutableStateOf(true) }
    var tick by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(serial, tick, auto) {
        runCatching { api.adb(AndroidActions.logcat(serial)) }
            .onSuccess { lines = it.stdout.lines(); error = it.stderr.takeIf { e -> e.isNotBlank() } }
            .onFailure { error = it.message }
        if (auto) { delay(2000); tick++ }
    }
    val shown = lines.filter { it.contains(filter.trim(), ignoreCase = true) }
    LaunchedEffect(shown.size) { if (auto && shown.isNotEmpty()) listState.scrollToItem(shown.lastIndex) }

    Page(Modifier.fillMaxSize()) {
        HStack {
            SaltTextField(filter, { filter = it }, "Filter (tag, package, text)", Modifier.weight(1f))
            Check(auto, { auto = it }, "Auto (2s)")
            SaltButton("Refresh", { tick++ }, kind = ButtonKind.Secondary)
            SaltButton("Clear", { scope.launch { api.adb(AndroidActions.clearLogcat(serial)); tick++ } }, kind = ButtonKind.Secondary)
        }
        error?.let { Notice(it) }
        LazyColumn(Modifier.weight(1f), state = listState) {
            items(shown.size) {
                val line = shown[it]
                val color = when (logLevel(line)) {
                    'E', 'F' -> SaltTheme.toneColor(Tone.Danger)
                    'W' -> SaltTheme.toneColor(Tone.Warning)
                    'V', 'D' -> SaltTheme.toneColor(Tone.Neutral)
                    else -> Color.Unspecified
                }
                Code(line, color = color)
            }
        }
    }
}

@Composable
private fun DeviceToolsScreen(api: DevToolsApi, serial: String) {
    val runner = rememberRunner()
    fun apply(requests: List<CommandRequest>) = runner.run { api.adbAll(requests) }

    Page(Modifier.verticalScroll(rememberScrollState())) {
        Label("Developer settings", kind = TextStyleKind.Heading)
        deviceToggles.forEach { t ->
            HStack(Modifier.fillMaxWidth()) {
                VStack(Modifier.weight(1f), Space.xs) {
                    Label(t.label)
                    if (t.hint.isNotBlank()) Label(t.hint, kind = TextStyleKind.Caption)
                }
                SaltButton("On", { apply(AndroidActions.toggle(serial, t.on)) }, kind = ButtonKind.Secondary, enabled = !runner.running)
                SaltButton("Off", { apply(AndroidActions.toggle(serial, t.off)) }, kind = ButtonKind.Secondary, enabled = !runner.running)
            }
        }
        Label("Font scale", kind = TextStyleKind.Heading)
        Wrap {
            fontScales.forEach { s -> SaltButton("${s}×", { apply(AndroidActions.setFontScale(serial, s)) }, kind = ButtonKind.Secondary, enabled = !runner.running) }
            SaltButton("Reset display size & density", { apply(AndroidActions.resetDisplay(serial)) }, kind = ButtonKind.Secondary, enabled = !runner.running)
        }
        Label("Power", kind = TextStyleKind.Heading)
        Wrap {
            ConfirmButton("Reboot", "Confirm reboot", { runner.run { api.adb(AndroidActions.reboot(serial)) } })
            ConfirmButton("Reboot to recovery", "Confirm", { runner.run { api.adb(AndroidActions.reboot(serial, "recovery")) } })
            ConfirmButton("Reboot to bootloader", "Confirm", { runner.run { api.adb(AndroidActions.reboot(serial, "bootloader")) } })
        }
        OutputView(runner)
    }
}

@Composable
private fun DiagnosticsScreen(api: DevToolsApi, serial: String) {
    val runner = rememberRunner()
    Page(Modifier.fillMaxSize()) {
        Wrap {
            diagnostics.forEach { (label, command) ->
                SaltButton(label, { runner.run { api.adb(AndroidActions.diagnostic(serial, command)) } }, kind = ButtonKind.Secondary, enabled = !runner.running)
            }
        }
        OutputView(runner)
    }
}

@Composable
private fun EmulatorsScreen(api: DevToolsApi) {
    var names by remember { mutableStateOf<List<String>>(emptyList()) }
    var profiles by remember { mutableStateOf<List<String>>(emptyList()) }
    var profile by remember { mutableStateOf("") }
    var reload by remember { mutableStateOf(0) }
    val runner = rememberRunner()

    LaunchedEffect(reload) {
        runCatching { api.androidCli(AndroidActions.listEmulators()) }.onSuccess { names = parseNames(it.stdout) }
    }
    LaunchedEffect(Unit) {
        runCatching { api.androidCli(AndroidActions.listEmulatorProfiles()) }.onSuccess {
            profiles = parseNames(it.stdout)
            profile = profiles.firstOrNull { p -> p == "medium_phone" } ?: profiles.firstOrNull().orEmpty()
        }
    }

    Page(Modifier.verticalScroll(rememberScrollState())) {
        HStack {
            SaltButton("Refresh", { reload++ }, kind = ButtonKind.Secondary)
            if (profiles.isNotEmpty()) {
                Select(profiles, profile, { profile = it })
                SaltButton("Create device", { runner.run(after = { reload++ }) { api.androidCli(AndroidActions.createEmulator(profile)) } }, enabled = !runner.running)
            }
        }
        if (names.isEmpty()) EmptyState("No virtual devices found.")
        names.forEach { name ->
            HStack(Modifier.fillMaxWidth()) {
                Label(name, Modifier.weight(1f))
                SaltButton("Start", { runner.run { api.androidCli(AndroidActions.startEmulator(name)) } }, enabled = !runner.running)
                SaltButton("Stop", { runner.run { api.androidCli(AndroidActions.stopEmulator(name)) } }, kind = ButtonKind.Secondary, enabled = !runner.running)
                ConfirmButton("Delete", "Confirm delete", { runner.run(after = { reload++ }) { api.androidCli(AndroidActions.removeEmulator(name)) } }, enabled = !runner.running)
            }
        }
        if (runner.running) Notice("Working… starting or creating an emulator can take a minute.", Tone.Info)
        OutputView(runner)
    }
}

@Composable
private fun SdkScreen(api: DevToolsApi) {
    var installed by remember { mutableStateOf<List<SdkPackage>>(emptyList()) }
    var results by remember { mutableStateOf<List<SdkPackage>?>(null) }
    var query by remember { mutableStateOf("") }
    var reload by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val runner = rememberRunner()

    LaunchedEffect(reload) {
        runCatching { api.androidCli(AndroidActions.sdkInstalled()) }
            .onSuccess { installed = parseSdkPackages(it.stdout); error = null }
            .onFailure { error = it.message }
    }

    fun search() {
        val q = query.trim()
        if (q.isEmpty() || runner.running) return
        runner.run { api.androidCli(AndroidActions.sdkSearch(q)).also { results = parseSdkPackages(it.stdout) } }
    }
    fun change(request: CommandRequest) { runner.run(after = { reload++ }) { api.androidCli(request) } }

    Page(Modifier.verticalScroll(rememberScrollState())) {
        Label("Find packages to install", kind = TextStyleKind.Heading)
        Label("Use * as a wildcard, e.g. platforms*, system-images/android-35*, build-tools*", kind = TextStyleKind.Caption)
        HStack {
            SaltTextField(query, { query = it }, "Package pattern", Modifier.weight(1f), onEnter = ::search)
            SaltButton("Search", ::search, enabled = query.isNotBlank() && !runner.running)
        }
        results?.let { found ->
            if (found.isEmpty()) EmptyState("No matching packages.")
            val have = installed.map { it.id }.toSet()
            found.forEach { p ->
                HStack(Modifier.fillMaxWidth()) {
                    Label(p.id, Modifier.weight(1f))
                    Label(p.version, kind = TextStyleKind.Caption)
                    if (p.id in have) Badge("installed", Tone.Success)
                    else SaltButton("Install", { change(AndroidActions.sdkInstall(p.id)) }, enabled = !runner.running)
                }
            }
        }
        Label("Installed (${installed.size})", kind = TextStyleKind.Heading)
        error?.let { Notice(it) }
        installed.forEach { p ->
            HStack(Modifier.fillMaxWidth()) {
                VStack(Modifier.weight(1f), Space.xs) {
                    Label(p.id)
                    Label(p.description, kind = TextStyleKind.Caption)
                }
                Label(p.version, kind = TextStyleKind.Caption)
                p.newerVersion?.let { SaltButton("Update to $it", { change(AndroidActions.sdkUpdate(p.id)) }, enabled = !runner.running) }
                ConfirmButton("Remove", "Confirm remove", { change(AndroidActions.sdkRemove(p.id)) }, enabled = !runner.running)
            }
        }
        if (runner.running) Notice("Working… SDK downloads can take several minutes.", Tone.Info)
        OutputView(runner)
    }
}
