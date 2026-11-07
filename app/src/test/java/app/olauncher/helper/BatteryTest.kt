package app.olauncher.helper

import app.olauncher.helper.Battery.Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryTest {

    private val hour = 3_600_000L

    @Test
    fun drainIsSplitByScreen() {
        // Two hours screen off losing 4%, then one hour screen on losing 10%
        val samples = listOf(
            Sample(0, 80, charging = false, screenOn = false),
            Sample(2 * hour, 76, charging = false, screenOn = true),
            Sample(3 * hour, 66, charging = false, screenOn = false),
        )
        assertEquals(2f, Battery.drainPerHour(samples, 0, screenOn = false)!!, 0.01f)
        assertEquals(10f, Battery.drainPerHour(samples, 0, screenOn = true)!!, 0.01f)
    }

    @Test
    fun chargingTimeIsLeftOut() {
        val samples = listOf(
            Sample(0, 30, charging = true, screenOn = false),
            Sample(hour, 60, charging = true, screenOn = false),
        )
        assertNull(Battery.drainPerHour(samples, 0, screenOn = false))
    }

    @Test
    fun aChargeRunsFromPluggedInToUnplugged() {
        val samples = listOf(
            Sample(0, 40, charging = false, screenOn = false),
            Sample(hour, 39, charging = true, screenOn = false),
            Sample(2 * hour, 90, charging = true, screenOn = false),
            Sample(3 * hour, 90, charging = false, screenOn = true),
        )
        val charges = Battery.charges(samples)
        assertEquals(1, charges.size)
        assertEquals(40, charges[0].fromLevel)
        assertEquals(90, charges[0].toLevel)
    }
}
