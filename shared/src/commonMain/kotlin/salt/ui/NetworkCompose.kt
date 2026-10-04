package salt.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import salt.Capture
import salt.Environment
import salt.Environments
import salt.NetworkApi
import salt.NoScriptEngine
import salt.RequestSpec
import salt.ResponseSpec
import salt.SavedRequest
import salt.ScriptContext
import salt.ScriptEngine
import salt.TestResult
import salt.format
import salt.missingVariables
import salt.parseCurl
import salt.parseHeaders
import salt.prettyJson
import salt.run
import salt.substitute
import salt.toCurl
import salt.ui.design.Badge
import salt.ui.design.ButtonKind
import salt.ui.design.ConfirmButton
import salt.ui.design.HStack
import salt.ui.design.Label
import salt.ui.design.Notice
import salt.ui.design.SaltButton
import salt.ui.design.SaltTextField
import salt.ui.design.Section
import salt.ui.design.Select
import salt.ui.design.Space
import salt.ui.design.TextStyleKind
import salt.ui.design.Tone
import salt.ui.design.VStack
import salt.ui.design.Wrap
import salt.ui.design.Code

/** Everything the request editor edits. Held above the tabs, so switching tabs keeps the work. */
class ComposeDraft {
    var request by mutableStateOf(RequestSpec())
    // Headers stay raw text while editing: parsing on every keystroke would drop half-typed lines.
    var headers by mutableStateOf("")
    var pre by mutableStateOf("")
    var tests by mutableStateOf("")

    /** Replaces the request and, for a saved request, its scripts. */
    fun load(spec: RequestSpec, pre: String = "", tests: String = "") {
        loadRequest(spec); this.pre = pre; this.tests = tests
    }

    /** Replaces only the request: the scripts stay, so a series of requests can share them. */
    fun loadRequest(spec: RequestSpec) { request = spec; headers = spec.headers.format() }

    fun current() = request.copy(headers = parseHeaders(headers))
}

/** What happened around one send: script output, undefined variables, test results. */
class RunReport(val tests: List<TestResult>, val logs: List<String>, val problem: String?, val missing: Set<String>)

private const val NO_ENV = "No environment"

private val testSnippets = listOf(
    "Status is 200" to """pm.test("Status is 200", () => pm.response.to.have.status(200));""",
    "Body has a field" to """pm.test("Has an id", () => pm.expect(pm.response.json()).to.have.property("id"));""",
    "Fast response" to """pm.test("Responds in under 500 ms", () => pm.expect(pm.response.responseTime).to.be.below(500));""",
    "Header is present" to """pm.test("Has Content-Type", () => pm.response.to.have.header("Content-Type"));""",
    "Save a value" to """pm.environment.set("token", pm.response.json().token);""",
)

private val preSnippets = listOf(
    "Set a variable" to """pm.environment.set("requestId", Date.now());""",
    "Add a header" to """pm.request.headers.upsert({ key: "Authorization", value: "Bearer " + pm.environment.get("token") });""",
    "Change the URL" to """pm.request.url = pm.request.url + "?t=" + Date.now();""",
    "Edit the JSON body" to "const body = JSON.parse(pm.request.body.raw || \"{}\");\nbody.ts = Date.now();\npm.request.body.raw = JSON.stringify(body);",
)

/** Returns [envs] with the active environment's variables replaced; creates "Default" when none is active yet. */
private fun withVars(envs: Environments, vars: Map<String, String>): Environments = when {
    envs.envs.any { it.name == envs.active } -> envs.copy(envs = envs.envs.map { if (it.name == envs.active) it.copy(vars = vars) else it })
    vars.isEmpty() -> envs
    else -> Environments("Default", envs.envs.filter { it.name != "Default" } + Environment("Default", vars))
}

private fun varsText(vars: Map<String, String>) = vars.entries.joinToString("\n") { "${it.key}: ${it.value}" }

private fun parseVars(text: String): Map<String, String> = parseHeaders(text).associate { it.name to it.value }

/**
 * The editable request of the Traffic screen: method and URL, variables, headers, body, scripts. Send runs the
 * pre-request script, sends through the server (which logs it like proxied traffic), runs the tests, and reports the
 * new capture through [onSent]. [actions] adds screen-specific buttons beside the built-in ones.
 */
@Composable
fun RequestEditor(
    api: NetworkApi,
    scripts: ScriptEngine,
    d: ComposeDraft,
    onSent: (Capture, RunReport) -> Unit,
    actions: @Composable () -> Unit = {},
) {
    var sending by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val saved = remember { mutableStateListOf<SavedRequest>() }
    var envs by remember { mutableStateOf(Environments()) }
    var newEnv by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var curlText by remember { mutableStateOf("") }
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        runCatching { api.savedRequests() }.onSuccess { saved.addAll(it) }.onFailure { error = it.message }
        runCatching { api.environments() }.onSuccess { envs = it }
    }
    LaunchedEffect(copied) { if (copied) { delay(1800); copied = false } }
    // Text of the variables editor; re-read whenever the stored environments change (a save, or a script setting a value).
    var envText by remember(envs) { mutableStateOf(varsText(envs.activeVars)) }

    suspend fun saveEnvs(next: Environments) {
        runCatching { api.saveEnvironments(next) }.onSuccess { envs = next }.onFailure { error = it.message ?: "Could not save environments" }
    }

    fun send() {
        if (sending || d.request.url.isBlank()) return
        sending = true; problem = null
        scope.launch {
            var vars = envs.activeVars
            var req = d.current()
            val logs = mutableListOf<String>()
            val pre = scripts.run(d.pre, ScriptContext(vars, req))
            logs += pre.logs
            if (pre.error != null) {
                problem = "Pre-request script failed: ${pre.error}"
                sending = false
                return@launch
            }
            pre.env?.let { vars = it }
            pre.request?.let { req = it }
            val missing = req.missingVariables(vars)
            val out = req.substitute(vars)
            val sent = runCatching { api.send(out) }.getOrElse { Capture(0, out, ResponseSpec(error = it.message ?: "Request failed")) }
            val post = scripts.run(d.tests, ScriptContext(vars, out, sent.response))
            logs += post.logs
            post.env?.let { vars = it }
            if (vars != envs.activeVars) saveEnvs(withVars(envs, vars))
            onSent(sent, RunReport(post.tests, logs, post.error?.let { "Test script failed: $it" }, missing))
            sending = false
        }
    }

    fun persist(next: List<SavedRequest>) = scope.launch {
        runCatching { api.saveRequests(next) }
            .onSuccess { saved.clear(); saved.addAll(next) }
            .onFailure { error = it.message ?: "Could not save" }
    }

    VStack(Modifier.fillMaxWidth()) {
        HStack {
            Select(listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"), d.request.method, { d.request = d.request.copy(method = it) })
            SaltTextField(
                d.request.url,
                // Pasting a curl command (from dev tools or docs) fills in the whole request.
                { v -> parseCurl(v)?.let { d.loadRequest(it) } ?: run { d.request = d.request.copy(url = v) } },
                "URL, or paste a curl command", Modifier.weight(1f), onEnter = ::send, mono = true,
            )
            SaltButton(if (sending) "Sending…" else "Send", ::send, enabled = !sending && d.request.url.isNotBlank())
        }
        Wrap {
            // The confirmation replaces the label in place at a fixed width, so nothing around it moves.
            SaltButton(if (copied) "Copied" else "Copy as cURL", {
                clipboard.setText(AnnotatedString(d.current().toCurl())); copied = true
            }, Modifier.width(124.dp), ButtonKind.Secondary, enabled = d.request.url.isNotBlank())
            actions()
            Label("Environment", kind = TextStyleKind.Caption)
            Select(listOf(NO_ENV) + envs.envs.map { it.name }, envs.active.ifBlank { NO_ENV }, { n -> scope.launch { saveEnvs(envs.copy(active = if (n == NO_ENV) "" else n)) } })
        }
        problem?.let { Notice(it) }
        if (scripts === NoScriptEngine) Notice("Scripts only run in the browser build of Salt.", Tone.Warning)

        Section("Headers", expanded = true) {
            SaltTextField(d.headers, { d.headers = it }, "Name: value, one per line", Modifier.fillMaxWidth(), singleLine = false, minLines = 3, mono = true)
        }
        Section("Body", expanded = d.request.body.isNotEmpty()) {
            val formatted = remember(d.request.body) { prettyJson(d.request.body) }
            SaltTextField(d.request.body, { d.request = d.request.copy(body = it) }, "Request body", Modifier.fillMaxWidth(), singleLine = false, minLines = 5, mono = true)
            if (formatted != null) HStack {
                Badge("valid JSON", Tone.Success)
                if (formatted != d.request.body) SaltButton("Format", { d.request = d.request.copy(body = formatted) }, kind = ButtonKind.Secondary)
            }
        }
        Section("Pre-request script") {
            Label("Runs before sending. Change the request with pm.request, and variables with pm.environment.", kind = TextStyleKind.Caption)
            SaltTextField(d.pre, { d.pre = it }, "JavaScript", Modifier.fillMaxWidth(), singleLine = false, minLines = 5, mono = true)
            Wrap { preSnippets.forEach { (name, code) -> SaltButton(name, { d.pre = (d.pre.trimEnd() + "\n" + code).trim() }, kind = ButtonKind.Secondary) } }
        }
        Section("Tests") {
            Label("Runs after the response arrives. Use pm.test(name, fn) with pm.expect and pm.response.", kind = TextStyleKind.Caption)
            SaltTextField(d.tests, { d.tests = it }, "JavaScript", Modifier.fillMaxWidth(), singleLine = false, minLines = 5, mono = true)
            Wrap { testSnippets.forEach { (name, code) -> SaltButton(name, { d.tests = (d.tests.trimEnd() + "\n" + code).trim() }, kind = ButtonKind.Secondary) } }
        }
        Section("Environments") {
            Label("Use {{name}} in the URL, headers or body. Scripts read and set them with pm.environment.", kind = TextStyleKind.Caption)
            if (envs.active.isNotBlank()) {
                SaltTextField(envText, { envText = it }, "Variables of ${envs.active} (name: value, one per line)", Modifier.fillMaxWidth(), singleLine = false, minLines = 4, mono = true, labelAbove = true)
                Wrap {
                    SaltButton("Save variables", { scope.launch { saveEnvs(withVars(envs, parseVars(envText))) } }, enabled = parseVars(envText) != envs.activeVars)
                    ConfirmButton("Delete ${envs.active}", "Confirm delete", { scope.launch { saveEnvs(Environments("", envs.envs.filter { it.name != envs.active })) } })
                }
            }
            HStack {
                SaltTextField(newEnv, { newEnv = it }, "New environment name", Modifier.weight(1f))
                SaltButton("Add", {
                    val name = newEnv.trim()
                    scope.launch { saveEnvs(Environments(name, envs.envs.filter { it.name != name } + Environment(name))); newEnv = "" }
                }, kind = ButtonKind.Secondary, enabled = newEnv.isNotBlank())
            }
        }
        Section("Import cURL") {
            Label("Paste a curl command, from browser dev tools or API docs, to replace the request above.", kind = TextStyleKind.Caption)
            SaltTextField(curlText, { curlText = it }, "curl https://…", Modifier.fillMaxWidth(), singleLine = false, minLines = 4, mono = true)
            val parsed = remember(curlText) { parseCurl(curlText) }
            if (curlText.isNotBlank() && parsed == null) Notice("That does not look like a curl command.", Tone.Warning)
            SaltButton("Import", { parsed?.let { d.loadRequest(it); curlText = "" } }, enabled = parsed != null)
        }
        Section("Saved requests (${saved.size})") {
            SaltButton("Save current request", {
                val req = d.current()
                val id = ((saved.maxOfOrNull { it.id.toLongOrNull() ?: 0 } ?: 0) + 1).toString()
                persist(saved + SavedRequest(id, "${req.method} ${req.url}", req, d.pre, d.tests))
            }, kind = ButtonKind.Secondary, enabled = d.request.url.isNotBlank())
            error?.let { Notice(it) }
            saved.forEach { s ->
                HStack(Modifier.fillMaxWidth()) {
                    Code(s.name, Modifier.weight(1f), singleLine = true)
                    if (s.pre.isNotBlank() || s.tests.isNotBlank()) Badge("scripts", Tone.Info)
                    SaltButton("Load", { d.load(s.request, s.pre, s.tests) }, kind = ButtonKind.Secondary)
                    ConfirmButton("Delete", "Confirm", { persist(saved.filter { it.id != s.id }) })
                }
            }
        }
    }
}
