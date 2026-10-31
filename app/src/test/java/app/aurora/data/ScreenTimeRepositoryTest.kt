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
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowUsageStatsManager
import java.time.LocalDate
import java.time.ZoneId

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

    private fun grantedContext(): Context {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context.getSystemService(AppOpsManager::class.java)).setMode(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName, AppOpsManager.MODE_ALLOWED,
        )
        return context
    }

    private fun Context.event(pkg: String, at: Long, type: Int) = shadowOf(getSystemService(UsageStatsManager::class.java)).addEvent(
        ShadowUsageStatsManager.EventBuilder.buildEvent().setPackage(pkg).setTimeStamp(at).setEventType(type).build(),
    )

    @Test
    fun anAppOpenAcrossMidnightCountsFromMidnight() = runBlocking {
        val context = grantedContext()
        val date = LocalDate.of(2026, 10, 20)
        val midnight = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        context.event("com.example.video", midnight - 600_000, UsageEvents.Event.ACTIVITY_RESUMED)
        val day = ScreenTimeRepository(context).day(date, now = midnight + 1_200_000)
        assertEquals(mapOf("com.example.video" to 1_200_000L), day)
    }

    @Test
    fun aFinishedDayWithNoRecordsIsUnknownEvenWhenDstMakesItShort() = runBlocking {
        val context = grantedContext()
        val zone = ZoneId.systemDefault()
        val date = LocalDate.of(2026, 10, 20)
        // The evening before only seeds; it says nothing about this day.
        context.event("com.example.video", date.atStartOfDay(zone).toInstant().toEpochMilli() - 600_000, UsageEvents.Event.ACTIVITY_PAUSED)
        val repository = ScreenTimeRepository(context)
        assertNull(repository.day(date, now = date.plusDays(3).atStartOfDay(zone).toInstant().toEpochMilli()))
        // Today so far with nothing yet is a quiet morning, not unknown.
        assertEquals(emptyMap<String, Long>(), repository.day(date, now = date.atTime(1, 0).atZone(zone).toInstant().toEpochMilli()))
    }
}
