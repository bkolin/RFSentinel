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
    fun theCategoryLineReadsLikeAFieldLog() {
        val axon = hit(Category.BODY_CAM, "Axon body camera")
        assertEquals("Priority / LE / Public Safety · Axon body camera", Affiliation.describe(Affiliation.PRIORITY, axon, "Axon"))
        assertEquals("Threat / Apple AirTag", Affiliation.describe(Affiliation.THREAT, hit(Category.TRACKER, "Apple AirTag"), "Apple"))
        assertEquals("Fleet / Cellular Router · Cradlepoint", Affiliation.describe(Affiliation.FLEET, null, "Cradlepoint"))
        assertEquals("Residential / Consumer", Affiliation.describe(Affiliation.RESIDENTIAL, null, null))
    }
}
