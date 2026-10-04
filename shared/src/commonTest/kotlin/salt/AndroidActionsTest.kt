package salt

import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidActionsTest {
    @Test
    fun deepLinkQuotesAmpersands() {
        val r = AndroidActions.deepLink("s1", "myapp://x?a=1&b=it's", "com.acme")
        assertEquals("s1", r.serial)
        assertEquals(
            listOf("shell", "am", "start", "-W", "-a", "android.intent.action.VIEW", "-d", "'myapp://x?a=1&b=it'\\''s'", "'com.acme'"),
            r.args,
        )
    }

    @Test
    fun deepLinkOmitsBlankPackage() {
        assertEquals("'a://b'", AndroidActions.deepLink("s", "a://b", " ").args.last())
    }

    @Test
    fun typeTextEscapesSpaces() {
        assertEquals("'hello%sworld'", AndroidActions.typeText("s", "hello world").args.last())
    }

    @Test
    fun logLevelFromThreadtime() {
        assertEquals('E', logLevel("10-04 11:00:00.123  1234  5678 E Tag: boom"))
        assertEquals(null, logLevel("\tat com.acme.Foo.bar(Foo.kt:1)"))
    }

    @Test
    fun parsesGetpropPackagesEmulators() {
        assertEquals("Pixel", parseGetprop("[ro.product.model]: [Pixel]\n[x]: []")["ro.product.model"])
        assertEquals(listOf("a.b", "z.y"), parsePackages("package:z.y\npackage:a.b\n"))
        assertEquals(listOf("Pixel_9a", "Resizable"), parseNames("Pixel_9a\nResizable\n\n"))
    }

    @Test
    fun parsesSdkListWithAndWithoutUpdates() {
        val out = listOf(
            "Installed packages:",
            "  emulator                       36.6.11   ->        37.2.12  Android Emulator",
            "  platforms/android-34           3.0.0                        Android SDK Platform 34",
        ).joinToString("\n")
        val pkgs = parseSdkPackages(out)
        assertEquals(listOf("emulator", "platforms/android-34"), pkgs.map { it.id })
        assertEquals("37.2.12", pkgs[0].newerVersion)
        assertEquals(null, pkgs[1].newerVersion)
        assertEquals("Android SDK Platform 34", pkgs[1].description)
    }
}
