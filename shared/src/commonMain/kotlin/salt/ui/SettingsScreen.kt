package salt.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import salt.SettingType
import salt.SettingsApi
import salt.ToolSection
import salt.ui.design.Check
import salt.ui.design.Label
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.unit.dp
import salt.ui.design.Notice
import salt.ui.design.Page
import salt.ui.design.SaltButton
import salt.ui.design.SaltSwitch
import salt.ui.design.SaltTextField
import salt.ui.design.TextStyleKind
import salt.ui.design.Tone
import salt.validateSettings

/** Renders any section's settings from its schema; no per-section UI code. */
@Composable
fun SettingsScreen(section: ToolSection, api: SettingsApi, resolved: suspend () -> Map<String, String> = { emptyMap() }) {
    var values by remember { mutableStateOf<Map<String, String>?>(null) }
    var status by remember { mutableStateOf<Pair<String, Tone>?>(null) }
    // What each path setting resolves to right now (saved values, then environment / PATH). Reloaded after Save.
    var found by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var version by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(section.id, version) { found = runCatching { resolved() }.getOrDefault(emptyMap()) }
    LaunchedEffect(section.id) {
        runCatching { api.getSettings(section.id) }
            .onSuccess { values = it }
            .onFailure { status = (it.message ?: "Failed to load settings") to Tone.Danger }
    }

    Page(Modifier.verticalScroll(rememberScrollState())) {
        Label("${section.title} settings", kind = TextStyleKind.Heading)
        if (section.settings.isEmpty()) Label("No settings yet.", kind = TextStyleKind.Caption)
        val current = values ?: return@Page
        val errors = validateSettings(section, current)
        section.settings.forEach { def ->
            val v = current[def.key] ?: def.default
            if (def.type == SettingType.BOOL) SaltSwitch(v == "true", { values = current + (def.key to it.toString()) }, def.label, Modifier.widthIn(max = 560.dp), hint = def.description)
            else {
                val path = found[def.key]
                val missing = path != null && path.isBlank()
                val hint = errors[def.key] ?: when {
                    path == null -> def.description
                    missing -> if (v.isBlank()) "Not found. Install it or set a path." else "Not found at this path."
                    else -> "Using $path"
                }
                SaltTextField(
                    v, { values = current + (def.key to it) }, def.label, Modifier.fillMaxWidth().widthIn(max = 560.dp),
                    hint = hint, error = def.key in errors || missing, labelAbove = true,
                )
            }
        }
        if (section.settings.isNotEmpty()) SaltButton("Save", {
            scope.launch {
                runCatching { api.saveSettings(section.id, current) }
                    .onSuccess { values = it; status = "Saved" to Tone.Success; version++ }
                    .onFailure { status = (it.message ?: "Save failed") to Tone.Danger }
            }
        }, enabled = errors.isEmpty())
        status?.let { (text, tone) -> Notice(text, tone) }
    }
}
