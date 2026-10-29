package com.rfsentinel.app.detect

import com.rfsentinel.app.oui.OuiEntry

/**
 * Who a device most likely belongs to, as the Tactical view sorts and colours it: alongside [Category], which says
 * what the equipment IS. A body camera and a squad-car router are both police kit to most people, but a cellular
 * router alone is also on ambulances, buses and utility trucks - so routers are [FLEET], and only equipment that is
 * police-specific is [PRIORITY]. A router travelling with EMS or fire equipment, or named like an ambulance or fire
 * engine, is [EMERGENCY].
 *
 * This is a reading of the evidence, never proof of who operates a device (see [read]).
 */
enum class Affiliation(val title: String, val detail: String, val colorArgb: Int, val rank: Int) {
    /** Law-enforcement equipment: body cams, plate readers, police-only gear. */
    PRIORITY("Priority", "LE / Public Safety", 0xFFFF2E88.toInt(), 0),
    /** Fire and EMS: defibrillators and patient monitors, breathing-apparatus telemetry, and routers with them. */
    EMERGENCY("Emergency", "EMS / Fire", 0xFFFF5A36.toInt(), 1),
    /** Something that could be aimed at you: trackers, camera glasses, drones, skimmers, hacking tools, hidden cameras. */
    THREAT("Threat", "Tracker / Surveillance", 0xFFC77DFF.toInt(), 2),
    /** Matched one of your own watchlist rules. */
    CUSTOM("Custom", "Your watchlist", 0xFF8C9EFF.toInt(), 3),
    /** Vehicle cellular routers and fleet telematics (trucks, vans, buses, utility vehicles). */
    FLEET("Fleet", "Cellular Router", 0xFFFFC845.toInt(), 4),
    /** Business access points, public hotspots, beacons and other infrastructure. */
    COMMERCIAL("Commercial", "Infrastructure", 0xFF4FC3F7.toInt(), 5),
    /** Everything else: home routers, phones, earbuds, TVs. */
    RESIDENTIAL("Residential", "Consumer", 0xFF7BD88F.toInt(), 6);

    /** "Priority / LE / Public Safety". */
    val label get() = "$title / $detail"

    /** An affiliation with the finer [detail] the evidence gave ("Telematics", "Transit"...), and why. */
    data class Reading(val affiliation: Affiliation, val detail: String = affiliation.detail, val because: String? = null) {
        val label get() = "${affiliation.title} / $detail"
    }

    companion object {
        /** Detection categories that are police / public-safety equipment. */
        private val PRIORITY_CATEGORIES = setOf(
            Category.BODY_CAM, Category.ALPR, Category.AUDIO_SENSOR, Category.PUBLIC_SAFETY, Category.POLICE_REPORT,
            Category.CELL_ANOMALY, Category.AIRCRAFT
        )

        private fun re(s: String) = Regex(s, RegexOption.IGNORE_CASE)

        /** Makers of vehicle cellular routers and modems (IEEE registrant / Bluetooth company names). */
        private val FLEET_VENDOR = re("cradlepoint|sierra wireless|inseego|novatel|peplink|pepwave|digi international|digiboard|" +
            "teltonika|multitech|multi-tech|semtech|airlink")
        /**
         * Fleet telematics and fleet dashcams (IEEE / Bluetooth SIG registrants): Samsara, Geotab, Motive (ex-KeepTruckin),
         * Verizon Connect (ex-Fleetmatics), Omnitracs, CalAmp, Lytx. On trucks, vans and service vehicles.
         */
        private val TELEMATICS_VENDOR = re("samsara|geotab|motive technologies|keeptruckin|verizon connect|fleetmatics|omnitracs|calamp|lytx")
        /** Transit-bus systems (Clever Devices) and school-bus / trucking telematics (Zonar Systems). */
        private val TRANSIT_VENDOR = re("clever devices|zonar")
        /**
         * EMS and fire equipment makers (IEEE / Bluetooth SIG registrants): defibrillators and monitors (Stryker,
         * Physio-Control - LIFEPAK, ZOLL, Philips cardiac and monitoring systems, Mindray), Dräger (medical and
         * breathing apparatus) and MSA (breathing-apparatus telemetry).
         */
        private val EMS_VENDOR = re("stryker|physio-control|\\bzoll\\b|philips medical|mindray|draeger|dräger|msa innovation|mine safety")

        /** What a hit label or device type says about a router rather than a police-only device. */
        private val ROUTER_WORDS = re("router|modem|gateway|hotspot|cellular|in-vehicle|mobile hotspot")
        /**
         * Police-specific makers and products: these keep a router match Priority (an Axon Fleet SSID is police kit).
         * "Possible police vehicle" alone is not enough: the same routers are on ambulances and buses.
         */
        private val POLICE_SPECIFIC = re("axon|watchguard|motorola solutions|getac|body ?cam|sheriff|\\bpolice (car|cruiser|unit)\\b")
        /**
         * Unit names agencies give fire and EMS vehicles' networks: MEDIC 12, AMB-3, ENGINE41, LADDER 2, RESCUE-1, EMS...
         * Not "squad" or "unit": police use those too.
         */
        private val EMS_NAME = re("\\b(medic|amb|ambulance|ems|engine|ladder|rescue|battalion|fire|fd)[ _-]?\\d|\\bambulance\\b|\\bems\\b|\\bfire ?dept|\\bamr\\b")
        /** Bus and transit network names. */
        private val TRANSIT_NAME = re("\\b(bus|transit|metro|coach)\\b|^bus[ _-]?\\d")

        /**
         * Device types (from [DeviceIntel]) that are business infrastructure. Not beacons, sensors or gateways: phones,
         * earbuds and home ISP gateways advertise as those too.
         */
        private val COMMERCIAL_TYPE = re(
            "^business (access point|wifi)|^public wifi hotspot|^open wifi|^train wifi|printer|point of sale|payment|^pos\\b|digital signage"
        )
        /**
         * Enterprise WiFi makers: their access points are in offices, shops, schools and hotels, rarely homes. Not makers
         * that also sell home gear (CommScope / Arris cable modems, Ubiquiti, Honeywell and Siemens thermostats).
         */
        private val COMMERCIAL_VENDOR = re(
            "cisco|meraki|aruba|hewlett packard enterprise|ruckus|juniper|mist systems|extreme networks|aerohive|" +
                "fortinet|cambium|zebra|axis communications|hanwha|hikvision|dahua"
        )
        /**
         * Network names that announce a business or public network. Not "guest": home routers make "<name>-guest"
         * networks.
         */
        private val COMMERCIAL_SSID = re(
            "public|\\bfree\\b|corp|office|staff|employee|xfinitywifi|attwifi|spectrum ?wifi|optimumwifi|cablewifi|" +
                "starbucks|mcdonald|walmart|target|hotel|airport|library|school|hospital|_pos\\b|-pos\\b"
        )

        /** Whether [vendor] makes EMS / fire equipment: such a device heard next to a router makes the router [EMERGENCY]. */
        fun isEmsVendor(vendor: String?) = vendor != null && EMS_VENDOR.containsMatchIn(vendor)

        fun classify(best: Hit?, vendor: String?, deviceType: String?, name: String?, nearbyEms: String? = null): Affiliation =
            read(best, vendor, deviceType, name, nearbyEms).affiliation

        /**
         * The affiliation of a device from what is known about it: its strongest detection [best] (null when nothing
         * flagged it), the maker [vendor], the [deviceType] [DeviceIntel] gave it, its advertised [name] / SSID, and
         * [nearbyEms]: the maker of EMS / fire equipment heard right beside it (same moment, about the same signal),
         * if any. Strongest evidence first: your own rules, police kit, threats, then the maker and name.
         */
        fun read(best: Hit?, vendor: String?, deviceType: String?, name: String?, nearbyEms: String? = null): Reading {
            val v = vendor.orEmpty()
            val type = deviceType.orEmpty()
            if (best != null) {
                if (best.source == OuiEntry.YOURS || best.category == Category.CUSTOM) return Reading(CUSTOM)
                if (best.category in PRIORITY_CATEGORIES) {
                    // A public-safety match that is only a router (by its label or maker) is a vehicle router: whose
                    // vehicle is read from its name and the equipment beside it, like an unflagged one.
                    val routerOnly = (ROUTER_WORDS.containsMatchIn(best.label) || FLEET_VENDOR.containsMatchIn(v)) &&
                        best.category == Category.PUBLIC_SAFETY && !POLICE_SPECIFIC.containsMatchIn(best.label)
                    return if (routerOnly) vehicle(name, nearbyEms, null) else Reading(PRIORITY)
                }
                return Reading(THREAT)
            }
            if (isEmsVendor(v)) return Reading(EMERGENCY, "EMS / Fire equipment")
            if (FLEET_VENDOR.containsMatchIn(v)) return vehicle(name, nearbyEms, null)
            if (TELEMATICS_VENDOR.containsMatchIn(v)) return Reading(FLEET, "Telematics")
            if (TRANSIT_VENDOR.containsMatchIn(v)) return Reading(FLEET, "Transit / School bus")
            if (COMMERCIAL_TYPE.containsMatchIn(type) || COMMERCIAL_VENDOR.containsMatchIn(v) ||
                name?.let { COMMERCIAL_SSID.containsMatchIn(it) } == true) return Reading(COMMERCIAL)
            return Reading(RESIDENTIAL)
        }

        /** A vehicle's router: Emergency when its name or the equipment beside it says so, a bus by its name, else Fleet. */
        private fun vehicle(name: String?, nearbyEms: String?, fallback: String?): Reading = when {
            name != null && EMS_NAME.containsMatchIn(name) -> Reading(EMERGENCY, "EMS / Fire vehicle", "network named \"$name\"")
            nearbyEms != null -> Reading(EMERGENCY, "EMS / Fire vehicle", "$nearbyEms equipment beside it")
            name != null && TRANSIT_NAME.containsMatchIn(name) -> Reading(FLEET, "Transit / Bus", "network named \"$name\"")
            else -> Reading(FLEET, fallback ?: FLEET.detail)
        }

        /**
         * The category line of a Tactical entry: "Priority / LE / Public Safety · Axon body camera",
         * "Emergency / EMS / Fire vehicle · ZOLL equipment beside it", "Fleet / Telematics · Samsara".
         */
        fun describe(r: Reading, best: Hit?, vendor: String?): String = when {
            r.because != null -> "${r.label} · ${r.because}"
            best != null && (r.affiliation == PRIORITY || r.affiliation == FLEET) -> "${r.label} · ${best.label}"
            best != null -> "${r.affiliation.title} / ${best.label}"
            !vendor.isNullOrBlank() -> "${r.label} · ${vendor.take(28)}"
            else -> r.label
        }
    }
}
