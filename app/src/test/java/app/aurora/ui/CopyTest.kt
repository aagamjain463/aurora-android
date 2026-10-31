package app.aurora.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.aurora.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/** The founder-approved wording, exactly, from the one place copy lives. */
@RunWith(AndroidJUnit4::class)
class CopyTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun approvedStringsAreExact() {
        val approved = mapOf(
            R.string.today_headline to "Your phone. Your rules.",
            R.string.today_description to "Reclaim your time and own your attention.",
            R.string.today_metric_label to "Time Reclaimed",
            R.string.track_headline to "See your progress. Own your growth.",
            R.string.home_ready_title to "Custom Sessions",
            R.string.shield_library_headline to "Shield yourself from distractions.",
            R.string.intent_tab_title to "Use with purpose. Not impulse.",
            R.string.intent_step_pause_body to "Pause for a moment to decide if you actually want to use the app you opened.",
            R.string.intent_step_use_body to "Use it intentionally for the time you actually want to.",
            R.string.protect_headline to "Never get distracted from what matters.",
            R.string.mode_flexible_desc to "Blocked until your timer ends, or you turn it off.",
            R.string.mode_friction_desc to "Complete a challenge before getting access.",
            R.string.mode_cooldown_desc to "Wait before you can open this app.",
            R.string.mode_always_block_desc to "No in-app early exit. Protection ends when your timer does.",
            R.string.always_block_confirm to "I'm ready to commit",
        )
        approved.forEach { (id, text) -> assertEquals(text, context.getString(id)) }
    }

    @Test
    fun lockdownIsGoneFromUserFacingCopy() {
        val fields = R.string::class.java.fields
        for (f in fields) {
            val text = context.getString(f.getInt(null))
            assertFalse("${f.name}: $text", text.contains("Lockdown", ignoreCase = true))
            assertFalse("${f.name}: $text", text.contains("moment of awareness", ignoreCase = true))
            assertFalse("${f.name}: $text", text.contains("configuration", ignoreCase = true))
        }
    }
}
