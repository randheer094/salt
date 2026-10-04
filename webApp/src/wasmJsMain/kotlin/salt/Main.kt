package salt

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.window
import salt.ui.App

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val services = HttpDevToolsApi(window.location.origin)
    ComposeViewport { App(services) }
}
