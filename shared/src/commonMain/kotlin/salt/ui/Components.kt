package salt.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import salt.CommandResult
import salt.ui.design.Code
import salt.ui.design.Notice
import salt.ui.design.Space
import salt.ui.design.Tone
import salt.ui.design.VStack

/** Runs one command at a time and exposes its progress/result as Compose state. */
class Runner(private val scope: CoroutineScope) {
    var running by mutableStateOf(false); private set
    var result by mutableStateOf<CommandResult?>(null); private set
    var error by mutableStateOf<String?>(null); private set

    /** No-op (returns false) while a command is running. [after] gets the result, or null on failure. */
    fun run(after: (CommandResult?) -> Unit = {}, block: suspend () -> CommandResult): Boolean {
        if (running) return false
        running = true; error = null
        scope.launch {
            val r = runCatching { block() }
                .onSuccess { result = it }
                .onFailure { error = it.message ?: "Request failed" }
                .getOrNull()
            running = false
            after(r)
        }
        return true
    }
}

@Composable
fun rememberRunner(): Runner {
    val scope = rememberCoroutineScope()
    return remember { Runner(scope) }
}

/** Command line, exit code and combined output of the last run. */
@Composable
fun OutputView(runner: Runner) {
    runner.error?.let { Notice(it) }
    runner.result?.let { r ->
        VStack(Modifier.fillMaxWidth(), Space.xs) {
            Code("$ ${r.command}  (exit ${r.exitCode})", color = if (r.ok) androidx.compose.ui.graphics.Color.Unspecified else salt.ui.design.SaltTheme.toneColor(Tone.Danger))
            Code((r.stdout + r.stderr).ifBlank { "(no output)" }, Modifier.fillMaxWidth().padding(top = Space.xs).verticalScroll(rememberScrollState()))
        }
    }
}
