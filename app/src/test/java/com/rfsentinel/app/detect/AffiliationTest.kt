package com.rfsentinel.app.detect

import com.rfsentinel.app.detect.Affiliation.Companion.classify
import com.rfsentinel.app.oui.OuiEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class AffiliationTest {
    private fun hit(c: Category, label: String, source: String = "IEEE registry") = Hit(c, label, 80, "x", source)

    @Test
    fun policeEquipmentIsPriority() {
        assertEquals(Affiliation.PRIORITY, classify(hit(Category.BODY_CAM, "Axon body camera"), "Axon Enterprise", "Body camera", "AB3-1234"))
        assertEquals(Affiliation.PRIORITY, classify(hit(Category.ALPR, "Flock Safety camera"), null, null, "Flock-1A2B"))
    }

    @Test
    fun aBareCellularRouterIsFleetButAPoliceOneStaysPriority() {
        assertEquals(Affiliation.FLEET, classify(hit(Category.PUBLIC_SAFETY, "Vehicle cellular router"), "Cradlepoint", null, null))
        assertEquals(Affiliation.FLEET, classify(hit(Category.PUBLIC_SAFETY, "In-vehicle gateway"), "Sierra Wireless", null, null))
        assertEquals(Affiliation.PRIORITY, classify(hit(Category.PUBLIC_SAFETY, "Axon Fleet in-car router"), "Cradlepoint", null, null))
        assertEquals("unflagged fleet router maker", Affiliation.FLEET, classify(null, "Inseego Wireless, Inc", "Router", "MiFi"))
    }

    @Test
    fun threatsAndYourOwnRules() {
        assertEquals(Affiliation.THREAT, classify(hit(Category.TRACKER, "Apple AirTag"), "Apple", null, null))
        assertEquals(Affiliation.THREAT, classify(hit(Category.GLASSES, "Ray-Ban Meta"), null, null, null))
        assertEquals(Affiliation.CUSTOM, classify(hit(Category.BODY_CAM, "Mine", OuiEntry.YOURS), null, null, null))
    }

    @Test
    fun infrastructureIsCommercialAndTheRestResidential() {
        assertEquals(Affiliation.COMMERCIAL, classify(null, "Cisco Meraki", "Router", "CorpNet"))
        assertEquals(Affiliation.COMMERCIAL, classify(null, "Some Vendor", "Business access point", "x"))
        assertEquals(Affiliation.COMMERCIAL, classify(null, null, "WiFi network", "Starbucks WiFi"))
        assertEquals(Affiliation.COMMERCIAL, classify(null, null, "iBeacon", null))
        assertEquals(Affiliation.RESIDENTIAL, classify(null, "NETGEAR", "Router", "NETGEAR42"))
        assertEquals(Affiliation.RESIDENTIAL, classify(null, "Apple, Inc.", "AirPods", null))
        assertEquals(Affiliation.RESIDENTIAL, classify(null, null, null, null))
    }

    @Test
    fun possiblePoliceRoutersAreFleet() {
        assertEquals(Affiliation.FLEET,
            classify(hit(Category.PUBLIC_SAFETY, "Vehicle cellular router in a possible police vehicle"), "Cradlepoint", null, "IBR900-21A"))
    }

    @Test
    fun emergencyFromEquipmentNamesAndNeighbours() {
        assertEquals("a defibrillator itself", Affiliation.EMERGENCY, classify(null, "Physio-Control, Inc", null, "LP15"))
        assertEquals(Affiliation.EMERGENCY, classify(null, "Stryker Corporation", null, null))
        val r = Affiliation.read(null, "Cradlepoint, Inc", "Router", "MEDIC 12")
        assertEquals(Affiliation.EMERGENCY, r.affiliation)
        assertEquals("Emergency / EMS / Fire vehicle", r.label)
        assertEquals(Affiliation.EMERGENCY, classify(null, "Cradlepoint", null, "ENGINE41"))
        assertEquals("equipment beside it", Affiliation.EMERGENCY, classify(null, "Sierra Wireless", null, null, nearbyEms = "ZOLL"))
        assertEquals("flagged router with EMS beside it",
            Affiliation.EMERGENCY, classify(hit(Category.PUBLIC_SAFETY, "Vehicle cellular router"), "Cradlepoint", null, null, nearbyEms = "Stryker"))
        assertEquals("police kit stays Priority whatever is beside it",
            Affiliation.PRIORITY, classify(hit(Category.BODY_CAM, "Axon body camera"), null, null, null, nearbyEms = "ZOLL"))
        assertEquals("squad is ambiguous", Affiliation.FLEET, classify(null, "Cradlepoint", null, "SQUAD 7"))
    }

    @Test
    fun telematicsAndTransitAreFleet() {
        assertEquals("Fleet / Telematics", Affiliation.read(null, "Samsara Networks Inc", null, null).label)
        assertEquals("Fleet / Telematics", Affiliation.read(null, "Geotab Inc.", null, null).label)
        assertEquals("Fleet / Transit / School bus", Affiliation.read(null, "Zonar Systems", null, null).label)
        assertEquals("Fleet / Transit / Bus", Affiliation.read(null, "Cradlepoint", null, "BUS 1402").label)
    }

    @Test
    fun theCategoryLineReadsLikeAFieldLog() {
        val axon = hit(Category.BODY_CAM, "Axon body camera")
        fun line(best: Hit?, vendor: String?, name: String? = null, ems: String? = null) =
            Affiliation.describe(Affiliation.read(best, vendor, null, name, ems), best, vendor)
        assertEquals("Priority / LE / Public Safety · Axon body camera", line(axon, "Axon"))
        assertEquals("Threat / Apple AirTag", line(hit(Category.TRACKER, "Apple AirTag"), "Apple"))
        assertEquals("Fleet / Cellular Router · Cradlepoint", line(null, "Cradlepoint"))
        assertEquals("Emergency / EMS / Fire vehicle · ZOLL equipment beside it", line(null, "Cradlepoint", ems = "ZOLL"))
        assertEquals("Residential / Consumer", line(null, null))
    }
}
