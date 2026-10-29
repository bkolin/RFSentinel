package com.rfsentinel.app.ui

import com.rfsentinel.app.service.DeviceRegistry.Sample
import com.rfsentinel.app.ui.RadarModel.Layout
import com.rfsentinel.app.ui.RadarModel.Motion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class RadarModelTest {
    private val now = 1_000_000L

    /** One reading a second for [seconds], rising by [dbPerSec] each, with +/-[jitter] dB of packet noise. */
    private fun readings(seconds: Int, start: Int, dbPerSec: Double, jitter: Int = 0) = (0 until seconds).map { i ->
        Sample(now - (seconds - 1 - i) * 1000L, (start + dbPerSec * i + if (i % 2 == 0) jitter else -jitter).toInt())
    }

    @Test
    fun aSignalRisingAsYouDriveUpIsClosing() {
        val h = readings(30, -95, 1.0, jitter = 5) // +60 dB a minute under noisy packets
        val slope = RadarModel.slopeDbPerMin(h, now)!!
        assertEquals(60.0, slope, 6.0)
        assertEquals(Motion.CLOSING, RadarModel.motion(slope))
    }

    @Test
    fun aFadingSignalIsOpeningAndANoisySteadyOneIsSteady() {
        assertEquals(Motion.OPENING, RadarModel.motion(RadarModel.slopeDbPerMin(readings(30, -50, -0.8), now)))
        assertEquals("packet noise alone isn't motion",
            Motion.STEADY, RadarModel.motion(RadarModel.slopeDbPerMin(readings(30, -70, 0.0, jitter = 6), now)))
    }

    @Test
    fun tooFewOrTooBunchedReadingsAreUnknown() {
        assertNull("WiFi scanned every 30 s", RadarModel.slopeDbPerMin(listOf(Sample(now - 25_000, -80), Sample(now, -60)), now))
        val burst = (0 until 10).map { Sample(now - it * 100L, -60 - it) }
        assertNull("a one-second burst", RadarModel.slopeDbPerMin(burst, now))
        assertEquals(Motion.UNKNOWN, RadarModel.motion(null))
        assertNull("old readings don't count", RadarModel.slopeDbPerMin(readings(30, -90, 1.0).map { it.copy(time = it.time - 120_000) }, now))
    }

    @Test
    fun approachLayoutPutsRisingOnTopAndFadingBelow() {
        for (mac in listOf("AA:BB:CC:00:00:01", "11:22:33:44:55:66", "C0:FF:EE:12:34:56")) {
            // Screen y points down: sin < 0 is the top half.
            assertTrue(sin(RadarModel.angle(mac, Layout.APPROACH, Motion.CLOSING)) < -0.4)
            assertTrue(sin(RadarModel.angle(mac, Layout.APPROACH, Motion.OPENING)) > 0.4)
            assertTrue("steady sits at the sides", kotlin.math.abs(sin(RadarModel.angle(mac, Layout.APPROACH, Motion.STEADY))) <= 0.51)
        }
    }

    @Test
    fun aDeviceKeepsItsSpot() {
        val mac = "AA:BB:CC:00:00:01"
        assertEquals(RadarModel.angle(mac, Layout.SPREAD, Motion.CLOSING), RadarModel.angle(mac, Layout.SPREAD, Motion.OPENING), 0.0)
        assertEquals(RadarModel.angle(mac, Layout.APPROACH, Motion.CLOSING), RadarModel.angle(mac, Layout.APPROACH, Motion.CLOSING), 0.0)
    }

    @Test
    fun trailAveragesSlicesOldestFirstAndSkipsGaps() {
        val h = listOf(Sample(now - 29_000, -90), Sample(now - 28_000, -88), Sample(now - 2_000, -60))
        assertEquals(listOf(-89, -60), RadarModel.trail(h, now))
        assertEquals(emptyList<Int>(), RadarModel.trail(emptyList(), now))
    }

    @Test
    fun captionsMatchTheLayout() {
        assertTrue(RadarModel.caption(Layout.SPREAD).contains("not direction"))
        assertTrue(RadarModel.caption(Layout.APPROACH).contains("rising"))
    }
}
