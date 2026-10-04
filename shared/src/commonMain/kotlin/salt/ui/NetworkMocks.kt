package salt.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
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
import kotlinx.coroutines.launch
import salt.Header
import salt.MockAction
import salt.MockRule
import salt.NetworkApi
import salt.RequestSpec
import salt.firstMatch
import salt.format
import salt.parseHeaders
import salt.prettyJson
import salt.ui.design.Badge
import salt.ui.design.ButtonKind
import salt.ui.design.ConfirmButton
import salt.ui.design.EmptyState
import salt.ui.design.HStack
import salt.ui.design.Label
import salt.ui.design.Notice
import salt.ui.design.Page
import salt.ui.design.Panel
import salt.ui.design.SaltButton
import salt.ui.design.SaltSwitch
import salt.ui.design.SaltTextField
import salt.ui.design.Select
import salt.ui.design.Space
import salt.ui.design.TextStyleKind
import salt.ui.design.Tone
import salt.ui.design.VStack
import salt.ui.design.Wrap

/** Id of a rule that has not been saved yet; the editor gives it a real one. */
const val NEW_RULE = "new"

private val actionLabels = listOf(
    MockAction.MOCK to "Mock the response",
    MockAction.MAP_REMOTE to "Send to another host",
    MockAction.BLOCK to "Block",
)

private val methods = listOf("ANY", "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")

private fun MockRule.summary(): String {
    val what = listOfNotNull(
        method.takeIf { it != "ANY" }, url.ifBlank { null }, operation.ifBlank { null }?.let { "operation $it" }, bodyContains.ifBlank { null }?.let { "body has “$it”" },
    ).joinToString(" ").ifBlank { "every request" }
    val then = when (action) {
        MockAction.MOCK -> "answers $status" + if (delayMs > 0) " after $delayMs ms" else ""
        MockAction.MAP_REMOTE -> "goes to $target" + if (delayMs > 0) " after $delayMs ms" else ""
        MockAction.BLOCK -> "gets 403"
    }
    return "$what $then"
}

/**
 * Proxy rules: answer a request locally, send it to another host, or block it. [incoming] is a rule handed over
 * from Traffic ("Mock this"); it opens in the editor and is consumed once.
 */
@Composable
fun MocksScreen(api: NetworkApi, incoming: MockRule?, onConsumed: () -> Unit) {
    val rules = remember { mutableStateListOf<MockRule>() }
    var editing by remember { mutableStateOf<MockRule?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var tryUrl by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        runCatching { api.mockRules() }.onSuccess { rules.addAll(it); loaded = true }.onFailure { error = it.message }
    }
    LaunchedEffect(incoming) { if (incoming != null) { editing = incoming; onConsumed() } }

    fun persist(next: List<MockRule>) = scope.launch {
        runCatching { api.saveMockRules(next) }
            .onSuccess { rules.clear(); rules.addAll(next) }
            .onFailure { error = it.message ?: "Could not save rules" }
    }
    fun nextId() = ((rules.maxOfOrNull { it.id.toLongOrNull() ?: 0 } ?: 0) + 1).toString()

    Page(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        HStack {
            Label(
                "Rules apply to proxied traffic from top to bottom; the first enabled match wins. HTTPS needs Decrypt HTTPS.",
                Modifier.weight(1f), kind = TextStyleKind.Caption,
            )
            SaltButton("New rule", { editing = MockRule(NEW_RULE) }, enabled = editing == null)
        }
        error?.let { Notice(it) }

        editing?.let { rule ->
            RuleEditor(rule, onCancel = { editing = null }, onSave = { saved ->
                val withId = if (saved.id == NEW_RULE) saved.copy(id = nextId()) else saved
                persist(if (rules.any { it.id == withId.id }) rules.map { if (it.id == withId.id) withId else it } else rules + withId)
                editing = null
            })
        }

        if (loaded && rules.isEmpty() && editing == null) {
            EmptyState("No rules yet. Press New rule, or open a request in Traffic and choose Mock this.")
        }
        rules.forEachIndexed { i, rule ->
            Panel {
                SaltSwitch(rule.enabled, { on -> persist(rules.map { if (it.id == rule.id) it.copy(enabled = on) else it }) }, rule.name.ifBlank { rule.url.ifBlank { "Untitled rule" } }, hint = rule.summary())
                Wrap {
                    Badge(actionLabels.first { it.first == rule.action }.second, if (rule.action == MockAction.BLOCK) Tone.Danger else Tone.Warning)
                    SaltButton("Edit", { editing = rule }, kind = ButtonKind.Secondary, enabled = editing == null)
                    SaltButton("Up", { persist(rules.toMutableList().apply { add(i - 1, removeAt(i)) }) }, kind = ButtonKind.Secondary, enabled = i > 0)
                    SaltButton("Down", { persist(rules.toMutableList().apply { add(i + 1, removeAt(i)) }) }, kind = ButtonKind.Secondary, enabled = i < rules.lastIndex)
                    SaltButton("Duplicate", { persist(rules.toMutableList().apply { add(i + 1, rule.copy(id = nextId(), name = rule.name + " copy")) }) }, kind = ButtonKind.Secondary)
                    ConfirmButton("Delete", "Confirm delete", { persist(rules.filter { it.id != rule.id }) })
                }
            }
        }

        if (rules.isNotEmpty()) {
            SaltTextField(tryUrl, { tryUrl = it }, "Check a URL: which rule would catch GET <url>?", Modifier.fillMaxWidth(), mono = true)
            if (tryUrl.isNotBlank()) {
                val hit = rules.firstMatch(RequestSpec("GET", tryUrl.trim()))
                Label(
                    if (hit != null) "Caught by “${hit.name.ifBlank { hit.url }}”." else "No rule catches it. GraphQL operation and body rules are not checked here.",
                    kind = TextStyleKind.Caption, tone = if (hit != null) Tone.Warning else null,
                )
            }
        }
    }
}

@Composable
private fun RuleEditor(rule: MockRule, onCancel: () -> Unit, onSave: (MockRule) -> Unit) {
    var name by remember(rule) { mutableStateOf(rule.name) }
    var action by remember(rule) { mutableStateOf(rule.action) }
    var method by remember(rule) { mutableStateOf(rule.method) }
    var url by remember(rule) { mutableStateOf(rule.url) }
    var operation by remember(rule) { mutableStateOf(rule.operation) }
    var bodyContains by remember(rule) { mutableStateOf(rule.bodyContains) }
    var status by remember(rule) { mutableStateOf(rule.status.toString()) }
    var headers by remember(rule) { mutableStateOf(rule.headers.format()) }
    var body by remember(rule) { mutableStateOf(rule.body) }
    var delay by remember(rule) { mutableStateOf(rule.delayMs.toString()) }
    var target by remember(rule) { mutableStateOf(rule.target) }

    val problems = buildList {
        if (url.isBlank() && operation.isBlank() && bodyContains.isBlank()) add("Add a URL, a GraphQL operation or body text. Without one, this rule would catch every request.")
        if (action == MockAction.MOCK && status.toIntOrNull()?.let { it in 100..599 } != true) add("Status must be a number from 100 to 599.")
        if (action == MockAction.MAP_REMOTE && !Regex("^https?://\\S+").matches(target.trim())) add("Send to must be a URL such as https://staging.example.com.")
        if (delay.isNotBlank() && delay.toLongOrNull()?.let { it in 0..60_000 } != true) add("Delay must be 0 to 60000 ms.")
    }

    Panel(title = if (rule.id == NEW_RULE) "New rule" else "Edit rule") {
        SaltTextField(name, { name = it }, "Name", Modifier.fillMaxWidth(), labelAbove = true)
        HStack {
            Label("Action", kind = TextStyleKind.Caption)
            Select(actionLabels.map { it.second }, actionLabels.first { it.first == action }.second, { picked -> action = actionLabels.first { it.second == picked }.first })
        }
        Label("Match", kind = TextStyleKind.Heading)
        HStack {
            Select(methods, method, { method = it })
            SaltTextField(url, { url = it }, "URL, e.g. api.example.com/users/*", Modifier.weight(1f), mono = true)
        }
        Label("* matches any text. Without https:// it matches any scheme, and the query string is ignored unless you include a ?.", kind = TextStyleKind.Caption)
        HStack {
            SaltTextField(operation, { operation = it }, "GraphQL operation name (optional)", Modifier.weight(1f))
            SaltTextField(bodyContains, { bodyContains = it }, "Request body contains (optional)", Modifier.weight(1f))
        }
        when (action) {
            MockAction.MOCK -> {
                Label("Response", kind = TextStyleKind.Heading)
                HStack {
                    SaltTextField(status, { status = it }, "Status", Modifier.width(110.dp), error = status.toIntOrNull()?.let { it in 100..599 } != true, labelAbove = true)
                    SaltTextField(delay, { delay = it }, "Delay (ms)", Modifier.width(130.dp), labelAbove = true)
                }
                SaltTextField(headers, { headers = it }, "Headers (Name: value per line)", Modifier.fillMaxWidth(), singleLine = false, minLines = 2, mono = true, labelAbove = true)
                SaltTextField(body, { body = it }, "Body", Modifier.fillMaxWidth(), singleLine = false, minLines = 8, mono = true, labelAbove = true)
                val formatted = remember(body) { prettyJson(body) }
                if (formatted != null) HStack {
                    Badge("valid JSON", Tone.Success)
                    if (formatted != body) SaltButton("Format", { body = formatted }, kind = ButtonKind.Secondary)
                }
            }
            MockAction.MAP_REMOTE -> {
                Label("Destination", kind = TextStyleKind.Heading)
                SaltTextField(target, { target = it }, "Send to, e.g. https://staging.example.com", Modifier.fillMaxWidth(), mono = true)
                Label("Scheme, host and port are replaced. A path here replaces the original path; the query is kept.", kind = TextStyleKind.Caption)
                SaltTextField(delay, { delay = it }, "Delay (ms)", Modifier.width(130.dp), labelAbove = true)
            }
            MockAction.BLOCK -> Label("Matching requests get 403 Forbidden and never reach the server.", kind = TextStyleKind.Caption)
        }
        problems.forEach { Notice(it, Tone.Warning) }
        HStack {
            SaltButton("Save rule", {
                onSave(
                    rule.copy(
                        name = name.trim(), action = action, method = method, url = url.trim(), operation = operation.trim(), bodyContains = bodyContains,
                        status = status.toIntOrNull() ?: 200, headers = parseHeaders(headers), body = body, delayMs = delay.toLongOrNull() ?: 0, target = target.trim(),
                    ),
                )
            }, enabled = problems.isEmpty())
            SaltButton("Cancel", onCancel, kind = ButtonKind.Secondary)
        }
    }
}
