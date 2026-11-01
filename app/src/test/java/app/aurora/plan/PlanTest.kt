package app.aurora.plan

import app.aurora.plan.Allowance.ActiveGoals
import app.aurora.plan.Allowance.CommittedMinutesPerDay
import app.aurora.plan.Allowance.IntentApps
import app.aurora.plan.Allowance.ProtectedItems
import app.aurora.plan.Plan.Free
import app.aurora.plan.Plan.Plus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanTest {

    private fun app(pkg: String) = ProtectedItem.App.of(pkg)!!
    private fun site(host: String) = ProtectedItem.Website.of(host)!!

    // Allowances

    @Test
    fun freeLimitsMatchTheBusinessModel() {
        assertEquals(
            mapOf(ProtectedItems to 5, IntentApps to 3, ActiveGoals to 1, CommittedMinutesPerDay to 120),
            Allowance.entries.associateWith { limit(Free, it) },
        )
    }

    @Test
    fun plusIsUnlimitedForEveryAllowance() {
        for (allowance in Allowance.entries) {
            assertNull(allowance.name, limit(Plus, allowance))
            assertTrue(allowance.name, canAdd(Plus, allowance, 10_000))
        }
    }

    @Test
    fun freeCanAddBelowEachLimitButNotAtOrAbove() {
        for ((allowance, max) in FreeLimits) {
            assertTrue(allowance.name, canAdd(Free, allowance, max - 1))
            assertFalse(allowance.name, canAdd(Free, allowance, max))
            assertFalse(allowance.name, canAdd(Free, allowance, max + 1))
        }
    }

    // Protected items

    @Test
    fun freeProtectsUpToFiveDistinctItems() {
        val four = listOf(app("com.a.one"), app("com.a.two"), app("com.a.three"), site("example.com"))
        assertTrue(canProtect(Free, four, app("com.a.five")))
        val five = four + app("com.a.five")
        assertFalse(canProtect(Free, five, app("com.a.six")))
        assertFalse(canProtect(Free, five, site("news.example")))
    }

    @Test
    fun appsAndWebsitesShareOnePool() {
        val mixed = listOf(app("com.a.one"), app("com.a.two"), site("one.example"), site("two.example"), site("three.example"))
        assertFalse(canProtect(Free, mixed, app("com.a.three")))
        assertFalse(canProtect(Free, mixed, site("four.example")))
    }

    @Test
    fun theSameItemInSeveralRulesCountsOnce() {
        // Five distinct items, each referenced by more than one rule.
        val acrossRules = listOf(
            app("com.a.one"), app("com.a.two"), site("example.com"), app("com.a.three"),
            app("com.a.one"), app("com.a.two"), site("EXAMPLE.com"), app("com.a.four"),
        )
        assertFalse(canProtect(Free, acrossRules, app("com.a.five")))
        // An item that is already protected never needs a new slot, even at the limit.
        assertTrue(canProtect(Free, acrossRules + app("com.a.five"), app("com.a.one")))
        val four = acrossRules.dropLast(1)
        assertTrue(canProtect(Free, four, app("com.a.five")))
    }

    @Test
    fun removingAnItemFreesASlotAtOnce() {
        val five = listOf(app("com.a.one"), app("com.a.two"), app("com.a.three"), app("com.a.four"), site("example.com"))
        assertFalse(canProtect(Free, five, app("com.a.six")))
        assertTrue(canProtect(Free, five - site("example.com"), app("com.a.six")))
    }

    @Test
    fun plusProtectsBeyondTheFreeLimit() {
        val twenty = (1..20).map { app("com.a.app$it") }
        assertTrue(canProtect(Plus, twenty, site("example.com")))
    }

    @Test
    fun websitesAreNormalised() {
        val expected = site("example.com")
        for (input in listOf("Example.COM", " example.com ", "example.com.", "www.example.com", "WWW.Example.com")) {
            assertEquals(input, expected, ProtectedItem.Website.of(input))
        }
        assertEquals("xn--bcher-kva.example", site("bücher.example").domain)
        assertEquals("m.example.com", site("m.example.com").domain)
    }

    @Test
    fun invalidItemsAreRejected() {
        for (input in listOf("", "  ", "localhost", "com", "https://example.com", "example.com/path", "example.com:443", "exa mple.com", "a..b", "under_score.com", "x".repeat(64) + ".com")) {
            assertNull(input, ProtectedItem.Website.of(input))
        }
        for (input in listOf("", "chrome", "com.", ".com.a", "1com.a", "com.a b", "com/a")) {
            assertNull(input, ProtectedItem.App.of(input))
        }
        assertEquals("com.Example.App", app(" com.Example.App ").packageName)
    }

    // Downgrade

    @Test
    fun downgradeKeepsTheOldestActiveUntilThePersonChooses() {
        val items = listOf("a", "b", "c", "d", "e", "f", "g") // oldest first
        assertEquals(listOf("a", "b", "c", "d", "e"), keepActive(Free, ProtectedItems, items))
        assertEquals(listOf("a"), keepActive(Free, ActiveGoals, items))
        assertEquals(items, keepActive(Plus, ProtectedItems, items))
    }

    @Test
    fun downgradeHonoursThePersonsChoice() {
        val items = listOf("a", "b", "c", "d", "e", "f", "g")
        assertEquals(listOf("a", "b", "c", "f", "g"), keepActive(Free, ProtectedItems, items, chosen = setOf("g", "f")))
        assertEquals(listOf("b", "d", "e"), keepActive(Free, IntentApps, items, chosen = listOf("b", "d", "e", "f")))
        // Choices that no longer exist are ignored.
        assertEquals(listOf("c"), keepActive(Free, ActiveGoals, listOf("c", "d"), chosen = setOf("zzz")))
        // Repeats count once.
        assertEquals(listOf("a", "b", "c"), keepActive(Free, IntentApps, listOf("a", "a", "b", "c", "d")))
    }
}
