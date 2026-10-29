package com.rfsentinel.app.ui

import com.rfsentinel.app.service.DeviceRegistry
import kotlin.math.PI

/**
 * Where [RadarView] puts a blip, kept free of Android so it is unit-tested.
 *
 * Distance from the centre is always signal strength. The angle has two layouts:
 *  - [Layout.SPREAD]: a stable angle from the address, only so blips don't sit on top of each other. It means
 *    nothing, and the radar says so.
 *  - [Layout.APPROACH]: the angle says whether the signal is rising or fading. A phone can't tell where a signal
 *    comes from, but it can tell whether it is getting stronger: something you are driving towards (or that is
 *    coming at you) rises, something you have passed fades. Rising blips go in the top half, fading ones in the
 *    bottom half, steady or not-yet-known ones at the sides.
 */
object RadarModel {
    enum class Kind(val label: String) { WIFI("WiFi"), BLE("BLE"), RADIO("Radio") }
    enum class Layout { SPREAD, APPROACH }
    enum class Motion { CLOSING, OPENING, STEADY, UNKNOWN }

    /** How far back the trend and the trail look. */
    const val WINDOW_MS = 30_000L
    /** Fewer readings than this in [WINDOW_MS] and the trend is unknown (WiFi, scanned every 30 s, usually is). */
    private const val MIN_SAMPLES = 4
    /** And they must span at least this long: a burst of adverts in one second says nothing about a trend. */
    private const val MIN_SPAN_MS = 8_000L
    /**
     * dB per minute a signal must rise or fall to count as moving. BLE readings swing by +/-5 dB from packet to
     * packet, so a least-squares slope over 30 s needs a clear margin; driving past a roadside device changes it
     * by tens of dB a minute.
     */
    const val MOVING_DB_PER_MIN = 10.0

    /** Least-squares slope of the readings of the last [windowMs], in dB per minute; null with too few readings. */
    fun slopeDbPerMin(history: List<DeviceRegistry.Sample>, now: Long, windowMs: Long = WINDOW_MS): Double? {
        val recent = history.filter { now - it.time in 0..windowMs }
        if (recent.size < MIN_SAMPLES || recent.last().time - recent.first().time < MIN_SPAN_MS) return null
        val t0 = recent.first().time
        val xs = recent.map { (it.time - t0) / 60_000.0 }
        val ys = recent.map { it.rssi.toDouble() }
        val mx = xs.average()
        val my = ys.average()
        val sxx = xs.sumOf { (it - mx) * (it - mx) }
        if (sxx == 0.0) return null
        return xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / sxx
    }

    fun motion(slopeDbPerMin: Double?): Motion = when {
        slopeDbPerMin == null -> Motion.UNKNOWN
        slopeDbPerMin >= MOVING_DB_PER_MIN -> Motion.CLOSING
        slopeDbPerMin <= -MOVING_DB_PER_MIN -> Motion.OPENING
        else -> Motion.STEADY
    }

    /**
     * The blip's angle in radians, screen convention (0 = right, PI/2 = down). The address picks the spot inside
     * the sector, so a device keeps its place while its motion stays the same.
     */
    fun angle(mac: String, layout: Layout, motion: Motion): Double {
        val spot = ((mac.hashCode() and 0x7fffffff) % 1000) / 1000.0
        if (layout == Layout.SPREAD) return spot * 2 * PI
        fun sector(fromDeg: Double, toDeg: Double) = Math.toRadians(fromDeg + (toDeg - fromDeg) * spot)
        return when (motion) {
            Motion.CLOSING -> sector(215.0, 325.0) // top
            Motion.OPENING -> sector(35.0, 145.0) // bottom
            // The sides, -30..30 (right) or 150..210 (left) degrees, by the address.
            else -> if (spot < 0.5) Math.toRadians(-30.0 + 120.0 * spot) else Math.toRadians(150.0 + 120.0 * (spot - 0.5))
        }
    }

    /**
     * The last [windowMs] of readings averaged into [points] equal slices, oldest first, for the fading trail
     * behind a blip. Empty slices are skipped, so a device heard only lately has a short trail.
     */
    fun trail(history: List<DeviceRegistry.Sample>, now: Long, windowMs: Long = WINDOW_MS, points: Int = 6): List<Int> {
        if (history.isEmpty()) return emptyList()
        val slice = windowMs / points
        return (0 until points).mapNotNull { i ->
            val from = now - windowMs + i * slice
            history.filter { it.time >= from && it.time < from + slice }.takeIf { it.isNotEmpty() }
                ?.let { s -> s.sumOf { it.rssi } / s.size }
        }
    }

    /** The caption under the radar for [layout]. */
    fun caption(layout: Layout): String = when (layout) {
        Layout.SPREAD -> "Closer to centre = stronger signal · angle is not direction"
        Layout.APPROACH -> "Top = signal rising (getting closer) · bottom = fading · sides = steady"
    }
}
