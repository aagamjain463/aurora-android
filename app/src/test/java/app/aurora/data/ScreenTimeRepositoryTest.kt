package app.aurora.data

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowUsageStatsManager

@RunWith(AndroidJUnit4::class)
class ScreenTimeRepositoryTest {
    @Test
    fun pagedIntervalsSeedAnAlreadyOpenAppAcrossSeveralPages() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context.getSystemService(AppOpsManager::class.java)).setMode(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName, AppOpsManager.MODE_ALLOWED,
        )
        val from = 86_400_000L
        val to = from + 7_200_000L
        shadowOf(context.getSystemService(UsageStatsManager::class.java)).addEvent(
            ShadowUsageStatsManager.EventBuilder.buildEvent()
                .setPackage("com.example.video").setTimeStamp(from - 10_000)
                .setEventType(UsageEvents.Event.ACTIVITY_RESUMED).build(),
        )
        assertEquals(listOf(UsageInterval("com.example.video", from, to)), ScreenTimeRepository(context).intervals(from, to))
    }
}
