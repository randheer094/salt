package salt

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.window
import salt.ui.App

// salt-script.js defines saltRun; the Network composer's pre-request and test scripts go through it.
@JsFun("(code, context) => globalThis.saltRun(code, context)")
private external fun saltRun(code: String, context: String): String

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val services = HttpDevToolsApi(window.location.origin)
    // "/" serves every section; "/android" serves only that section.
    val only = window.location.pathname.trim('/').substringBefore('/').ifEmpty { null }
    ComposeViewport { App(services, only, ScriptEngine { code, context -> saltRun(code, context) }) }
}
