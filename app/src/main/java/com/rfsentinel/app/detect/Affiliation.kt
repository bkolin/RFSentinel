package com.rfsentinel.app.detect

import com.rfsentinel.app.oui.OuiEntry

/**
 * Who a device most likely belongs to, as the Tactical view sorts and colours it: alongside [Category], which says
 * what the equipment IS. A body camera and a squad-car router are both police kit to most people, but a cellular
 * router alone is also on ambulances, buses and utility trucks - so routers are [FLEET], and only equipment that is
 * police-specific is [PRIORITY].
 *
 * This is a reading of the evidence, never proof of who operates a device (see [classify]).
 */
enum class Affiliation(val title: String, val detail: String, val colorArgb: Int, val rank: Int) {
    /** Law-enforcement / public-safety equipment: body cams, plate readers, police-only gear. */
    PRIORITY("Priority", "LE / Public Safety", 0xFFFF3B6B.toInt(), 0),
    /** Something that could be aimed at you: trackers, camera glasses, drones, skimmers, hacking tools, hidden cameras. */
    THREAT("Threat", "Tracker / Surveillance", 0xFFFF8A3D.toInt(), 1),
    /** Matched one of your own watchlist rules. */
    CUSTOM("Custom", "Your watchlist", 0xFFB98CFF.toInt(), 2),
    /** Vehicle cellular routers and modems (Cradlepoint, Sierra Wireless, Inseego, Peplink, Digi...). */
    FLEET("Fleet", "Cellular Router", 0xFFFFC845.toInt(), 3),
    /** Business access points, public hotspots, beacons and other infrastructure. */
    COMMERCIAL("Commercial", "Infrastructure", 0xFF4FC3F7.toInt(), 4),
    /** Everything else: home routers, phones, earbuds, TVs. */
    RESIDENTIAL("Residential", "Consumer", 0xFF7BD88F.toInt(), 5);

    /** "Priority / LE / Public Safety". */
    val label get() = "$title / $detail"

    companion object {
        /** Detection categories that are police / public-safety equipment. */
        private val PRIORITY_CATEGORIES = setOf(
            Category.BODY_CAM, Category.ALPR, Category.AUDIO_SENSOR, Category.PUBLIC_SAFETY, Category.POLICE_REPORT,
            Category.CELL_ANOMALY, Category.AIRCRAFT
        )

        /** Makers of vehicle cellular routers and modems (IEEE registrant / Bluetooth company names). */
        private val FLEET_VENDOR = Regex(
            "cradlepoint|sierra wireless|inseego|novatel|peplink|pepwave|digi international|digiboard|teltonika|" +
                "multitech|multi-tech|semtech|airlink", RegexOption.IGNORE_CASE
        )
        /** What a hit label or device type says about a router rather than a police-only device. */
        private val ROUTER_WORDS = Regex("router|modem|gateway|hotspot|cellular|in-vehicle|mobile hotspot", RegexOption.IGNORE_CASE)
        /** Words that keep a router hit police-specific (an Axon Fleet SSID is police, a bare Cradlepoint isn't). */
        private val POLICE_WORDS = Regex("\\bpolice\\b|\\bpd\\b|sheriff|axon|watchguard|motorola solutions|getac|body ?cam|patrol|squad|\\ble\\b",
            RegexOption.IGNORE_CASE)

        /** Device types (from [DeviceIntel]) that are infrastructure rather than a person's own device. */
        private val COMMERCIAL_TYPE = Regex(
            "^business (access point|wifi)|^public wifi hotspot|^open wifi|^train wifi|beacon|eddystone|ibeacon|" +
                "printer|point of sale|payment|^pos\\b|scale|digital signage|^sensor|gateway|access control|parking",
            RegexOption.IGNORE_CASE
        )
        /** Enterprise WiFi makers: their access points are in offices, shops, schools and hotels, rarely homes. */
        private val COMMERCIAL_VENDOR = Regex(
            "cisco|meraki|aruba|hewlett packard enterprise|ruckus|commscope|juniper|mist systems|extreme networks|aerohive|" +
                "fortinet|cambium|ubiquiti|zebra|honeywell|siemens|axis communications|hanwha|hikvision|dahua",
            RegexOption.IGNORE_CASE
        )
        /** Network names that announce a business or public network. */
        private val COMMERCIAL_SSID = Regex(
            "guest|public|free|corp|office|staff|employee|xfinitywifi|attwifi|spectrum ?wifi|optimumwifi|cablewifi|" +
                "starbucks|mcdonald|walmart|target|hotel|airport|library|school|hospital|_pos\\b|-pos\\b",
            RegexOption.IGNORE_CASE
        )

        /**
         * The affiliation of a device from what is known about it: its strongest detection [best] (null when nothing
         * flagged it), the maker [vendor], the [deviceType] [DeviceIntel] gave it and its advertised [name] / SSID.
         * Strongest evidence first: your own rules, then police categories, then threats, then the maker and name.
         */
        fun classify(best: Hit?, vendor: String?, deviceType: String?, name: String?): Affiliation {
            val v = vendor.orEmpty()
            val type = deviceType.orEmpty()
            if (best != null) {
                if (best.source == OuiEntry.YOURS || best.category == Category.CUSTOM) return CUSTOM
                if (best.category in PRIORITY_CATEGORIES) {
                    // A public-safety match that is only a router (by its label or maker) is fleet gear, unless
                    // something in it is police-specific.
                    val routerOnly = (ROUTER_WORDS.containsMatchIn(best.label) || FLEET_VENDOR.containsMatchIn(v)) &&
                        best.category == Category.PUBLIC_SAFETY && !POLICE_WORDS.containsMatchIn(best.label)
                    return if (routerOnly) FLEET else PRIORITY
                }
                return THREAT
            }
            if (FLEET_VENDOR.containsMatchIn(v)) return FLEET
            if (COMMERCIAL_TYPE.containsMatchIn(type) || COMMERCIAL_VENDOR.containsMatchIn(v) ||
                name?.let { COMMERCIAL_SSID.containsMatchIn(it) } == true) return COMMERCIAL
            return RESIDENTIAL
        }

        /**
         * The category line of a Tactical entry: "Priority / LE / Public Safety · Axon body camera",
         * "Threat / Apple AirTag", "Fleet / Cellular Router · Cradlepoint", "Residential / Consumer · Apple".
         */
        fun describe(a: Affiliation, best: Hit?, vendor: String?): String = when {
            best != null && (a == PRIORITY || a == FLEET) -> "${a.label} · ${best.label}"
            best != null -> "${a.title} / ${best.label}"
            !vendor.isNullOrBlank() -> "${a.label} · ${vendor.take(28)}"
            else -> a.label
        }
    }
}
