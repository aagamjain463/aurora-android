package app.aurora.protect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four Shield modes, Intentional App Opening and every precedence rule between them
 * (docs/PRODUCT.md "Rule precedence"). Times are plain numbers: minutes from 0.
 */
class PolicyTest {
    private val min = 60_000L
    private val ig = "com.instagram.android"

    private fun r(reason: ShieldReason, from: Long = 0, to: Long = 120 * min, id: String = reason.name, wait: Long = 45 * min) =
        Restriction(reason, setOf(ig), from, to, id, cooldownWaitMs = wait)

    private fun state(vararg restrictions: Restriction) = PolicyState(
        protectedPackages = setOf(ig, "com.youtube"),
        exempt = SystemExempt + setOf("com.launcher", "app.aurora", "com.dialer"),
        restrictions = restrictions.toList(),
    )

    private val intent = mapOf(ig to IntentConfig(reason = "Study", reflectionSeconds = 15))

    // ---- Basics ----

    @Test
    fun nothingRunningMeansNothingBlocked() {
        // A saved app is a preference, not protection: with no session it opens normally.
        assertEquals(Decision.Allow, decide(ig, 0, state()))
        assertEquals(Decision.Allow, decide("com.mail", 0, state()))
    }

    @Test
    fun exemptionsAlwaysWin() {
        val all = state(r(ShieldReason.AlwaysBlock).copy(keys = setOf("com.dialer", "com.android.settings")))
        for (pkg in listOf("app.aurora", "com.launcher", "com.android.settings", "com.android.systemui", "com.android.phone", "com.dialer")) {
            assertEquals(pkg, Decision.Exempt, decide(pkg, min, all))
        }
    }

    @Test
    fun unknownConfigurationAndMissingPackagesNeverShield() {
        assertEquals(Decision.Unavailable, decide(ig, 0, null))
        assertEquals(Decision.Invalid, decide(null, 0, state()))
        assertEquals(Decision.Invalid, decide(" ", 0, state()))
    }

    // ---- Flexible: full block, no way through from the Shield, ends with its timer ----

    @Test
    fun flexibleBlocksCompletelyUntilItsTimerEnds() {
        val s = state(r(ShieldReason.Flexible, 0, 120 * min))
        val d = decide(ig, 30 * min, s) as Decision.Shield
        assertEquals(ShieldReason.Flexible, d.reason)
        assertEquals(120 * min, d.until)
        assertFalse(ShieldReason.Flexible.canContinue)
        assertEquals(Decision.Allow, decide(ig, 120 * min, s)) // expired
    }

    @Test
    fun flexibleIgnoresEveryGrant() {
        val s = state(r(ShieldReason.Flexible)).copy(
            access = mapOf(ig to Grant(100 * min, Mode.Cooldown)),
            intents = intent,
            intentUses = mapOf(ig to IntentUse(30 * min, runningSince = 0)),
        )
        assertEquals(ShieldReason.Flexible, (decide(ig, min, s) as Decision.Shield).reason)
    }

    // ---- Friction: a task, then timed access only ----

    @Test
    fun frictionAsksForATaskThenAllowsOnlyTheChosenTime() {
        val s = state(r(ShieldReason.Friction, 0, 180 * min))
        assertEquals(ShieldReason.Friction, (decide(ig, min, s) as Decision.Shield).reason)
        assertTrue(ShieldReason.Friction.canContinue)
        val granted = s.copy(access = mapOf(ig to Grant(16 * min, Mode.Friction)))
        assertEquals(Decision.TemporarilyAllowed(16 * min), decide(ig, min, granted))
        // The grant ends: the task is needed again.
        assertEquals(ShieldReason.Friction, (decide(ig, 16 * min, granted) as Decision.Shield).reason)
    }

    // ---- Cooldown: wait before access, then timed access ----

    @Test
    fun cooldownWaitsBeforeAccessWithACustomFortyFiveMinutes() {
        val cd = r(ShieldReason.Cooldown, 0, 240 * min, id = "s1", wait = 45 * min)
        val s = state(cd)
        // First attempt: no wait running yet.
        assertNull((decide(ig, min, s) as Decision.Shield).readyAt)
        assertEquals(45 * min, cd.cooldownWaitMs)
        // A wait is running: reopening shows the same countdown, never a fresh one.
        val waiting = s.copy(cooldownWaits = mapOf(ig to CooldownWait(46 * min, "s1")))
        assertEquals(46 * min, (decide(ig, 20 * min, waiting) as Decision.Shield).readyAt)
        assertEquals(46 * min, (decide(ig, 40 * min, waiting) as Decision.Shield).readyAt)
        // After the wait, access is a chosen, timed grant.
        val granted = waiting.copy(cooldownWaits = emptyMap(), access = mapOf(ig to Grant(56 * min, Mode.Cooldown)))
        assertEquals(Decision.TemporarilyAllowed(56 * min), decide(ig, 50 * min, granted))
        // When it ends a fresh wait is required.
        assertNull((decide(ig, 56 * min, granted) as Decision.Shield).readyAt)
    }

    @Test
    fun aCooldownWaitBelongsToOneRestrictionOnly() {
        val s = state(r(ShieldReason.Cooldown, id = "window-2")).copy(cooldownWaits = mapOf(ig to CooldownWait(5 * min, "window-1")))
        assertNull((decide(ig, min, s) as Decision.Shield).readyAt)
    }

    @Test
    fun cooldownEndsWithItsProtectionWindowEvenMidWait() {
        val s = state(r(ShieldReason.Cooldown, 0, 30 * min, id = "s1")).copy(cooldownWaits = mapOf(ig to CooldownWait(45 * min, "s1")))
        assertEquals(Decision.Allow, decide(ig, 30 * min, s))
    }

    // ---- Strict: strongest, nothing lifts it ----

    @Test
    fun alwaysBlockOverridesEverything() {
        val s = state(r(ShieldReason.AlwaysBlock, 0, 30 * min), r(ShieldReason.Flexible), r(ShieldReason.Friction), r(ShieldReason.Cooldown)).copy(
            access = mapOf(ig to Grant(60 * min, Mode.Cooldown)),
            intents = intent,
            intentUses = mapOf(ig to IntentUse(60 * min)),
        )
        assertEquals(Decision.Shield(ig, ShieldReason.AlwaysBlock, 30 * min, r(ShieldReason.AlwaysBlock, 0, 30 * min)), decide(ig, min, s))
        // Then the next strongest takes over.
        assertEquals(ShieldReason.Flexible, (decide(ig, 30 * min, s) as Decision.Shield).reason)
    }

    // ---- Precedence between the rest ----

    @Test
    fun strengthOrderIsDeterministic() {
        val order = listOf(ShieldReason.AlwaysBlock, ShieldReason.Flexible, ShieldReason.Focus, ShieldReason.Limit, ShieldReason.Cooldown, ShieldReason.Friction, ShieldReason.IntentCooldown, ShieldReason.Intent, ShieldReason.Locked)
        assertEquals(order, ShieldReason.entries.toList())
        val s = state(r(ShieldReason.Friction), r(ShieldReason.Cooldown), r(ShieldReason.Focus))
        assertEquals(ShieldReason.Focus, (decide(ig, min, s) as Decision.Shield).reason)
    }

    @Test
    fun aFrictionGrantNeverLiftsCooldownButACooldownGrantLiftsFriction() {
        val both = state(r(ShieldReason.Friction), r(ShieldReason.Cooldown))
        val frictionGrant = both.copy(access = mapOf(ig to Grant(30 * min, Mode.Friction)))
        assertEquals(ShieldReason.Cooldown, (decide(ig, min, frictionGrant) as Decision.Shield).reason)
        val friction = state(r(ShieldReason.Friction)).copy(access = mapOf(ig to Grant(30 * min, Mode.Cooldown)))
        assertEquals(Decision.TemporarilyAllowed(30 * min), decide(ig, min, friction))
    }

    @Test
    fun aSpentDailyLimitBeatsGrantsAndIntentUntilMidnight() {
        val s = state(r(ShieldReason.Friction)).copy(
            limits = mapOf(ig to 10 * min), usedToday = mapOf(ig to 10 * min), midnight = 600 * min,
            access = mapOf(ig to Grant(100 * min, Mode.Cooldown)), intents = intent,
        )
        assertEquals(Decision.Shield(ig, ShieldReason.Limit, 600 * min), decide(ig, min, s))
    }

    @Test
    fun aGrantEndsNoLaterThanTheRestrictionItLifts() {
        val s = state(r(ShieldReason.Friction, 0, 20 * min)).copy(access = mapOf(ig to Grant(60 * min, Mode.Friction)))
        assertEquals(Decision.TemporarilyAllowed(20 * min), decide(ig, min, s))
    }

    // ---- Intentional App Opening: the seven founder scenarios ----

    /** Scenario 1: Intent on, nothing stronger: Intent asks, and a chosen time allows. */
    @Test
    fun scenario1IntentAsksThenAllowsTheChosenTime() {
        val s = state().copy(intents = intent)
        assertEquals(Decision.Shield(ig, ShieldReason.Intent, intent = intent.getValue(ig)), decide(ig, 0, s))
        val using = s.copy(intentUses = mapOf(ig to IntentUse(15 * min, runningSince = 0)))
        assertEquals(Decision.TemporarilyAllowed(15 * min), decide(ig, 0, using))
    }

    /** Scenario 2: Flexible and Intent: Flexible blocks; Intent can't bypass it. */
    @Test
    fun scenario2FlexibleBeatsIntent() {
        val s = state(r(ShieldReason.Flexible)).copy(intents = intent, intentUses = mapOf(ig to IntentUse(15 * min)))
        assertEquals(ShieldReason.Flexible, (decide(ig, min, s) as Decision.Shield).reason)
    }

    /** Scenario 3: Friction and Intent: the task is required; an Intent session is no bypass. */
    @Test
    fun scenario3FrictionCannotBeBypassedByIntent() {
        val s = state(r(ShieldReason.Friction)).copy(intents = intent, intentUses = mapOf(ig to IntentUse(15 * min, runningSince = 0)))
        assertEquals(ShieldReason.Friction, (decide(ig, min, s) as Decision.Shield).reason)
        // After the task, its grant covers Intent too: no second prompt.
        assertTrue(decide(ig, min, s.copy(access = mapOf(ig to Grant(10 * min, Mode.Friction)))) is Decision.TemporarilyAllowed)
    }

    /** Scenario 4: an Intent session with a reminder, then Strict starts while the app is open. */
    @Test
    fun scenario4AlwaysBlockStartingMidSessionWinsAtOnce() {
        val s = state(r(ShieldReason.AlwaysBlock, 5 * min, 35 * min)).copy(
            intents = intent, intentUses = mapOf(ig to IntentUse(15 * min, runningSince = 0, expiry = IntentExpiry.Reminder)),
        )
        assertTrue(decide(ig, 4 * min, s) is Decision.TemporarilyAllowed)
        assertEquals(ShieldReason.AlwaysBlock, (decide(ig, 5 * min, s) as Decision.Shield).reason)
        // The interceptor re-checks at the restriction's start while the app is open.
        assertEquals(5 * min, nextChange(ig, min, s))
    }

    /** Scenario 5: an active post-usage cooldown blocks until it ends. */
    @Test
    fun scenario5PostUsageCooldownBlocksUntilItEnds() {
        val s = state().copy(intents = intent, intentCooldowns = mapOf(ig to 60 * min))
        assertEquals(Decision.Shield(ig, ShieldReason.IntentCooldown, 60 * min), decide(ig, 15 * min, s))
        assertFalse(ShieldReason.IntentCooldown.canContinue)
    }

    /** Scenario 6: a valid Intent session, then a stronger schedule window begins. */
    @Test
    fun scenario6AScheduleSuspendsAnIntentSession() {
        val window = Restriction(ShieldReason.Flexible, setOf(ig), 10 * min, 60 * min, "schedule:s:1", scheduleId = "s")
        val s = state(window).copy(intents = intent, intentUses = mapOf(ig to IntentUse(30 * min, runningSince = 0)))
        assertTrue(decide(ig, 9 * min, s) is Decision.TemporarilyAllowed)
        assertEquals(ShieldReason.Flexible, (decide(ig, 10 * min, s) as Decision.Shield).reason)
    }

    /** Scenario 7: the cooldown expires and the next opening is a fresh decision, never unlimited access. */
    @Test
    fun scenario7AfterCooldownTheNextOpeningIsAFreshDecision() {
        val s = state().copy(intents = intent, intentCooldowns = mapOf(ig to 60 * min))
        assertEquals(Decision.Shield(ig, ShieldReason.Intent, intent = intent.getValue(ig)), decide(ig, 60 * min, s))
    }

    @Test
    fun intentTimeUpWithReminderOrCooldown() {
        val reminder = state().copy(intents = intent, intentUses = mapOf(ig to IntentUse(15 * min, usedMs = 15 * min, expiry = IntentExpiry.Reminder)))
        assertEquals(Decision.Shield(ig, ShieldReason.Intent, timeUp = true, intent = intent.getValue(ig)), decide(ig, 20 * min, reminder))
        val cooldown = state().copy(intents = intent, intentUses = mapOf(ig to IntentUse(15 * min, usedMs = 15 * min, expiry = IntentExpiry.Cooldown, cooldownMinutes = 45)))
        assertEquals(Decision.Shield(ig, ShieldReason.IntentCooldown, 20 * min + 45 * min), decide(ig, 20 * min, cooldown))
    }

    @Test
    fun onlyForegroundTimeCountsTowardTheChosenTime() {
        // 15 minutes chosen; 10 used earlier, in front again since minute 100.
        val use = IntentUse(15 * min, usedMs = 10 * min, runningSince = 100 * min)
        assertEquals(12 * min, use.used(102 * min))
        assertEquals(3 * min, use.left(102 * min))
        // In the background (not running), time doesn't pass for it.
        val away = use.copy(usedMs = 12 * min, runningSince = null)
        assertEquals(3 * min, away.left(500 * min))
        // nextChange only schedules the boundary while it's in front.
        val s = state().copy(intents = intent, intentUses = mapOf(ig to use))
        assertEquals(105 * min, nextChange(ig, 102 * min, s))
        assertNull(nextChange(ig, 102 * min, s.copy(intentUses = mapOf(ig to away))))
    }

    @Test
    fun nextChangeFindsTheEarliestBoundary() {
        val s = state(r(ShieldReason.Cooldown, 10 * min, 100 * min)).copy(
            access = mapOf(ig to Grant(40 * min, Mode.Cooldown)),
            cooldownWaits = mapOf(ig to CooldownWait(30 * min, "x")),
        )
        assertEquals(10 * min, nextChange(ig, 0, s))
        assertEquals(30 * min, nextChange(ig, 10 * min, s))
        assertEquals(40 * min, nextChange(ig, 30 * min, s))
        assertNull(nextChange("com.mail", 0, s))
    }

    // ---- Strict: Settings and installers are locked while a commitment runs ----

    @Test
    fun strictCommitmentLocksSettingsUntilItEnds() {
        val settings = "com.android.settings"
        for (reason in listOf(ShieldReason.AlwaysBlock, ShieldReason.Cooldown, ShieldReason.Friction)) {
            val s = state(r(reason, 0, 120 * min).copy(locks = true)).copy(locked = setOf(settings))
            val d = decide(settings, 30 * min, s) as Decision.Shield
            assertEquals(ShieldReason.Locked, d.reason)
            assertEquals(120 * min, d.until)
            assertFalse(ShieldReason.Locked.canContinue)
            assertEquals(Decision.Exempt, decide(settings, 120 * min, s))
        }
        // Flexible never locks anything, and calls stay exempt during a commitment.
        val flexible = state(r(ShieldReason.Flexible)).copy(locked = setOf(settings))
        assertEquals(Decision.Exempt, decide(settings, 30 * min, flexible))
        val strict = state(r(ShieldReason.AlwaysBlock).copy(locks = true)).copy(locked = setOf(settings))
        assertEquals(Decision.Exempt, decide("com.android.phone", 30 * min, strict))
    }

    /** Settings stays open during a commitment; only its Accessibility and Date & time pages are locked. */
    @Test
    fun strictCommitmentLocksOnlyAccessibilityAndDateTimePages() {
        val settings = "com.android.settings"
        val s = state(r(ShieldReason.Cooldown, 0, 120 * min).copy(locks = true)).copy(exempt = setOf(settings))
        assertEquals(Decision.Exempt, decide(settings, 30 * min, s))
        assertEquals(ShieldReason.Locked, (decide(settings, 30 * min, s, lockedScreen = true) as Decision.Shield).reason)
        assertEquals(Decision.Exempt, decide(settings, 120 * min, s, lockedScreen = true))
        assertEquals(Decision.Exempt, decide(settings, 30 * min, state(r(ShieldReason.Flexible)).copy(exempt = setOf(settings)), lockedScreen = true))

        val screens = LockedScreens(setOf(settings), setOf("accessibility", "date & time", "aurora shield"))
        assertTrue(screens.matches(settings, "com.android.settings.SubSettings", listOf(" Accessibility ")))
        assertTrue(screens.matches(settings, "com.android.settings.SubSettings", listOf("Aurora Shield")))
        assertTrue(screens.matches(settings, "com.android.settings.Settings\$DateTimeSettingsActivity", emptyList()))
        assertFalse(screens.matches(settings, "com.android.settings.SubSettings", listOf("Wi-Fi")))
        assertFalse(screens.matches(settings, "com.android.settings.Settings", listOf("Settings")))
        assertFalse(screens.matches("com.other", "com.other.AccessibilityActivity", listOf("Accessibility")))
    }
}
