package salt.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import salt.AndroidActions
import salt.Capture
import salt.CaptureRow
import salt.CommandRequest
import salt.DevToolsApi
import salt.GqlOperation
import salt.MockRule
import salt.NetworkApi
import salt.ProxyStatus
import salt.ResponseSpec
import salt.ScriptEngine
import salt.format
import salt.formatGraphQl
import salt.graphQlOperations
import salt.graphQlResults
import salt.prettyJson
import salt.rowTitle
import salt.ruleFromCapture
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
import salt.ui.design.SaltButton
import salt.ui.design.SaltTextField
import salt.ui.design.SaltTheme
import salt.ui.design.Section
import salt.ui.design.Space
import salt.ui.design.TextStyleKind
import salt.ui.design.Tone
import salt.ui.design.VStack

/**
 * Network has two tools: Traffic (the 24 hour log, where any request can be edited and sent again) and Mocks.
 * [go] switches to a tab by index (Traffic 0, Mocks 1) after a rule is handed over.
 */
@Composable
fun networkTabs(services: Services, serial: String?, scripts: ScriptEngine, go: (Int) -> Unit): List<UiTab> {
    val draft = remember { ComposeDraft() }
    var mockDraft by remember { mutableStateOf<MockRule?>(null) }
    return listOf(
        "Traffic" to { TrafficScreen(services, services, serial, scripts, draft, onMock = { mockDraft = ruleFromCapture(it, NEW_RULE); go(1) }) },
        "Mocks" to { MocksScreen(services, mockDraft) { mockDraft = null } },
    )
}

@Composable
private fun TrafficScreen(
    api: NetworkApi, adb: DevToolsApi, serial: String?, scripts: ScriptEngine, draft: ComposeDraft, onMock: (Capture) -> Unit,
) {
    var status by remember { mutableStateOf<ProxyStatus?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // Rule names for the "mocked" notice; read once, rules are edited in the Mocks tab.
    var ruleNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val rows = remember { mutableStateListOf<CaptureRow>() }
    var selectedId by remember { mutableStateOf<Long?>(null) }
    var detail by remember { mutableStateOf<Capture?>(null) }
    // The editor is filled from a capture once, when it is selected; later refreshes must not overwrite edits.
    var editorFor by remember { mutableStateOf<Long?>(null) }
    val reports = remember { mutableStateMapOf<Long, RunReport>() }
    var filter by remember { mutableStateOf("") }
    var gqlOnly by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val runner = rememberRunner()

    LaunchedEffect(Unit) {
        runCatching { api.mockRules() }.onSuccess { rules -> ruleNames = rules.associate { it.id to it.name.ifBlank { it.url } } }
        runCatching { api.proxyStatus() }.onSuccess { status = it }.onFailure { error = it.message }
        while (true) {
            // Re-fetch from the oldest unfinished row so in-flight requests pick up their response.
            val after = minOf(rows.lastOrNull()?.id ?: 0, (rows.firstOrNull { it.pending }?.id ?: Long.MAX_VALUE) - 1)
            runCatching { api.captures(after) }.onSuccess { fresh ->
                if (rows.isEmpty()) rows.addAll(fresh)
                else fresh.forEach { r -> val i = rows.indexOfLast { it.id == r.id }; if (i >= 0) rows[i] = r else rows.add(r) }
            }
            delay(1000)
        }
    }

    val selectedRow = rows.lastOrNull { it.id == selectedId }
    LaunchedEffect(selectedId, selectedRow?.pending) {
        val id = selectedId ?: run { detail = null; return@LaunchedEffect }
        val c = api.capture(id)
        detail = c
        if (c != null && editorFor != id) { draft.loadRequest(c.request); editorFor = id }
    }

    fun newRequest() { selectedId = null; detail = null; editorFor = null; draft.loadRequest(salt.RequestSpec()) }

    fun toggle(on: Boolean) = scope.launch {
        error = null
        runCatching { api.setProxy(on) }.onSuccess { status = it }.onFailure { error = it.message ?: "Could not change proxy" }
    }

    fun device(requests: List<CommandRequest>) = runner.run {
        val results = requests.map { adb.adb(it) }
        results.firstOrNull { !it.ok } ?: results.last()
    }

    val s = status
    Page(Modifier.fillMaxSize()) {
        HStack {
            if (s?.running == true) {
                Badge("proxy on :${s.port}", Tone.Success)
                if (s.decryptsHttps) Badge("HTTPS decrypted", Tone.Info)
                SaltButton("Stop", { toggle(false) }, kind = ButtonKind.Secondary)
            } else {
                Badge("proxy off", Tone.Neutral)
                SaltButton("Start proxy", { toggle(true) })
            }
            SaltButton("New request", ::newRequest, kind = ButtonKind.Secondary)
            ConfirmButton("Clear log", "Confirm clear", {
                scope.launch { runCatching { api.clearCaptures() }; rows.clear(); reports.clear(); selectedId = null; detail = null }
            })
            SaltTextField(filter, { filter = it }, "Filter URL or operation", Modifier.weight(1f))
            Check(gqlOnly, { gqlOnly = it }, "GraphQL only")
        }
        HStack {
            val ready = serial != null && s?.running == true
            SaltButton("Point device at proxy", { device(AndroidActions.setProxy(serial!!, s!!.port)) }, kind = ButtonKind.Secondary, enabled = ready && !runner.running)
            SaltButton("Reset device proxy", { device(AndroidActions.clearProxy(serial!!)) }, kind = ButtonKind.Secondary, enabled = serial != null && !runner.running)
            SaltButton("Push CA to device", { device(listOf(AndroidActions.pushFile(serial!!, s!!.caPath, "/sdcard/Download/salt-ca.crt"))) }, kind = ButtonKind.Secondary, enabled = ready && s?.decryptsHttps == true && !runner.running)
        }
        error?.let { Notice(it) }
        OutputView(runner)
        if (runner.result?.ok == true && runner.result?.command?.contains("salt-ca") == true) {
            Notice("Now on the device: Settings → Security → Install a certificate → CA certificate → salt-ca.crt. Apps targeting Android 7+ only trust it if their debug build allows user CAs.", Tone.Info)
        }

        val shown by remember(filter, gqlOnly) {
            derivedStateOf {
                val q = filter.trim()
                rows.filter { r ->
                    (!gqlOnly || r.operations.isNotEmpty()) &&
                        (r.url.contains(q, ignoreCase = true) || r.operations.any { it.name?.contains(q, ignoreCase = true) == true })
                }.asReversed()
            }
        }
        HStack(Modifier.weight(1f).fillMaxWidth(), Space.lg) {
            Column(Modifier.weight(1f).fillMaxSize()) {
                Label("${shown.size} of ${rows.size} requests from the last 24 hours", kind = TextStyleKind.Caption)
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    if (shown.isEmpty()) item { EmptyState(if (s?.running == true) "Waiting for traffic…" else "Start the proxy, then point a device or app at it.") }
                    items(shown, key = { it.id }) { r -> TrafficRow(r, r.id == selectedId) { selectedId = r.id } }
                }
            }
            Column(Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Space.sm)) {
                val c = detail
                RequestEditor(
                    api, scripts, draft,
                    onSent = { sent, report ->
                        // The resend is now in the log; select it, and keep the editor as typed (placeholders and all).
                        reports[sent.id] = report
                        editorFor = sent.id; selectedId = sent.id; detail = sent
                    },
                    actions = {
                        if (c != null && !c.tunnel) SaltButton("Mock this", { onMock(c) }, kind = ButtonKind.Secondary, enabled = c.response != null)
                    },
                )
                when {
                    selectedId == null -> Label("Edit the request above and press Send, or select a request on the left.", kind = TextStyleKind.Caption)
                    c == null -> Label("Loading…", kind = TextStyleKind.Caption)
                    else -> ExchangeResult(c, ruleNames[c.mocked], reports[c.id])
                }
            }
        }
    }
}

@Composable
private fun TrafficRow(r: CaptureRow, selected: Boolean, onClick: () -> Unit) {
    val accent = SaltTheme.scheme.primary
    val color = if (selected) SaltTheme.toneColor(Tone.Info) else Color.Unspecified
    HStack(
        Modifier.fillMaxWidth().background(if (selected) accent.copy(alpha = 0.10f) else Color.Transparent).clickable(onClick = onClick).padding(vertical = Space.xs, horizontal = Space.xs),
        Space.xs,
    ) {
        Label(r.clock, Modifier.width(64.dp), kind = TextStyleKind.Caption)
        // GraphQL rows lead with the operation, since every call hits the same URL.
        if (r.operations.isEmpty()) Badge(r.method)
        else Badge(if (r.operations.size > 1) "batch ${r.operations.size}" else r.operations[0].type, gqlTone(r.operations[0].type))
        Badge(r.status?.toString() ?: if (r.pending) "…" else "ERR", if (r.pending) Tone.Neutral else statusTone(r.status))
        if (r.mocked != null) Badge("mocked", Tone.Warning)
        if (r.gqlErrors) Badge("errors", Tone.Danger)
        if (r.operations.isEmpty()) Code(r.url, Modifier.weight(1f), singleLine = true, color = color)
        else VStack(Modifier.weight(1f), Space.xs) {
            Label(r.operations.rowTitle(), tone = if (selected) Tone.Info else null)
            Code(r.url, singleLine = true, color = color)
        }
    }
}

/** The outcome of the selected capture: why it looks the way it does, its GraphQL operations, the test results, the response. */
@Composable
private fun ExchangeResult(c: Capture, mockedBy: String?, report: RunReport?) = VStack {
    val operations = remember(c.id) { if (c.tunnel) emptyList() else graphQlOperations(c.request) }
    if (c.tunnel) Notice("Encrypted tunnel: enable “Decrypt HTTPS” in Network settings and restart the proxy to see inside.", Tone.Info)
    if (c.mocked != null) Notice("Answered by the rule “${mockedBy ?: c.mocked}” instead of the real server.", Tone.Warning)
    if (operations.isNotEmpty()) Section("GraphQL", expanded = true) { operations.forEach { GraphQlOperationView(it) } }
    report?.let { ReportView(it) }
    c.response?.let { ResponseView(it, operations.size) } ?: Label("Waiting for response…", kind = TextStyleKind.Caption)
}

@Composable
private fun ReportView(r: RunReport) = VStack {
    r.problem?.let { Notice(it) }
    if (r.missing.isNotEmpty()) Notice("Undefined variables were sent as typed: ${r.missing.joinToString { "{{$it}}" }}", Tone.Warning)
    if (r.tests.isNotEmpty()) {
        val passed = r.tests.count { it.ok }
        HStack {
            Label("Tests", kind = TextStyleKind.Heading)
            Badge("$passed of ${r.tests.size} passed", if (passed == r.tests.size) Tone.Success else Tone.Danger)
        }
        r.tests.forEach { t ->
            HStack(spacing = Space.sm) {
                Badge(if (t.ok) "pass" else "fail", if (t.ok) Tone.Success else Tone.Danger)
                Label(t.name)
                t.error?.let { Label(it, kind = TextStyleKind.Caption, tone = Tone.Danger) }
            }
        }
    }
    if (r.logs.isNotEmpty()) Section("Console (${r.logs.size})") { Code(r.logs.joinToString("\n")) }
}

private fun gqlTone(type: String) = when (type) {
    "mutation" -> Tone.Warning
    "subscription" -> Tone.Neutral
    else -> Tone.Info
}

@Composable
private fun GraphQlOperationView(o: GqlOperation) = VStack {
    HStack {
        Badge(o.type, gqlTone(o.type))
        Label(o.name ?: "anonymous", kind = TextStyleKind.Heading)
        if (o.persisted) Badge("persisted", Tone.Neutral)
    }
    if (o.query != null) Code(remember(o.query) { formatGraphQl(o.query) })
    else Label("Persisted query: the request sends only a hash and the server looks up the text.", kind = TextStyleKind.Caption)
    o.variables?.let { Label("Variables", kind = TextStyleKind.Caption); Code(it) }
}

/** [graphQlCount] > 0 shows each operation's data and errors instead of the raw body. */
@Composable
internal fun ResponseView(r: ResponseSpec, graphQlCount: Int = 0) = VStack {
    HStack {
        Label("Response", kind = TextStyleKind.Heading)
        if (r.status > 0) Badge(r.status.toString(), statusTone(r.status))
        if (r.durationMs > 0) Label("${r.durationMs} ms", kind = TextStyleKind.Caption)
        if (r.body.isNotEmpty()) Label(formatSize(r.body.length), kind = TextStyleKind.Caption)
    }
    r.error?.let { Notice(it) }
    if (r.headers.isNotEmpty()) Section("Headers (${r.headers.size})") { Code(r.headers.format()) }
    val results = if (graphQlCount > 0) remember(r) { graphQlResults(r, graphQlCount) } else emptyList()
    if (results.any { it != null }) results.forEachIndexed { i, g ->
        if (g == null) return@forEachIndexed
        if (graphQlCount > 1) Label("Operation ${i + 1}", kind = TextStyleKind.Caption)
        g.errors.forEach { Notice(it) }
        g.data?.let { Code(it) }
    } else if (r.body.isNotEmpty()) Code(remember(r.body) { prettyJson(r.body) ?: r.body })
}

internal fun formatSize(chars: Int) = if (chars < 1024) "$chars B" else "${chars / 1024} KB"

internal fun statusTone(status: Int?) = when {
    status == null || status == 0 -> Tone.Danger
    status < 300 -> Tone.Success
    status < 400 -> Tone.Info
    status < 500 -> Tone.Warning
    else -> Tone.Danger
}
