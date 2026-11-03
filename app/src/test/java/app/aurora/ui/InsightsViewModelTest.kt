package app.aurora.ui

import android.app.AppOpsManager
import android.os.Looper
import android.os.Process
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.aurora.AuroraApplication
import app.aurora.ui.insights.InsightsViewModel
import app.aurora.ui.insights.Period
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class InsightsViewModelTest {
    @Test
    fun changingPeriodLoadsBeforePublishingItsRecords() {
        val app = ApplicationProvider.getApplicationContext<AuroraApplication>()
        shadowOf(app.getSystemService(AppOpsManager::class.java)).setMode(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.packageName, AppOpsManager.MODE_ALLOWED,
        )
        val vm = InsightsViewModel(app)
        try {
            vm.refresh()
            await { !vm.state.value.loading }
            assertEquals(1, vm.state.value.days.size)

            vm.select(Period.Week)
            assertEquals(Period.Week, vm.state.value.period)
            assertTrue("The old period's records must not appear ready", vm.state.value.loading)
            await { !vm.state.value.loading }
            assertEquals(Period.Week, vm.state.value.period)
            assertEquals(7, vm.state.value.days.size)

            vm.select(Period.Month)
            vm.select(Period.Week)
            vm.select(Period.Today)
            await { vm.viewModelScope.coroutineContext[Job]!!.children.none() }
            assertTrue(!vm.state.value.loading)
            assertEquals(Period.Today, vm.state.value.period)
            assertEquals(1, vm.state.value.days.size)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Usage did not finish loading" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(2)
        }
    }
}
