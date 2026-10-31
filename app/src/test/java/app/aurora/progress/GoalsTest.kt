package app.aurora.progress

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** Goals must really succeed and really fail: the case for every state a person can see. */
class GoalsTest {
    private val zone = ZoneOffset.UTC
    private val m = 60_000L
    private val h = 60 * m

    // A Wednesday; its week starts on Monday the 26th.
    private val today = LocalDate.parse("2026-10-28")
    private fun at(date: LocalDate, hour: Int = 0) = date.atStartOfDay(zone).toInstant().toEpochMilli() + hour * h
    private val noon = at(today, 12)
    private val tenDaysAgo = at(today.minusDays(10))

    private fun limit(period: GoalPeriod = GoalPeriod.Daily, minutes: Int = 45, createdAt: Long = tenDaysAgo) =
        Goal("l", "Less Instagram", GoalKind.Limit, period, minutes, apps = setOf("ig"), createdAt = createdAt)

    private fun session(period: GoalPeriod = GoalPeriod.Daily, minutes: Int = 180, sessions: Int? = null, createdAt: Long = tenDaysAgo) =
        Goal("s", "Work", GoalKind.Session, period, minutes, configId = "c", sessions = sessions, createdAt = createdAt)

    private fun progress(goal: Goal, records: List<RunRecord> = emptyList(), live: List<LiveRun> = emptyList(), spans: List<ServiceSpan> = always, usage: Map<String, Long> = emptyMap()) =
        goalProgress(goal, records, live, spans, usage, today, noon, zone)

    private val always = listOf(ServiceSpan(0, Long.MAX_VALUE / 2))

    private fun run(start: Long, end: Long, succeeded: Boolean = true) = RunRecord(
        "r$start", "c", "Work", start = start, plannedEnd = end, end = end, shieldName = "S", protection = if (succeeded) Outcome.Succeeded else Outcome.NotAchieved,
    )

    private fun days(vararg back: Pair<Long, Long>) = back.associate { (b, ms) -> today.minusDays(b).toString() to ms }

    @Test fun limitUnderIsOnTrackUntilTheDayEnds() {
        val p = progress(limit(), usage = days(0L to 18 * m))
        assertEquals(18 * m, p.value)
        assertEquals(GoalState.OnTrack, p.state)
    }

    @Test fun limitFailsTheMomentItIsPassed() {
        val p = progress(limit(), usage = days(0L to 46 * m))
        assertEquals(GoalState.Failed, p.state)
        assertEquals(DayMark.Missed, p.record.last().second)
    }

    @Test fun finishedDaysAreJudgedAndCountedInTheStreak() {
        val p = progress(limit(createdAt = at(today.minusDays(3))), usage = days(3L to 60 * m, 2L to 10 * m, 1L to 45 * m, 0L to 5 * m))
        assertEquals(listOf(DayMark.Missed, DayMark.Succeeded, DayMark.Succeeded, DayMark.Pending), p.record.takeLast(4).map { it.second })
        assertEquals(2, p.streak)
        assertEquals(2, p.met)
        assertEquals(1, p.failed)
    }

    @Test fun aDayWithoutRecordsIsNeverASuccess() {
        val p = progress(limit(), usage = days(1L to 5 * m))
        assertNull(p.value)
        assertEquals(GoalState.Unknown, p.state)
        assertEquals(DayMark.Rest, p.record[p.record.size - 3].second)
    }

    @Test fun weeklyLimitAddsUpFromMondayOnly() {
        val g = limit(GoalPeriod.Weekly, minutes = 60)
        val usage = days(3L to 500 * m, 2L to 20 * m, 1L to 20 * m, 0L to 15 * m)
        assertEquals(55 * m, progress(g, usage = usage).value)
        assertEquals(GoalState.OnTrack, progress(g, usage = usage).state)
        assertEquals(GoalState.Failed, progress(g, usage = usage + (today.toString() to 21 * m)).state)
    }

    @Test fun usedInCountsOnlyTheGoalsApps() {
        val usage = mapOf("ig" to 10L, "yt" to 5L)
        assertEquals(10L, limit().usedIn(usage))
        assertEquals(15L, limit().copy(apps = emptySet()).usedIn(usage))
    }

    @Test fun sessionTimeShowsCompletedTimeIncludingARunGoingNow() {
        val p = progress(session(), live = listOf(LiveRun("c", noon - h, noon + h, true)))
        assertEquals(h, p.value)
        assertEquals(GoalState.OnTrack, p.state)
    }

    @Test fun sessionTimeCountsOnlyWhileTheShieldServiceWasBound() {
        val spans = listOf(ServiceSpan(at(today, 8), at(today, 9), closed = true))
        assertEquals(h, progress(session(), listOf(run(at(today, 8), at(today, 11))), spans = spans).value)
    }

    @Test fun overlappingRunsCountOnceAndOtherSessionsNotAtAll() {
        val other = run(at(today, 1), at(today, 2)).copy(configId = "other")
        val p = progress(session(), listOf(run(at(today, 8), at(today, 10)), run(at(today, 9), at(today, 11)), other))
        assertEquals(3 * h, p.value)
        assertEquals(GoalState.Achieved, p.state)
    }

    @Test fun aRunAcrossMidnightCountsOnEachDay() {
        val r = run(at(today.minusDays(1), 23), at(today, 2))
        assertEquals(2 * h, progress(session(), listOf(r)).value)
    }

    @Test fun missingADayFailsItButNotTheDayTheGoalWasCreated() {
        val p = progress(session(createdAt = at(today.minusDays(2), 21)))
        assertEquals(listOf(DayMark.Rest, DayMark.Missed, DayMark.Pending), p.record.takeLast(3).map { it.second })
        assertEquals(1, p.failed)
    }

    @Test fun sessionsPerWeekCountOnlySuccessesThisWeek() {
        val g = session(GoalPeriod.Weekly, sessions = 2)
        val lastWeek = run(at(today.minusDays(5)), at(today.minusDays(5), 1))
        val failed = run(at(today, 1), at(today, 2), succeeded = false)
        val good = run(at(today.minusDays(1)), at(today.minusDays(1), 1))
        assertEquals(1L, progress(g, listOf(lastWeek, failed, good)).value)
        assertEquals(GoalState.OnTrack, progress(g, listOf(lastWeek, failed, good)).state)
        assertEquals(GoalState.Achieved, progress(g, listOf(good, run(at(today, 3), at(today, 4)))).state)
    }

    @Test fun runsBeforeTheGoalExistedDontCount() {
        val g = session(createdAt = at(today, 9))
        assertEquals(h, progress(g, listOf(run(at(today, 8), at(today, 10)))).value)
    }

    @Test fun sessionStatsCountNormalCustomAndRunning() {
        val from = at(today)
        val to = at(today.plusDays(1))
        val normal = run(at(today, 1), at(today, 3)).copy(custom = false)
        val intentOnly = run(at(today, 4), at(today, 5), succeeded = false).copy(shieldName = null)
        val yesterday = run(at(today.minusDays(1)), at(today.minusDays(1), 1))
        val stats = sessionStats(listOf(normal, intentOnly, yesterday), listOf(LiveRun("c", noon, noon + h, true)), from, to, noon)
        assertEquals(SessionStats(started = 3, succeeded = 1, setMs = 3 * h), stats)
    }
}
