package salt

import kotlin.test.Test
import kotlin.test.assertEquals

class UiHierarchyTest {
    private val xml = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation="0">""" +
        """<node index="0" text="" resource-id="" class="android.widget.FrameLayout" package="p" content-desc="" clickable="false" bounds="[0,0][1080,2400]">""" +
        """<node index="0" text="Tom &amp; &quot;Jerry&quot; &gt;" resource-id="p:id/title" class="android.widget.TextView" package="p" content-desc="" clickable="true" bounds="[100,200][500,300]" />""" +
        """<node index="1" text="" resource-id="p:id/list" class="androidx.recyclerview.widget.RecyclerView" package="p" content-desc="Feed" scrollable="true" bounds="[0,400][1080,2000]">""" +
        """<node index="0" text="Row" resource-id="" class="android.widget.TextView" package="p" content-desc="" bounds="[0,400][1080,500]" /></node>""" +
        """</node></hierarchy>UI hierchary dumped to: /dev/tty"""

    @Test fun buildsDepthsFromNesting() {
        assertEquals(listOf(0, 1, 1, 2), parseUiDump(xml).map { it.depth })
    }

    @Test fun readsAttributesBoundsAndEntities() {
        val title = parseUiDump(xml)[1]
        assertEquals("Tom & \"Jerry\" >", title.text)
        assertEquals("p:id/title", title.resourceId)
        assertEquals(400, title.width)
        assertEquals(listOf("clickable"), title.flags)
        assertEquals("TextView  Tom & \"Jerry\" >", title.label)
    }

    @Test fun hitTestPicksTheDeepestSmallestNode() {
        val nodes = parseUiDump(xml)
        assertEquals(1, nodes.hitTest(120, 250))   // the title, not the root
        assertEquals(3, nodes.hitTest(50, 450))    // the row inside the list
        assertEquals(2, nodes.hitTest(50, 1000))   // the list itself
        assertEquals(null, nodes.hitTest(5000, 5000))
    }

    @Test fun spacingMeasuresInsetsAndSiblingGaps() {
        val nodes = parseUiDump(xml)
        val title = nodes.spacing(1)
        assertEquals(0, title.parent)
        assertEquals(listOf(100, 200, 580, 2100), title.inset!!.let { listOf(it.left, it.top, it.right, it.bottom) })
        assertEquals(100, title.gap.bottom)   // the list starts 100px below the title
        assertEquals(null, title.gap.top)
        val row = nodes.spacing(3)
        assertEquals(listOf(0, 0, 0, 1500), row.inset!!.let { listOf(it.left, it.top, it.right, it.bottom) })
        assertEquals(null, nodes.spacing(0).inset)
    }

    @Test fun formatsLengthsInDpAndFallsBackToPx() {
        assertEquals("16dp", formatLength(42, 420))   // 42px at 420dpi
        assertEquals("16dp", formatLength(40, 400))
        assertEquals("16.8dp", formatLength(42, 400))
        assertEquals("42px", formatLength(42, null))
    }

    @Test fun parsesDensityPreferringTheOverride() {
        assertEquals(420, parseDensity("Physical density: 420"))
        assertEquals(560, parseDensity("Physical density: 420\nOverride density: 560"))
        assertEquals(null, parseDensity("error"))
    }

    @Test fun flagsSmallTouchTargetsAndUnlabeledIcons() {
        val small = UiNode(0, mapOf("clickable" to "true", "class" to "android.widget.ImageView"), 0, 0, 80, 80)
        assertEquals(2, small.issues(320).size)         // 40dp square and no description
        assertEquals(1, small.issues(null).size)        // size unknown: only the label check applies
        val ok = UiNode(0, mapOf("clickable" to "true", "class" to "android.widget.Button", "text" to "Go"), 0, 0, 200, 120)
        assertEquals(0, ok.issues(320).size)
    }

    @Test fun readsToggleStatesFromProbeOutput() {
        val states = parseToggleStates("0=1\n1=0\n3=Night mode: yes\n4=null\nnoise")
        assertEquals(true, states[0])
        assertEquals(false, states[1])
        assertEquals(null, states[2])
        assertEquals(true, states[3])
        assertEquals(true, states[4])
    }
}
