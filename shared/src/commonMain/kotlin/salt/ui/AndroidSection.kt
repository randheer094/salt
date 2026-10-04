package salt.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import salt.Edges
import salt.Spacing
import salt.density
import salt.formatLength
import salt.issues
import salt.parseDensity
import salt.spacing
import salt.ui.design.Wrap
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.Alignment
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import salt.UiNode
import salt.hitTest
import salt.parseToggleStates
import salt.parseUiDump
import salt.readToggles
import salt.uiDump
import salt.ui.design.Divider
import salt.ui.design.SaltSwitch
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
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
import salt.deviceSummary
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
import salt.ui.design.Section
import salt.ui.design.Select
import salt.ui.design.VStack
import salt.ui.design.Wrap

fun androidTabs(api: DevToolsApi, device: Device?, onSelect: (Device) -> Unit): List<UiTab> {
    val serial = device?.serial
    return listOf(
        "Devices" to { DevicesScreen(api, device, onSelect) },
        "Apps" to { NeedsDevice(serial) { AppsScreen(api, it) } },
        "Screen" to { NeedsDevice(serial) { ScreenScreen(api, it) } },
        "Logcat" to { NeedsDevice(serial) { LogcatScreen(api, it) } },
        "Controls" to { NeedsDevice(serial) { DeviceToolsScreen(api, it) } },
        "SDK" to { SdkScreen(api) },
    )
}

/** How the Android tools are grouped in the side list; titles match [androidTabs]. */
val androidNav: List<Pair<String?, List<String>>> = listOf(
    "Connect" to listOf("Devices"),
    "On the device" to listOf("Apps", "Screen", "Logcat", "Controls"),
    "This computer" to listOf("SDK"),
)

/** Runs [requests] in order through adb; returns the first failure, else the last result. */
private suspend fun DevToolsApi.adbAll(requests: List<CommandRequest>): CommandResult {
    val results = requests.map { adb(it) }
    return results.firstOrNull { !it.ok } ?: results.last()
}

/** Gate for tabs that act on one device. */
@Composable
fun NeedsDevice(serial: String?, content: @Composable (String) -> Unit) {
    if (serial == null) EmptyState("Select a device under Devices first.") else content(serial)
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
        // Device Manager style: name, one summary line, state; the picked device shows its Android/API/ABI inline.
        devices.forEach { d ->
            val picked = d.serial == selected?.serial
            Panel {
                HStack {
                    VStack(Modifier.weight(1f), Space.xs) {
                        Label(d.model ?: d.serial, kind = TextStyleKind.Heading)
                        Label(
                            listOfNotNull(d.serial.takeIf { d.model != null || !d.isEmulator }, "Emulator".takeIf { d.isEmulator }, deviceSummary(props).takeIf { picked && it.isNotEmpty() }).joinToString(" · "),
                            kind = TextStyleKind.Caption,
                        )
                    }
                    Badge(if (d.isOnline) "Online" else d.state, if (d.isOnline) Tone.Success else Tone.Warning)
                    SaltButton(if (picked) "Selected" else "Select", { onSelect(d) }, kind = if (picked) ButtonKind.Secondary else ButtonKind.Primary, enabled = d.isOnline && !picked)
                }
            }
        }
        Label("Wireless debugging", kind = TextStyleKind.Heading)
        Label("Pair once with the code from the phone, then connect.", kind = TextStyleKind.Caption)
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
        Section("Virtual devices") { EmulatorsBlock(api) }
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
        Section("Open a link") { DeepLinkBlock(api, serial) }
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
private fun DeepLinkBlock(api: DevToolsApi, serial: String) {
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

    VStack {
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
    var inspect by remember { mutableStateOf(false) }
    var interact by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var nodes by remember { mutableStateOf<List<UiNode>>(emptyList()) }
    var picked by remember { mutableStateOf<Int?>(null) }
    var density by remember(serial) { mutableStateOf<Int?>(null) }
    var asDp by remember { mutableStateOf(true) }
    var showBounds by remember { mutableStateOf(false) }
    // Spacing is derived from the selection; null density (or the px option) shows raw pixels.
    val spacing = remember(nodes, picked) { picked?.takeIf { it < nodes.size }?.let { nodes.spacing(it) } }
    val unit = if (asDp) density else null
    val runner = rememberRunner()
    val lastPng = remember { arrayOfNulls<ByteArray>(1) }

    LaunchedEffect(serial, inspect) {
        if (inspect && density == null) runCatching { api.adb(density(serial)) }.onSuccess { density = parseDensity(it.stdout) }
    }

    LaunchedEffect(serial, tick, live, inspect) {
        // Decode only when the frame changed: a still screen redraws nothing, so live view doesn't flicker.
        runCatching { api.screenshot(serial) }
            .onSuccess { png ->
                if (lastPng[0]?.contentEquals(png) != true) { image = png.decodeToImageBitmap(); lastPng[0] = png }
                error = null
            }
            .onFailure { error = it.message ?: "Screenshot failed" }
        if (inspect && !live) {
            val dump = runCatching { api.adb(uiDump(serial)) }.getOrNull()
            val parsed = dump?.stdout?.let(::parseUiDump).orEmpty()
            if (parsed.isEmpty()) error = dump?.stderr?.ifBlank { null } ?: "Could not read the layout. Is the screen locked?"
            else { nodes = parsed; picked = picked?.takeIf { it < parsed.size } }
        }
        if (live) { delay(1000); tick++ }
    }

    HStack(Modifier.fillMaxSize().padding(Space.lg), Space.lg) {
        // Full height from the first frame, so the toolbar doesn't jump when the screenshot arrives.
        VStack(Modifier.weight(1f).fillMaxHeight()) {
            Wrap {
                SaltButton("Screenshot", { tick++ }, kind = ButtonKind.Secondary)
                SaltButton("Save", { runner.run { api.saveScreenshot(serial).let { CommandResult("Saved screenshot", 0, it, "") } } })
                Check(live, { live = it; if (it) inspect = false }, "Live (1s)")
                Check(interact, { interact = it; if (it) inspect = false }, "Interact")
                Check(inspect, { inspect = it; if (it) { live = false; interact = false } else { nodes = emptyList(); picked = null } }, "Inspect layout")
                SaltButton("Open scrcpy", { runner.run { CommandResult("scrcpy", 0, api.openScrcpy(serial), "") } }, kind = ButtonKind.Secondary)
                if (inspect) {
                    Check(showBounds, { showBounds = it }, "Show bounds")
                    Check(asDp, { asDp = it }, if (density != null) "dp (${density}dpi)" else "dp")
                }
            }
            error?.let { Notice(it) }
            image?.let {
                ScreenImage(it, if (inspect) nodes else emptyList(), picked, if (inspect) { i -> picked = i } else null, showBounds && inspect, spacing, unit,
                    if (interact) DeviceControl(
                        { x, y -> scope.launch { api.adb(AndroidActions.tap(serial, x, y)); delay(300); tick++ } },
                        { x1, y1, x2, y2, ms -> scope.launch { api.adb(AndroidActions.swipe(serial, x1, y1, x2, y2, ms)); delay(300); tick++ } },
                    ) else null,
                )
            }
        }
        if (inspect) InspectorPane(nodes, picked, { picked = it }, spacing, unit, Modifier.align(Alignment.Top).width(400.dp).fillMaxHeight())
        else VStack(Modifier.align(Alignment.Top).width(260.dp)) {
            Label("Navigation", kind = TextStyleKind.Heading)
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

/** What dragging and clicking on the picture does to the device, in device pixels. */
private class DeviceControl(val tap: (Int, Int) -> Unit, val swipe: (Int, Int, Int, Int, Int) -> Unit)

/** The device screen, scaled to fit. With [onPick], a click selects the view under it and the selection is outlined. */
@Composable
private fun ScreenImage(
    img: ImageBitmap, nodes: List<UiNode>, picked: Int?, onPick: ((Int?) -> Unit)?,
    showBounds: Boolean = false, spacing: Spacing? = null, density: Int? = null, control: DeviceControl? = null,
) {
    var box by remember { mutableStateOf(IntSize.Zero) }
    val scale = if (box.width == 0 || box.height == 0) 1f else minOf(box.width.toFloat() / img.width, box.height.toFloat() / img.height)
    val ox = (box.width - img.width * scale) / 2f
    val oy = (box.height - img.height * scale) / 2f
    val accent = SaltTheme.scheme.primary
    val measurer = rememberTextMeasurer()
    Box(
        Modifier.fillMaxSize().onSizeChanged { box = it }.pointerInput(nodes, scale, ox, oy, onPick != null) {
            if (onPick != null) detectTapGestures { p -> onPick(nodes.hitTest(((p.x - ox) / scale).toInt(), ((p.y - oy) / scale).toInt())) }
        }.pointerInput(control != null, scale, ox, oy, img) {
            if (control == null) return@pointerInput
            // Screen position -> device pixel; null when the pointer is outside the picture.
            fun device(p: Offset): Pair<Int, Int>? {
                val x = ((p.x - ox) / scale).toInt(); val y = ((p.y - oy) / scale).toInt()
                return if (x in 0 until img.width && y in 0 until img.height) x to y else null
            }
            detectTapGestures(
                onTap = { p -> device(p)?.let { control.tap(it.first, it.second) } },
                onLongPress = { p -> device(p)?.let { control.swipe(it.first, it.second, it.first, it.second, 800) } },
            )
        }.pointerInput(control != null, scale, ox, oy, img) {
            if (control == null) return@pointerInput
            var from: Offset? = null
            var to: Offset? = null
            detectDragGestures(
                onDragStart = { from = it; to = it },
                onDrag = { change, _ -> to = change.position },
                onDragEnd = {
                    val a = from; val b = to
                    if (a != null && b != null) {
                        val x1 = ((a.x - ox) / scale).toInt().coerceIn(0, img.width - 1); val y1 = ((a.y - oy) / scale).toInt().coerceIn(0, img.height - 1)
                        val x2 = ((b.x - ox) / scale).toInt().coerceIn(0, img.width - 1); val y2 = ((b.y - oy) / scale).toInt().coerceIn(0, img.height - 1)
                        control.swipe(x1, y1, x2, y2, 250)
                    }
                },
            )
        },
    ) {
        Image(img, "Device screen", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        val selected = picked?.let { nodes.getOrNull(it) }
        if (showBounds || selected != null) Canvas(Modifier.fillMaxSize()) {
            fun topLeft(n: UiNode) = Offset(ox + n.left * scale, oy + n.top * scale)
            fun sizeOf(n: UiNode) = Size(n.width * scale, n.height * scale)
            if (showBounds) nodes.forEach { drawRect(accent.copy(alpha = 0.35f), topLeft(it), sizeOf(it), style = Stroke(width = 1f)) }
            if (selected == null) return@Canvas
            // The parent is dashed: the distance lines below are measured against it.
            spacing?.parent?.let { nodes.getOrNull(it) }?.let { p ->
                drawRect(MeasureParent, topLeft(p), sizeOf(p), style = Stroke(1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
            }
            drawRect(accent.copy(alpha = 0.22f), topLeft(selected), sizeOf(selected))
            drawRect(accent, topLeft(selected), sizeOf(selected), style = Stroke(width = 2f))

            // One red line per side with its length in dp, to the nearest sibling or else to the parent edge.
            fun measure(from: Offset, to: Offset, px: Int?) {
                if (px == null || px <= 0) return
                drawLine(MeasureLine, from, to, strokeWidth = 2f)
                val layout = measurer.measure(formatLength(px, density), TextStyle(fontSize = 10.sp))
                val at = Offset((from.x + to.x) / 2f - layout.size.width / 2f, (from.y + to.y) / 2f - layout.size.height / 2f)
                drawRect(MeasureLine, at, Size(layout.size.width.toFloat(), layout.size.height.toFloat()))
                drawText(layout, Color.White, at)
            }
            val s = spacing ?: return@Canvas
            val cx = ox + (selected.left + selected.right) / 2f * scale
            val cy = oy + (selected.top + selected.bottom) / 2f * scale
            val left = s.gap.left ?: s.inset?.left
            val right = s.gap.right ?: s.inset?.right
            val top = s.gap.top ?: s.inset?.top
            val bottom = s.gap.bottom ?: s.inset?.bottom
            measure(Offset(ox + (selected.left - (left ?: 0)) * scale, cy), Offset(ox + selected.left * scale, cy), left)
            measure(Offset(ox + selected.right * scale, cy), Offset(ox + (selected.right + (right ?: 0)) * scale, cy), right)
            measure(Offset(cx, oy + (selected.top - (top ?: 0)) * scale), Offset(cx, oy + selected.top * scale), top)
            measure(Offset(cx, oy + selected.bottom * scale), Offset(cx, oy + (selected.bottom + (bottom ?: 0)) * scale), bottom)
        }
    }
}

private val MeasureLine = Color(0xFFE5484D)
private val MeasureParent = Color(0xFFF5A524)

/** View tree (indented by depth) over the details of the selected view, like a layout inspector. */
@Composable
private fun InspectorPane(
    nodes: List<UiNode>, picked: Int?, onPick: (Int) -> Unit, spacing: Spacing?, density: Int?, modifier: Modifier,
) = VStack(modifier) {
    if (nodes.isEmpty()) { EmptyState("Reading the layout…"); return@VStack }
    var filter by remember { mutableStateOf("") }
    SaltTextField(filter, { filter = it }, "Filter ${nodes.size} views by class, id or text", Modifier.fillMaxWidth())
    val visible = remember(nodes, filter) {
        val q = filter.trim()
        if (q.isEmpty()) nodes.indices.toList() else nodes.indices.filter { nodes[it].label.contains(q, true) || nodes[it].resourceId.contains(q, true) }
    }
    val listState = rememberLazyListState()
    // Bring the selection into view only when a click on the screen picked something off-screen in the list.
    LaunchedEffect(picked) {
        val row = visible.indexOf(picked)
        if (row >= 0 && listState.layoutInfo.visibleItemsInfo.none { it.index == row }) listState.animateScrollToItem(maxOf(0, row - 4))
    }
    val accent = SaltTheme.scheme.primary
    LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
        if (visible.isEmpty()) item { EmptyState("No view matches.") }
        items(visible, key = { it }) { i ->
            val n = nodes[i]
            Code(
                n.label,
                Modifier.fillMaxWidth().background(if (i == picked) accent.copy(alpha = 0.16f) else Color.Transparent).clickable { onPick(i) }
                    .padding(start = Space.xs + (n.depth.coerceAtMost(14) * 10).dp, top = 3.dp, bottom = 3.dp),
                singleLine = true,
            )
        }
    }
    val n = picked?.let { nodes.getOrNull(it) }
    if (n == null) Label("Click the screen or a row to measure a view.", kind = TextStyleKind.Caption)
    else {
        Divider()
        VStack(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), Space.xs) {
            Label(n.shortClass, kind = TextStyleKind.Heading)
            n.issues(density).forEach { Notice(it, Tone.Warning) }
            fun sides(e: Edges?) = if (e == null) "none" else listOf("L" to e.left, "T" to e.top, "R" to e.right, "B" to e.bottom)
                .joinToString("   ") { (side, px) -> "$side ${px?.let { formatLength(it, density) } ?: "–"}" }
            val rows = listOf(
                "size" to "${formatLength(n.width, density)} × ${formatLength(n.height, density)}",
                "position" to "x ${formatLength(n.left, density)}, y ${formatLength(n.top, density)}",
                "inside parent" to sides(spacing?.inset),
                "next to siblings" to sides(spacing?.gap),
                "bounds (px)" to "[${n.left},${n.top}][${n.right},${n.bottom}]  ${n.width} × ${n.height}",
            ) + n.attrs.filter { (k, v) -> v.isNotEmpty() && v != "false" && k != "bounds" && k != "index" }.toList()
            rows.forEach { (k, v) ->
                HStack(Modifier.fillMaxWidth(), Space.sm) {
                    Label(k, Modifier.width(110.dp), kind = TextStyleKind.Caption)
                    Code(v, Modifier.weight(1f))
                }
            }
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
    val lastText = remember { arrayOf("") }
    var follow by remember { mutableStateOf(true) }

    // Replace the list only when the log actually changed, so an idle device causes no redraw at all.
    LaunchedEffect(serial, tick, auto) {
        runCatching { api.adb(AndroidActions.logcat(serial)) }
            .onSuccess {
                if (it.stdout != lastText[0]) { lastText[0] = it.stdout; lines = it.stdout.lines() }
                error = it.stderr.takeIf { e -> e.isNotBlank() }
            }
            .onFailure { error = it.message }
        if (auto) { delay(2000); tick++ }
    }
    val shown = remember(lines, filter) { lines.filter { it.contains(filter.trim(), ignoreCase = true) } }
    // Follow the tail only while the reader is at the bottom; scrolling up pauses it instead of yanking them back.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }.collect { (scrolling, more) -> if (scrolling) follow = !more }
    }
    LaunchedEffect(shown) { if (auto && follow && shown.isNotEmpty()) listState.scrollToItem(shown.lastIndex) }

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
    // Current switch states, read in one adb call. Unknown (null) rows keep their place, so nothing shifts when they arrive.
    val states = remember(serial) { mutableStateMapOf<Int, Boolean>() }
    suspend fun refreshStates() {
        runCatching { api.adb(readToggles(serial)) }.onSuccess { states.putAll(parseToggleStates(it.stdout)) }
    }
    LaunchedEffect(serial) { refreshStates() }
    val scope = rememberCoroutineScope()

    Page(Modifier.verticalScroll(rememberScrollState())) {
        Label("Developer settings", kind = TextStyleKind.Heading)
        deviceToggles.forEachIndexed { i, t ->
            SaltSwitch(states[i], { on ->
                states[i] = on
                scope.launch { api.adbAll(AndroidActions.toggle(serial, if (on) t.on else t.off)); refreshStates() }
            }, t.label, Modifier.widthIn(max = 560.dp), hint = t.hint)
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
        Section("Diagnostics") { DiagnosticsBlock(api, serial) }
    }
}

@Composable
private fun DiagnosticsBlock(api: DevToolsApi, serial: String) {
    val runner = rememberRunner()
    VStack {
        Wrap {
            diagnostics.forEach { (label, command) ->
                SaltButton(label, { runner.run { api.adb(AndroidActions.diagnostic(serial, command)) } }, kind = ButtonKind.Secondary, enabled = !runner.running)
            }
        }
        OutputView(runner)
    }
}

@Composable
private fun EmulatorsBlock(api: DevToolsApi) {
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

    VStack {
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
