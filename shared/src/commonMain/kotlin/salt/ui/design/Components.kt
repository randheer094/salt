package salt.ui.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Switch
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor

// ── Layout ────────────────────────────────────────────────────────────────

@Composable
fun VStack(modifier: Modifier = Modifier, spacing: androidx.compose.ui.unit.Dp = Space.sm, content: @Composable ColumnScope.() -> Unit) =
    Column(modifier, verticalArrangement = Arrangement.spacedBy(spacing), content = content)

@Composable
fun HStack(modifier: Modifier = Modifier, spacing: androidx.compose.ui.unit.Dp = Space.sm, content: @Composable RowScope.() -> Unit) =
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(spacing), verticalAlignment = Alignment.CenterVertically, content = content)

/** Row that wraps onto more lines when it runs out of width. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Wrap(modifier: Modifier = Modifier, spacing: androidx.compose.ui.unit.Dp = Space.sm, content: @Composable () -> Unit) =
    FlowRow(modifier, Arrangement.spacedBy(spacing), Arrangement.spacedBy(spacing)) { content() }

/** Standard padded page body. */
@Composable
fun Page(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    VStack(modifier.padding(Space.lg), content = content)

// ── Text ──────────────────────────────────────────────────────────────────

enum class TextStyleKind { Title, Heading, Body, Caption }

@Composable
fun Label(text: String, modifier: Modifier = Modifier, kind: TextStyleKind = TextStyleKind.Body, tone: Tone? = null) {
    val t = MaterialTheme.typography
    Text(
        text, modifier,
        style = when (kind) {
            TextStyleKind.Title -> t.titleLarge
            TextStyleKind.Heading -> t.titleSmall
            TextStyleKind.Body -> t.bodyMedium
            TextStyleKind.Caption -> t.bodySmall
        },
        color = tone?.let { SaltTheme.toneColor(it) } ?: if (kind == TextStyleKind.Caption) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
    )
}

/** Selectable monospace text, for logs, headers and bodies. */
@Composable
fun Code(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified, singleLine: Boolean = false) = SelectionContainer {
    Text(
        text, modifier, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = color,
        maxLines = if (singleLine) 1 else Int.MAX_VALUE, overflow = if (singleLine) TextOverflow.Ellipsis else TextOverflow.Clip,
    )
}

// ── Actions ───────────────────────────────────────────────────────────────

enum class ButtonKind { Primary, Secondary, Danger }

private val ControlShape = RoundedCornerShape(6.dp)

/** Compact (32dp) button: primary is filled, secondary is outlined so a disabled one reads as clearly off. */
@Composable
fun SaltButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, kind: ButtonKind = ButtonKind.Primary, enabled: Boolean = true) {
    val m = modifier.height(32.dp)
    val pad = PaddingValues(horizontal = 14.dp)
    val label = @Composable { Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1) }
    when (kind) {
        ButtonKind.Primary -> Button(onClick, m, enabled, shape = ControlShape, contentPadding = pad) { label() }
        ButtonKind.Secondary -> OutlinedButton(
            onClick, m, enabled, shape = ControlShape, contentPadding = pad,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (enabled) 1f else 0.5f)),
        ) { label() }
        ButtonKind.Danger -> Button(
            onClick, m, enabled, shape = ControlShape, contentPadding = pad,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
        ) { label() }
    }
}

/** On/off row control. [checked] null means the state is still loading: the switch holds its place, disabled. */
@Composable
fun SaltSwitch(checked: Boolean?, onChange: (Boolean) -> Unit, label: String, modifier: Modifier = Modifier, hint: String = "", enabled: Boolean = true) =
    HStack(modifier.fillMaxWidth(), Space.md) {
        VStack(Modifier.weight(1f), Space.xs) {
            Label(label)
            if (hint.isNotBlank()) Label(hint, kind = TextStyleKind.Caption)
        }
        Label(when (checked) { true -> "On"; false -> "Off"; null -> "…" }, kind = TextStyleKind.Caption, tone = if (checked == true) Tone.Success else null)
        Switch(checked == true, onChange, enabled = enabled && checked != null)
    }

/** Destructive action that needs a second click to fire. */
@Composable
fun ConfirmButton(text: String, confirmText: String, onConfirm: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    var armed by remember { mutableStateOf(false) }
    SaltButton(
        if (armed) confirmText else text,
        { if (armed) { armed = false; onConfirm() } else armed = true },
        modifier, if (armed) ButtonKind.Danger else ButtonKind.Secondary, enabled,
    )
}

/** Runs [action] when Enter is pressed, like a terminal. */
fun Modifier.onEnter(action: () -> Unit) = onKeyEvent {
    (it.key == Key.Enter && it.type == KeyEventType.KeyDown).also { hit -> if (hit) action() }
}

// ── Inputs ────────────────────────────────────────────────────────────────

@Composable
fun SaltTextField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    error: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    mono: Boolean = false,
    onEnter: (() -> Unit)? = null,
    /** True puts the label above the field (forms); false uses it as the placeholder (toolbars, filters). */
    labelAbove: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val style = (if (mono) MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyMedium)
        .copy(color = scheme.onSurface)
    var focused by remember { mutableStateOf(false) }
    val border = if (error) scheme.error else if (focused) scheme.primary else scheme.outlineVariant
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        if (labelAbove) Text(label, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
        BasicTextField(
            value, onChange,
            Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.let { if (onEnter != null) it.onEnter(onEnter) else it },
            singleLine = singleLine, minLines = minLines, textStyle = style, cursorBrush = SolidColor(scheme.primary),
            decorationBox = { inner ->
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 34.dp).background(scheme.surfaceContainerLowest, ControlShape)
                        .border(if (focused) 2.dp else 1.dp, border, ControlShape).padding(horizontal = 10.dp, vertical = 8.dp),
                    contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
                ) {
                    if (value.isEmpty() && !labelAbove) Text(label, style = style.copy(color = scheme.onSurfaceVariant.copy(alpha = 0.7f)), maxLines = 1)
                    inner()
                }
            },
        )
        hint?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (error) scheme.error else scheme.onSurfaceVariant) }
    }
}

/** Whole row is the click target, not just the box. */
@Composable
fun Check(checked: Boolean, onChange: (Boolean) -> Unit, label: String) =
    HStack(Modifier.toggleable(checked, role = Role.Checkbox, onValueChange = onChange), spacing = Space.xs) {
        Checkbox(checked, null)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }

@Composable
fun Select(options: List<String>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton({ open = true }) { Text("$selected  ▾") }
        DropdownMenu(open, { open = false }) {
            options.forEach { o -> DropdownMenuItem({ Text(o) }, { onSelect(o); open = false }) }
        }
    }
}

// ── Surfaces & feedback ───────────────────────────────────────────────────

/** Outlined card with an optional title. */
@Composable
fun Panel(modifier: Modifier = Modifier, title: String? = null, content: @Composable ColumnScope.() -> Unit) =
    OutlinedCard(
        modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(Space.lg), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            title?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            content()
        }
    }

/** Small tonal pill. */
@Composable
fun Badge(text: String, tone: Tone = Tone.Neutral, modifier: Modifier = Modifier) {
    val c = SaltTheme.toneColor(tone)
    Text(
        text, modifier.background(c.copy(alpha = 0.14f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall, color = c, maxLines = 1,
    )
}

/** Inline message: errors, warnings, hints. Tone shown by an accent bar and tint. */
@Composable
fun Notice(text: String, tone: Tone = Tone.Danger, modifier: Modifier = Modifier) {
    val c = SaltTheme.toneColor(tone)
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(MaterialTheme.shapes.small).background(c.copy(alpha = 0.10f))) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(c))
        Text(text, Modifier.padding(Space.md), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) =
    Box(modifier.fillMaxWidth().padding(Space.xl), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }

/** Heading that shows or hides its content; content is composed only while open. */
@Composable
fun Section(title: String, expanded: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var open by remember { mutableStateOf(expanded) }
    Column {
        Divider()
        HStack(Modifier.fillMaxWidth().toggleable(open, role = Role.Button, onValueChange = { open = it }).padding(vertical = Space.md), Space.sm) {
            val turn by animateFloatAsState(if (open) 90f else 0f)
            Text("›", Modifier.rotate(turn), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(title, style = MaterialTheme.typography.titleSmall)
        }
        // Size animates instead of snapping, so opening a block never jolts what sits below it.
        Box(Modifier.fillMaxWidth().animateContentSize()) { if (open) VStack(Modifier.fillMaxWidth(), content = content) }
    }
}

@Composable
fun Divider(modifier: Modifier = Modifier) = HorizontalDivider(modifier, color = MaterialTheme.colorScheme.outlineVariant)

// ── Navigation ────────────────────────────────────────────────────────────

/** Secondary navigation: scrollable so nine tabs fit any width. */
@Composable
fun Tabs(titles: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) =
    PrimaryScrollableTabRow(selected.coerceIn(0, titles.lastIndex), modifier, edgePadding = Space.lg) {
        titles.forEachIndexed { i, t -> Tab(selected == i, { onSelect(i) }, text = { Text(t, maxLines = 1) }) }
    }

/** Grouped vertical list for a section with many tools: quiet group names, the current item marked by an accent bar. */
@Composable
fun SideNav(groups: List<Pair<String?, List<String>>>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(true) }
    // Collapsed keeps the first two letters of each tool, so the list stays usable at 48dp.
    val width by animateDpAsState(if (open) 208.dp else 48.dp)
    val scheme = MaterialTheme.colorScheme
    Column(modifier.width(width).fillMaxHeight().background(scheme.surfaceContainerLow).verticalScroll(rememberScrollState()).padding(vertical = Space.sm)) {
        Box(Modifier.fillMaxWidth().height(32.dp).toggleable(open, role = Role.Button, onValueChange = { open = it }), contentAlignment = if (open) Alignment.CenterEnd else Alignment.Center) {
            Text(if (open) "‹  Hide" else "›", Modifier.padding(horizontal = Space.md), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, maxLines = 1)
        }
        groups.forEach { (group, items) ->
            if (open) group?.let { Text(it, Modifier.padding(start = Space.lg, top = Space.md, bottom = Space.xs), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, maxLines = 1) }
            else Spacer(Modifier.height(Space.md))
            items.forEach { item ->
                val on = item == selected
                Row(
                    Modifier.fillMaxWidth().height(34.dp).background(if (on) scheme.primary.copy(alpha = 0.12f) else Color.Transparent)
                        .selectable(on, role = Role.Tab, onClick = { onSelect(item) }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(if (on) scheme.primary else Color.Transparent))
                    Text(
                        if (open) item else item.take(2), Modifier.padding(start = if (open) Space.lg - 3.dp else Space.md), maxLines = 1,
                        style = MaterialTheme.typography.bodyMedium, fontWeight = if (on) FontWeight.SemiBold else null,
                        color = if (on) scheme.primary else scheme.onSurface,
                    )
                }
            }
        }
    }
}

/** App frame: navigation rail for the top-level sections, a top bar, then the content. */
@Composable
fun AppShell(
    brand: String,
    sections: List<Pair<String, String>>,
    selected: Int,
    onSelect: (Int) -> Unit,
    topBarTitle: String,
    topBarTrailing: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) = Row(Modifier.fillMaxSize()) {
    // A section served on its own path has nothing to switch to, so no rail.
    if (sections.size > 1) NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainer, header = {
        Text(brand, Modifier.padding(vertical = Space.lg), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    }) {
        sections.forEachIndexed { i, (glyph, title) ->
            NavigationRailItem(
                selected == i, { onSelect(i) },
                icon = { Text(glyph, style = MaterialTheme.typography.titleMedium) },
                label = { Text(title, style = MaterialTheme.typography.labelMedium) },
            )
        }
    }
    Column(Modifier.weight(1f).fillMaxHeight()) {
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
            HStack(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = Space.lg), Space.md) {
                Text(topBarTitle, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                topBarTrailing()
            }
        }
        content()
    }
}
