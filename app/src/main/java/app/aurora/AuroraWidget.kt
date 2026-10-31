package app.aurora

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews

/**
 * The home-screen widget: First Light at night, and what is protecting the person right now. While a
 * timed configuration runs it counts down by itself; otherwise it carries the promise and opens Home.
 * State comes from [SessionNotification], which refreshes it whenever protection changes.
 */
class AuroraWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        (context.applicationContext as? AuroraApplication)?.let { refresh(it) }
    }

    companion object {
        fun refresh(app: AuroraApplication) {
            val manager = AppWidgetManager.getInstance(app) ?: return
            val ids = runCatching { manager.getAppWidgetIds(ComponentName(app, AuroraWidget::class.java)) }.getOrNull()
            if (ids == null || ids.isEmpty()) return
            manager.updateAppWidget(ids, views(app))
        }

        internal fun views(app: AuroraApplication): RemoteViews {
            val t = app.now()
            val runs = app.runsNow(t)
            val v = RemoteViews(app.packageName, R.layout.widget_aurora)
            val open = PendingIntent.getActivity(
                app, 1,
                Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            v.setOnClickPendingIntent(R.id.widget_root, open)
            if (runs.isEmpty()) {
                v.setTextViewText(R.id.widget_eyebrow, app.getString(R.string.widget_idle_eyebrow))
                v.setTextViewText(R.id.widget_title, app.getString(R.string.today_headline))
                v.setTextViewText(R.id.widget_body, app.getString(R.string.widget_idle_body))
                v.setViewVisibility(R.id.widget_timer, View.GONE)
                return v
            }
            val end = runs.map { it.endAt(t) }.let { ends -> if (ends.any { it == null }) null else ends.filterNotNull().maxOrNull() }
            v.setTextViewText(R.id.widget_eyebrow, SessionNotification.strongest(runs)?.let { app.getString(SessionNotification.modeName(it)) } ?: app.getString(R.string.widget_on))
            v.setTextViewText(
                R.id.widget_title,
                if (runs.size == 1) runs.single().name else app.resources.getQuantityString(R.plurals.notif_running_many, runs.size, runs.size),
            )
            if (end == null) {
                v.setTextViewText(R.id.widget_body, app.getString(R.string.notif_until_off))
                v.setViewVisibility(R.id.widget_timer, View.GONE)
            } else {
                v.setTextViewText(R.id.widget_body, app.getString(R.string.widget_left))
                v.setViewVisibility(R.id.widget_timer, View.VISIBLE)
                // The chronometer runs on elapsed time; it counts down to the end without further updates.
                v.setChronometer(R.id.widget_timer, SystemClock.elapsedRealtime() + (end - t.wall), null, true)
                v.setChronometerCountDown(R.id.widget_timer, true)
            }
            return v
        }
    }
}
