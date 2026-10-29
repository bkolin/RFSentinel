package com.rfsentinel.app.ui

import com.rfsentinel.app.detect.Affiliation
import com.rfsentinel.app.detect.Category
import com.rfsentinel.app.detect.Hit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TacticalModelTest {
    private val now = 10_000_000L
    private fun dev(mac: String, rssi: Int, wifi: Boolean = true, name: String? = "Net", vendor: String? = null, best: Hit? = null, freq: Int? = null) =
        TacticalModel.Device(mac, wifi, name, vendor, "Router", best, rssi, freq, now - 274_000, now - 2_000)

    @Test
    fun priorityFirstThenStrongest() {
        val home = dev("AA:00:00:00:00:01", -40, vendor = "NETGEAR")
        val home2 = dev("AA:00:00:00:00:02", -70, vendor = "NETGEAR")
        val cam = dev("00:25:DF:37:7C:CB", -82, name = null, best = Hit(Category.BODY_CAM, "Axon body camera", 90, "x", "IEEE"))
        val fleet = dev("00:30:44:00:00:01", -60, vendor = "Cradlepoint")
        assertEquals(listOf(cam, fleet, home, home2), TacticalModel.sorted(listOf(home2, home, fleet, cam)))
    }

    @Test
    fun anEntryReadsLikeSophiasCard() {
        val cam = dev("00:25:DF:37:7C:CB", -82, name = null, best = Hit(Category.BODY_CAM, "Axon body camera", 90, "x", "IEEE"), freq = 5785)
        val e = TacticalModel.entry(cam, now)
        assertEquals("<hidden>", e.title)
        assertTrue(e.hidden)
        assertEquals(Affiliation.PRIORITY, e.affiliation)
        assertEquals(listOf(
            "BSSID: 00:25:df:37:7c:cb",
            "Category: Priority / LE / Public Safety · Axon body camera",
            "RSSI: -82 dBm    Frequency: 5785 MHz",
            "First Seen: 274s ago",
            "Last Seen: 2s ago",
        ), e.lines)
    }

    @Test
    fun bluetoothEntriesSayMacAndAreNotHiddenNetworks() {
        val e = TacticalModel.entry(dev("C1:11:22:33:44:55", -60, wifi = false, name = null), now)
        assertEquals("<no name>", e.title)
        assertFalse(e.hidden)
        assertTrue(e.lines[0].startsWith("MAC: "))
        assertTrue("no frequency line part without one", e.lines[2] == "RSSI: -60 dBm")
    }

    @Test
    fun agesAndFooters() {
        assertEquals("2s ago", TacticalModel.ago(now, now - 2_000))
        assertEquals("899s ago", TacticalModel.ago(now, now - 899_000))
        assertEquals("15m ago", TacticalModel.ago(now, now - 900_000))
        assertEquals("3h ago", TacticalModel.ago(now, now - 3 * 3_600_000))
        assertEquals("0s ago", TacticalModel.ago(now, now + 5_000))
        assertEquals("Showing top 1 of 25", TacticalModel.showing(1, 25))
        assertEquals("Wi-Fi radar points: 12 | BLE devices: 542 | refresh: 1s", TacticalModel.radarFooter(12, 542, 1))
    }

    @Test
    fun countsAreInRankOrder() {
        val c = TacticalModel.counts(listOf(dev("A", -50, vendor = "NETGEAR"), dev("B", -50, vendor = "Cradlepoint"), dev("C", -50, vendor = "NETGEAR")))
        assertEquals(listOf(Affiliation.FLEET to 1, Affiliation.RESIDENTIAL to 2), c.toList())
    }
}
