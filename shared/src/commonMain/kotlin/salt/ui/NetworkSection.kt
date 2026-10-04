package salt.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import salt.AndroidActions
import salt.Capture
import salt.CommandRequest
import salt.CommandResult
import salt.Header
import salt.NetworkApi
import salt.DevToolsApi
import salt.ProxyStatus
import salt.RequestSpec
import salt.ResponseSpec
import salt.SavedRequest
import salt.format
import salt.parseHeaders
import salt.ui.design.Badge
import salt.ui.design.ButtonKind
import salt.ui.design.Code
import salt.ui.design.ConfirmButton
import salt.ui.design.EmptyState
import salt.ui.design.HStack
import salt.ui.design.Label
import salt.ui.design.Notice
import salt.ui.design.Page
import salt.ui.design.SaltButton
import salt.ui.design.SaltTextField
import salt.ui.design.Select
import salt.ui.design.Space
import salt.ui.design.TextStyleKind
import salt.ui.design.Tone
import salt.ui.design.VStack

/** [openCompose] switches to the Compose tab after a request is loaded into the draft. */
@Composable
fun networkTabs(services: Services, serial: String?, openCompose: () -> Unit): List<UiTab> {
    var draft by remember { mutableStateOf(RequestSpec(url = "https://")) }
    // Headers stay raw text while editing: parsing on every keystroke would drop half-typed lines.
    var headers by remember { mutableStateOf("") }
    fun load(spec: RequestSpec) { draft = spec; headers = spec.headers.format() }
    return listOf(
        "Traffic" to { TrafficScreen(services, services, serial) { load(it); openCompose() } },
        "Compose" to { ComposeScreen(services, draft, { draft = it }, headers, { headers = it }, ::load) },
    )
}

private fun statusTone(status: Int?) = when {
    status == null || status == 0 -> Tone.Danger
    status < 300 -> Tone.Success
    status < 400 -> Tone.Info
    status < 500 -> Tone.Warning
    else -> Tone.Danger
}

@Composable
private fun TrafficScreen(api: NetworkApi, adb: DevToolsApi, serial: String?, onEdit: (RequestSpec) -> Unit) {
    var status by remember { mutableStateOf<ProxyStatus?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val captures = remember { mutableStateListOf<Capture>() }
    var selectedId by remember { mutableStateOf<Long?>(null) }
    var filter by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val runner = rememberRunner()

    LaunchedEffect(Unit) {
        runCatching { api.proxyStatus() }.onSuccess { status = it }.onFailure { error = it.message }
        while (true) {
            // Re-fetch from the oldest unfinished capture so in-flight requests pick up their response.
            val after = minOf(captures.lastOrNull()?.id ?: 0, (captures.firstOrNull { it.response == null }?.id ?: Long.MAX_VALUE) - 1)
            runCatching { api.captures(after) }.onSuccess { fresh ->
                fresh.forEach { c -> val i = captures.indexOfFirst { it.id == c.id }; if (i >= 0) captures[i] = c else captures.add(c) }
            }
            delay(1000)
        }
    }

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
            SaltButton("Clear", { scope.launch { runCatching { api.clearCaptures() }; captures.clear(); selectedId = null } }, kind = ButtonKind.Secondary)
            SaltTextField(filter, { filter = it }, "Filter URL", Modifier.weight(1f))
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

        val shown = captures.filter { it.request.url.contains(filter.trim(), ignoreCase = true) }.asReversed()
        HStack(Modifier.weight(1f).fillMaxWidth(), Space.lg) {
            LazyColumn(Modifier.weight(1f).fillMaxSize()) {
                if (shown.isEmpty()) item { EmptyState(if (s?.running == true) "Waiting for traffic…" else "Start the proxy, then point a device or app at it.") }
                items(shown, key = { it.id }) { c ->
                    HStack(Modifier.fillMaxWidth().clickable { selectedId = c.id }.padding(vertical = Space.xs), Space.xs) {
                        Badge(c.request.method)
                        Badge(c.response?.status?.takeIf { it > 0 }?.toString() ?: if (c.response == null) "…" else "ERR", if (c.response == null) Tone.Neutral else statusTone(c.response?.status))
                        Code(c.request.url, Modifier.weight(1f), singleLine = true, color = if (c.id == selectedId) salt.ui.design.SaltTheme.toneColor(Tone.Info) else androidx.compose.ui.graphics.Color.Unspecified)
                    }
                }
            }
            val sel = captures.firstOrNull { it.id == selectedId }
            if (sel == null) EmptyState("Select a request.", Modifier.weight(1f))
            else Column(Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState())) { ExchangeView(sel, onEdit) }
        }
    }
}

@Composable
private fun ExchangeView(c: Capture, onEdit: (RequestSpec) -> Unit) = VStack {
    HStack {
        Label("#${c.id} ${c.request.method}", kind = TextStyleKind.Heading)
        if (!c.tunnel) SaltButton("Edit & resend", { onEdit(c.request) }, kind = ButtonKind.Secondary)
    }
    Code(c.request.url)
    if (c.tunnel) Notice("Encrypted tunnel: enable “Decrypt HTTPS” in Network settings and restart the proxy to see inside.", Tone.Info)
    Label("Request", kind = TextStyleKind.Heading)
    Code(c.request.headers.format().ifBlank { "(no headers)" })
    if (c.request.body.isNotEmpty()) Code(c.request.body)
    c.response?.let { ResponseView(it) } ?: Label("Waiting for response…", kind = TextStyleKind.Caption)
}

@Composable
private fun ResponseView(r: ResponseSpec) = VStack {
    HStack {
        Label("Response", kind = TextStyleKind.Heading)
        if (r.status > 0) Badge(r.status.toString(), statusTone(r.status))
        if (r.durationMs > 0) Label("${r.durationMs} ms", kind = TextStyleKind.Caption)
    }
    r.error?.let { Notice(it) }
    if (r.headers.isNotEmpty()) Code(r.headers.format())
    if (r.body.isNotEmpty()) Code(r.body)
}

@Composable
private fun ComposeScreen(
    api: NetworkApi,
    draft: RequestSpec,
    onDraft: (RequestSpec) -> Unit,
    headers: String,
    onHeaders: (String) -> Unit,
    onLoad: (RequestSpec) -> Unit,
) {
    var response by remember { mutableStateOf<ResponseSpec?>(null) }
    var sending by remember { mutableStateOf(false) }
    val saved = remember { mutableStateListOf<SavedRequest>() }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { runCatching { api.savedRequests() }.onSuccess { saved.addAll(it) }.onFailure { error = it.message } }

    fun current() = draft.copy(headers = parseHeaders(headers))

    fun send() {
        if (sending || draft.url.isBlank()) return
        sending = true; response = null
        scope.launch {
            response = runCatching { api.send(current()) }.getOrElse { ResponseSpec(error = it.message ?: "Request failed") }
            sending = false
        }
    }

    fun persist(next: List<SavedRequest>) = scope.launch {
        runCatching { api.saveRequests(next) }
            .onSuccess { saved.clear(); saved.addAll(next) }
            .onFailure { error = it.message ?: "Could not save" }
    }

    Page(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        HStack {
            Select(listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"), draft.method, { onDraft(draft.copy(method = it)) })
            SaltTextField(draft.url, { onDraft(draft.copy(url = it)) }, "URL", Modifier.weight(1f), onEnter = ::send, mono = true)
            SaltButton(if (sending) "Sending…" else "Send", ::send, enabled = !sending && draft.url.isNotBlank())
        }
        SaltTextField(headers, onHeaders, "Headers (Name: value per line)", Modifier.fillMaxWidth(), singleLine = false, minLines = 3, mono = true)
        SaltTextField(draft.body, { onDraft(draft.copy(body = it)) }, "Body", Modifier.fillMaxWidth(), singleLine = false, minLines = 4, mono = true)
        HStack {
            SaltButton("Save request", {
                val req = current()
                val id = ((saved.maxOfOrNull { it.id.toLongOrNull() ?: 0 } ?: 0) + 1).toString()
                persist(saved + SavedRequest(id, "${req.method} ${req.url}", req))
            }, kind = ButtonKind.Secondary, enabled = draft.url.isNotBlank())
        }
        error?.let { Notice(it) }
        response?.let { ResponseView(it) }
        if (saved.isNotEmpty()) {
            Label("Saved", kind = TextStyleKind.Heading)
            saved.forEach { s ->
                HStack(Modifier.fillMaxWidth()) {
                    Code(s.name, Modifier.weight(1f), singleLine = true)
                    SaltButton("Load", { onLoad(s.request) }, kind = ButtonKind.Secondary)
                    ConfirmButton("Delete", "Confirm", { persist(saved.filter { it.id != s.id }) })
                }
            }
        }
    }
}
