package app.aurora

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import app.aurora.protect.ConfigRun
import app.aurora.protect.Mode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Protection made visible outside Aurora: one quiet ongoing notification while a timed configuration or
 * Focus runs. It counts down on its own (a chronometer), so it stays right even if the process sleeps, and it
 * times out at the end. On Android 16 it is a progress-style Live Update. Also refreshes the home-screen
 * widget and the Quick Settings tile, which show the same state.
 */
object SessionNotification {
    private const val Channel = "protection"
    private const val Id = 1
    /** Bumped when Aurora comes back to the front, e.g. after the notification permission is answered. */
    private val resumed = kotlinx.coroutines.flow.MutableStateFlow(0)

    fun nudge() = resumed.update { it + 1 }

    fun start(app: AuroraApplication) {
        val manager = app.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(Channel, app.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW).apply {
                description = app.getString(R.string.notif_channel_desc)
                setShowBadge(false)
            },
        )
        app.scope.launch {
            combine(app.runtime.state, app.rules.state, resumed) { a, b, _ -> a to b }.collectLatest {
                // Runs start and end on the clock as well as on edits, so look again before the next change.
                // A failure here must never take the app down; try again in a minute.
                while (true) delay(runCatching { update(app, manager) }.getOrDefault(60_000L))
            }
        }
    }

    /** Posts or clears the notification; returns how long until it should be looked at again. */
    private fun update(app: AuroraApplication, manager: NotificationManager): Long {
        val t = app.now()
        AuroraWidget.refresh(app)
        AuroraTile.refresh(app)
        // Only sessions with an end: an Always configuration is ambient, and a notification that never
        // leaves would be noise. Home, the widget and the tile still show it.
        val runs = app.runsNow(t).filter { it.endAt(t) != null }
        val focus = app.runtime.value?.focus?.takeIf { !it.isOver(t) }
        if (runs.isEmpty() && focus == null) {
            manager.cancel(Id)
            return 60_000L
        }
        val start = runs.minOfOrNull { it.startAt(t) } ?: focus!!.startWall
        val end = (runs.map { it.endAt(t) } + listOfNotNull(focus?.let { it.startWall + it.durationMs })).let { ends ->
            if (ends.any { it == null }) null else ends.filterNotNull().maxOrNull()
        }
        val title = when {
            runs.size > 1 -> app.resources.getQuantityString(R.plurals.notif_running_many, runs.size, runs.size)
            runs.size == 1 -> runs.single().name
            else -> focus!!.intention.ifBlank { app.getString(R.string.notif_focus) }
        }
        val text = listOfNotNull(
            strongest(runs)?.let { app.getString(modeName(it)) },
            if (end == null) app.getString(R.string.notif_until_off) else app.getString(R.string.notif_ends, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(end))),
        ).joinToString(" · ")

        val open = PendingIntent.getActivity(
            app, 0,
            Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = Notification.Builder(app, Channel)
            .setSmallIcon(R.drawable.ic_stat_aurora)
            .setColor(app.getColor(R.color.aurora_accent))
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        if (end != null) {
            builder.setWhen(end).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
                .setTimeoutAfter((end - t.wall).coerceAtLeast(1_000L))
            if (Build.VERSION.SDK_INT >= 36) {
                val span = (end - start).coerceAtLeast(1L)
                val done = ((t.wall - start).coerceIn(0L, span) * 1000 / span).toInt()
                builder.setStyle(
                    Notification.ProgressStyle()
                        .setProgressSegments(listOf(Notification.ProgressStyle.Segment(1000).setColor(app.getColor(R.color.aurora_accent))))
                        .setProgress(done)
                        .setStyledByProgress(true),
                )
                // Live Update promotion arrived in Android 16 QPR2 (36.1); 36.0 devices don't have the method.
                if (Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1) builder.setRequestPromotedOngoing(true)
            }
        }
        runCatching { manager.notify(Id, builder.build()) }
        // A minute keeps the progress bar honest; sooner if the session ends first.
        return if (end == null) 60_000L else (end - t.wall).coerceIn(1_000L, 60_000L)
    }

    /** The mode that decides, in the Shield's own precedence (docs/PRODUCT.md, Rule precedence). */
    fun strongest(runs: List<ConfigRun>): Mode? = runs.mapNotNull { it.shield?.mode }.minByOrNull { listOf(Mode.AlwaysBlock, Mode.Flexible, Mode.Cooldown, Mode.Friction).indexOf(it) }

    fun modeName(m: Mode) = when (m) {
        Mode.Flexible -> R.string.mode_flexible
        Mode.Friction -> R.string.mode_friction
        Mode.Cooldown -> R.string.mode_cooldown
        Mode.AlwaysBlock -> R.string.mode_always_block
    }

    /** Whether Android would show it: on Android 13+ this is the person's choice. */
    fun allowed(context: Context): Boolean = context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true
}
