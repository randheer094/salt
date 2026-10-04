package salt

import kotlin.test.Test
import kotlin.test.assertEquals

class AdbParsersTest {
    @Test
    fun parsesDevicesWithAttributes() {
        val out = """
            * daemon started successfully
            List of devices attached
            emulator-5554          device product:sdk_gphone model:sdk_gphone64_arm64 device:emu64a transport_id:1
            R5CT1234               unauthorized transport_id:2

        """.trimIndent()
        val devices = parseDevices(out)
        assertEquals(2, devices.size)
        assertEquals("sdk gphone64 arm64", devices[0].model)
        assertEquals(true, devices[0].isEmulator)
        assertEquals("unauthorized", devices[1].state)
    }
}
