package app.aurora.protect

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.aurora.AuroraApplication
import app.aurora.plan.ProtectedItem
import app.aurora.ui.protect.AppPickerScreen
import app.aurora.ui.theme.AuroraTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class ProtectLimitTest {
    @get:Rule val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<AuroraApplication>()

    @Before
    fun installOneAppAndProtectFive() = runBlocking {
        // Installed, so Protect doesn't prune them as uninstalled.
        (1..6).forEach { i ->
            val activity = ComponentName("com.example.app$i", "com.example.app$i.Main")
            shadowOf(app.packageManager).apply {
                installPackage(
                    PackageInfo().apply {
                        packageName = activity.packageName
                        applicationInfo = ApplicationInfo().apply { packageName = activity.packageName }
                    },
                )
                addActivityIfNotPresent(activity)
                addIntentFilterForActivity(activity, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
            }
        }
        (1..5).forEach { app.protection.add(ProtectedItem.App.of("com.example.app$it")!!, app.plan) }
    }

    @Test
    fun theSixthAppOnFreeExplainsTheLimitAndAddsNothing() {
        compose.setContent { AuroraTheme(darkTheme = false, reduceMotion = true) { AppPickerScreen(onBack = {}, onPlus = {}) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("com.example.app1").fetchSemanticsNodes().isNotEmpty() }
        val list = compose.onNode(hasScrollAction())
        list.performScrollToNode(hasText("com.example.app6"))
        compose.onNodeWithText("com.example.app6").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("You're protecting 5 items, the most Aurora Free allows.", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("You're protecting 5 items, the most Aurora Free allows.", substring = true).assertIsDisplayed()
        assertEquals(5, runBlocking { app.protection.list.first().items.size })
    }
}
