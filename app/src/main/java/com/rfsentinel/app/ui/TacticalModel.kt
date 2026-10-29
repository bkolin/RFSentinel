package com.rfsentinel.app.ui

import com.rfsentinel.app.detect.Affiliation
import com.rfsentinel.app.detect.Hit

/**
 * What the Tactical view lists, kept free of Android so it is unit-tested. An entry reads like a field log:
 *
 *     <hidden>
 *     BSSID: 00:25:df:37:7c:cb
 *     Category: Priority / LE / Public Safety · Axon body camera
 *     RSSI: -82 dBm    Frequency: 5785 MHz
 *     First Seen: 274s ago
 *     Last Seen: 2s ago
 */
object TacticalModel {
    /** One device as the Tactical view needs it. */
    data class Device(
        val mac: String,
        val wifi: Boolean,
        val name: String?,
        val vendor: String?,
        val deviceType: String?,
        val best: Hit?,
        val rssi: Int,
        val frequencyMhz: Int?,
        val firstSeen: Long,
        val lastSeen: Long,
    ) {
        val affiliation: Affiliation by lazy { Affiliation.classify(best, vendor, deviceType, name) }
    }

    data class Entry(
        val mac: String,
        val title: String,
        /** True for a network with no name: shown as "<hidden>" in its own colour. */
        val hidden: Boolean,
        val lines: List<String>,
        val affiliation: Affiliation,
    )

    /** Most important first: by affiliation (Priority, Threat, yours, Fleet...), then the strongest signal. */
    fun sorted(devices: List<Device>): List<Device> =
        devices.sortedWith(compareBy<Device> { it.affiliation.rank }.thenByDescending { it.rssi })

    /** "2s ago", "274s ago" (as SØPHIA counts), "12m ago" past 15 minutes, "3h ago" past 2 hours. */
    fun ago(now: Long, then: Long): String {
        val s = ((now - then) / 1000).coerceAtLeast(0)
        return when {
            s < 900 -> "${s}s ago"
            s < 7_200 -> "${s / 60}m ago"
            else -> "${s / 3600}h ago"
        }
    }

    fun entry(d: Device, now: Long): Entry {
        val hidden = d.name.isNullOrBlank()
        val title = if (hidden) (if (d.wifi) "<hidden>" else "<no name>") else d.name!!.trim()
        val lines = buildList {
            add("${if (d.wifi) "BSSID" else "MAC"}: ${d.mac.lowercase()}")
            add("Category: ${Affiliation.describe(d.affiliation, d.best, d.vendor)}")
            add("RSSI: ${d.rssi} dBm" + (d.frequencyMhz?.let { "    Frequency: $it MHz" } ?: ""))
            add("First Seen: ${ago(now, d.firstSeen)}")
            add("Last Seen: ${ago(now, d.lastSeen)}")
        }
        return Entry(d.mac, title, hidden && d.wifi, lines, d.affiliation)
    }

    /** "Showing top 5 of 25". */
    fun showing(shown: Int, total: Int) = "Showing top $shown of $total"

    /** "Wi-Fi radar points: 12 | BLE devices: 542 | refresh: 1s". */
    fun radarFooter(wifi: Int, ble: Int, refreshSec: Int) = "Wi-Fi radar points: $wifi | BLE devices: $ble | refresh: ${refreshSec}s"

    /** Per affiliation, how many [devices] there are, for the legend ("Priority 1 · Fleet 2 ..."); empty ones left out. */
    fun counts(devices: List<Device>): Map<Affiliation, Int> =
        devices.groupingBy { it.affiliation }.eachCount().toSortedMap(compareBy { it.rank })
}
