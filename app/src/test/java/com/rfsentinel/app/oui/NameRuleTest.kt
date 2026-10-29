package com.rfsentinel.app.oui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** "is:" (whole name) and "re:" (pattern) rules on device names and SSIDs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NameRuleTest {
    private val context: Application get() = ApplicationProvider.getApplicationContext()

    @Before
    fun noPresets() = OuiWatchlist.setEnabledPresets(context, emptySet())

    @After
    fun clear() = OuiWatchlist.allEntries().forEach { OuiWatchlist.removeCustomEntry(context, it.prefix) }

    private fun watch(key: String) = OuiWatchlist.addCustomEntry(context, OuiEntry(key, "Mine", "user-added", "custom"))
    private fun hits(name: String) = OuiWatchlist.hits("C1:11:22:33:44:55", name, emptyList())

    @Test
    fun keysNormalizeAndValidate() {
        assertEquals("is:PD-UNIT-12", OuiWatchlist.normalizeKey(" IS: PD-UNIT-12 "))
        assertTrue(OuiWatchlist.isValidKey("is:PD-UNIT-12"))
        assertFalse(OuiWatchlist.isValidKey("is:  "))
        assertEquals("re: unit ", OuiWatchlist.normalizeKey("RE: unit "))
        assertTrue(OuiWatchlist.isValidKey("re:^AB3-[0-9A-F]{4}$"))
        assertFalse("doesn't compile", OuiWatchlist.isValidKey("re:(unclosed"))
        assertFalse(OuiWatchlist.isValidKey("re:" + "a".repeat(NamePattern.MAX_LENGTH + 1)))
        assertTrue(OuiEntry("is:x", "x").isExactRule && OuiEntry("is:x", "x").isTextRule)
        assertTrue(OuiEntry("re:x", "x").isPatternRule && !OuiEntry("re:x", "x").isNameRule)
    }

    @Test
    fun exactMatchesTheWholeNameOnly() {
        watch("is:PD-UNIT-12")
        assertEquals(1, hits("pd-unit-12").size)
        assertEquals("surrounding spaces don't count", 1, hits(" PD-UNIT-12 ").size)
        assertTrue(hits("PD-UNIT-123").isEmpty())
        assertTrue(hits("MY PD-UNIT-12").isEmpty())
    }

    @Test
    fun patternMatchesAnywhereUnlessAnchored() {
        watch("re:^AB3-[0-9A-F]{4}$")
        watch("re:patrol ?\\d+")
        assertEquals(1, hits("ab3-1F2e").size)
        assertTrue(hits("AB3-1F2E-X").isEmpty())
        val h = hits("Unit Patrol 7")
        assertEquals(1, h.size)
        assertTrue(h[0].evidence, h[0].evidence.contains("patrol ?\\d+"))
        assertEquals(OuiEntry.YOURS, h[0].source)
    }

    @Test
    fun anAddressRuleIsntMistakenForAPattern() {
        watch("re:C1:11")
        assertTrue("re: rules only look at the name", OuiWatchlist.hits("C1:11:22:33:44:55", "x", emptyList()).isEmpty())
    }

    @Test
    fun aRunawayPatternGivesUpInsteadOfHanging() {
        val p = assertNotNullPattern("(a+)+$")
        val start = System.nanoTime()
        assertFalse(p.matches("a".repeat(40) + "!"))
        assertTrue("took ${(System.nanoTime() - start) / 1_000_000} ms", System.nanoTime() - start < 2_000_000_000L)
        assertTrue("an ordinary match still works", p.matches("aaa"))
    }

    @Test
    fun problemsAreExplained() {
        assertNull(NamePattern.problem("^Flock-"))
        assertTrue(NamePattern.problem("(")!!.startsWith("Not a valid pattern"))
        assertEquals("Enter a pattern", NamePattern.problem(" "))
    }

    private fun assertNotNullPattern(s: String) = NamePattern.compile(s).also { assertNotNull(it) }!!
}
