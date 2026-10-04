package salt

import kotlin.math.abs
import kotlin.math.roundToInt

/** One view from a `uiautomator dump`. Nodes come in document order, so a parent always precedes its children. */
class UiNode(val depth: Int, val attrs: Map<String, String>, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val shortClass: String get() = attrs["class"].orEmpty().substringAfterLast('.')
    val resourceId: String get() = attrs["resource-id"].orEmpty()
    val text: String get() = attrs["text"].orEmpty()
    val description: String get() = attrs["content-desc"].orEmpty()
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    /** Class plus the most telling detail: text, then description, then id. */
    val label: String
        get() {
            val detail = text.ifBlank { description }.ifBlank { resourceId.substringAfter(":id/") }
            return if (detail.isBlank()) shortClass else "$shortClass  ${detail.take(40)}"
        }

    /** Boolean attributes that are true, e.g. clickable, scrollable. */
    val flags: List<String> get() = flagNames.filter { attrs[it] == "true" }

    fun contains(x: Int, y: Int) = x in left until right && y in top until bottom

    companion object {
        private val flagNames = listOf("clickable", "long-clickable", "scrollable", "focusable", "checkable", "checked", "selected", "password")
    }
}

private val tag = Regex("""<node((?:[^>"]|"[^"]*")*?)(/?)>|</node>""")
private val attribute = Regex("""([\w-]+)="([^"]*)"""")
private val boundsPattern = Regex("""\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""")

private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
    .replace("&#10;", "\n").replace("&#13;", "").replace("&amp;", "&")

/** Parses `uiautomator dump` XML (anything after the closing tag is ignored). Nodes without bounds are dropped. */
fun parseUiDump(xml: String): List<UiNode> {
    val nodes = mutableListOf<UiNode>()
    var depth = 0
    for (m in tag.findAll(xml)) {
        if (m.value == "</node>") { depth = maxOf(0, depth - 1); continue }
        val attrs = attribute.findAll(m.groupValues[1]).associate { it.groupValues[1] to unescape(it.groupValues[2]) }
        boundsPattern.find(attrs["bounds"].orEmpty())?.destructured?.let { (l, t, r, b) ->
            nodes += UiNode(depth, attrs, l.toInt(), t.toInt(), r.toInt(), b.toInt())
        }
        if (m.groupValues[2].isEmpty()) depth++
    }
    return nodes
}

/** Distances in device pixels on each side; null where there is nothing on that side. */
class Edges(val left: Int?, val top: Int?, val right: Int?, val bottom: Int?)

/** [inset] is the distance to the parent's edges (its padding, as laid out); [gap] is the distance to the nearest sibling per side. */
class Spacing(val parent: Int?, val inset: Edges?, val gap: Edges)

/** Index of the node's parent: the closest earlier node one level up. */
fun List<UiNode>.parentOf(i: Int): Int? {
    val depth = this[i].depth
    if (depth == 0) return null
    for (j in i - 1 downTo 0) if (this[j].depth == depth - 1) return j
    return null
}

/**
 * Spacing around node [i], measured from bounds. `uiautomator` reports no padding or margin values, so this is the
 * distance as laid out, which is what a design review compares against anyway.
 */
fun List<UiNode>.spacing(i: Int): Spacing {
    val n = this[i]
    val p = parentOf(i)
    val parent = p?.let { this[it] }
    val inset = parent?.let { Edges(n.left - it.left, n.top - it.top, it.right - n.right, it.bottom - n.bottom) }
    val siblings = mutableListOf<UiNode>()
    if (p != null) {
        var k = p + 1
        while (k < size && this[k].depth > parent!!.depth) {
            if (this[k].depth == n.depth && k != i && this[k].width > 0 && this[k].height > 0) siblings += this[k]
            k++
        }
    }
    val sameRow = siblings.filter { it.top < n.bottom && it.bottom > n.top }
    val sameColumn = siblings.filter { it.left < n.right && it.right > n.left }
    return Spacing(
        p, inset,
        Edges(
            left = sameRow.filter { it.right <= n.left }.minOfOrNull { n.left - it.right },
            top = sameColumn.filter { it.bottom <= n.top }.minOfOrNull { n.top - it.bottom },
            right = sameRow.filter { it.left >= n.right }.minOfOrNull { it.left - n.right },
            bottom = sameColumn.filter { it.top >= n.bottom }.minOfOrNull { it.top - n.bottom },
        ),
    )
}

/** Screen density in dpi from `wm density` output; an override wins over the physical value. */
fun parseDensity(output: String): Int? {
    val found = Regex("""(Override|Physical) density: (\d+)""").findAll(output).associate { it.groupValues[1] to it.groupValues[2].toInt() }
    return found["Override"] ?: found["Physical"]
}

/** "16dp" or "16.5dp" for a length in device pixels; plain "24px" when [density] is unknown. */
fun formatLength(px: Int, density: Int?): String {
    if (density == null || density <= 0) return "${px}px"
    val tenths = (px * 1600f / density).roundToInt()
    return if (tenths % 10 == 0) "${tenths / 10}dp" else "${tenths / 10}.${abs(tenths % 10)}dp"
}

/** Design problems visible from the dump alone: small touch targets and unlabeled icon buttons. */
fun UiNode.issues(density: Int?): List<String> = buildList {
    val clickable = attrs["clickable"] == "true"
    if (clickable && density != null && density > 0 && minOf(width, height) * 160f / density < 48f) {
        add("Touch target ${formatLength(width, density)} × ${formatLength(height, density)}, under the 48dp minimum.")
    }
    if (clickable && shortClass in setOf("ImageView", "ImageButton") && text.isBlank() && description.isBlank()) {
        add("Clickable image without a content description.")
    }
}

/** The smallest node under the point (x, y) in device pixels, or null. Later nodes win ties, being deeper. */
fun List<UiNode>.hitTest(x: Int, y: Int): Int? =
    indices.filter { this[it].contains(x, y) }.minWithOrNull(compareBy<Int> { this[it].width.toLong() * this[it].height }.thenByDescending { it })
