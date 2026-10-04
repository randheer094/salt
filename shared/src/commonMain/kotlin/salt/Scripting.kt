package salt

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Runs user scripts (JavaScript with a Postman-style `pm` object). The browser provides the implementation, since
 * the server has no JavaScript engine; the context and the result travel as JSON strings.
 */
fun interface ScriptEngine {
    fun eval(code: String, contextJson: String): String
}

/** Used where no engine exists (tests, non-browser hosts). */
object NoScriptEngine : ScriptEngine {
    override fun eval(code: String, contextJson: String) = """{"error":"This host cannot run scripts."}"""
}

@Serializable
class ScriptContext(val env: Map<String, String>, val request: RequestSpec, val response: ResponseSpec? = null)

@Serializable
class TestResult(val name: String, val ok: Boolean, val error: String? = null)

/** [env] and [request] are the values after the script ran; null means the script reported nothing. */
@Serializable
class ScriptResult(
    val env: Map<String, String>? = null,
    val request: RequestSpec? = null,
    val tests: List<TestResult> = emptyList(),
    val logs: List<String> = emptyList(),
    val error: String? = null,
)

private val scriptJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** Runs [code]; a blank script is a no-op. Never throws: failures come back in [ScriptResult.error]. */
fun ScriptEngine.run(code: String, context: ScriptContext): ScriptResult {
    if (code.isBlank()) return ScriptResult()
    return runCatching { scriptJson.decodeFromString<ScriptResult>(eval(code, scriptJson.encodeToString(context))) }
        .getOrElse { ScriptResult(error = "Script engine failed: ${it.message}") }
}

private val placeholder = Regex("""\{\{\s*([^{}]+?)\s*\}\}""")

/** Replaces {{name}} with its value; unknown names are left as written so the mistake is visible. */
fun String.substitute(vars: Map<String, String>): String = placeholder.replace(this) { vars[it.groupValues[1]] ?: it.value }

fun RequestSpec.substitute(vars: Map<String, String>): RequestSpec = if (vars.isEmpty()) this else copy(
    url = url.substitute(vars),
    headers = headers.map { Header(it.name.substitute(vars), it.value.substitute(vars)) },
    body = body.substitute(vars),
)

/** Names used as {{placeholders}} in [req] that [vars] does not define. */
fun RequestSpec.missingVariables(vars: Map<String, String>): Set<String> =
    (listOf(url, body) + headers.flatMap { listOf(it.name, it.value) })
        .flatMap { placeholder.findAll(it).map { m -> m.groupValues[1] }.toList() }.filter { it !in vars }.toSet()
