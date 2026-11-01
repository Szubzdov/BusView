package com.busview

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import java.time.format.DateTimeFormatter

class BusWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
        refresh(ctx)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TOGGLE -> { Schedule.toggle(ctx); refresh(ctx) }
            ACTION_REFRESH -> refresh(ctx)
            else -> super.onReceive(ctx, intent)
        }
    }

    override fun onDisabled(ctx: Context) {
        ctx.getSystemService(AlarmManager::class.java).cancel(pending(ctx, ACTION_REFRESH))
    }

    companion object {
        private const val ACTION_TOGGLE = "com.busview.TOGGLE"
        private const val ACTION_REFRESH = "com.busview.REFRESH"
        private val HM = DateTimeFormatter.ofPattern("HH:mm")
        private const val MAX_WAIT_MS = 30 * 60 * 1000L

        private fun pending(ctx: Context, action: String) = PendingIntent.getBroadcast(
            ctx, action.hashCode(), Intent(ctx, BusWidget::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        fun refresh(ctx: Context) {
            val manager = AppWidgetManager.getInstance(ctx)
            val ids = manager.getAppWidgetIds(ComponentName(ctx, BusWidget::class.java))
            if (ids.isEmpty()) return

            val now = Schedule.now()
            val dir = Schedule.direction(ctx, now)
            val deps = Schedule.next(ctx, dir, now)

            val v = RemoteViews(ctx.packageName, R.layout.widget)
            v.setTextViewText(R.id.title, if (dir == HOME_TO_WORK) "Dom → Praca" else "Praca → Dom")
            v.setOnClickPendingIntent(R.id.toggle, pending(ctx, ACTION_TOGGLE))
            v.setOnClickPendingIntent(
                R.id.title, PendingIntent.getActivity(
                    ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
                )
            )

            val rows = listOf(
                Triple(R.id.row1, R.id.time1, Pair(R.id.line1, R.id.stop1)),
                Triple(R.id.row2, R.id.time2, Pair(R.id.line2, R.id.stop2))
            )
            rows.forEachIndexed { i, (row, time, rest) ->
                val d = deps.getOrNull(i)
                v.setViewVisibility(row, if (d == null) View.GONE else View.VISIBLE)
                if (d != null) {
                    v.setTextViewText(time, d.time.format(HM))
                    v.setTextViewText(rest.first, d.line)
                    v.setTextViewText(rest.second, d.stop)
                }
            }
            val message = when {
                !Schedule.hasData(ctx) -> "Otwórz BusView i zapisz ustawienia"
                deps.isEmpty() -> "Brak połączeń"
                else -> null
            }
            v.setViewVisibility(R.id.message, if (message == null) View.GONE else View.VISIBLE)
            v.setTextViewText(R.id.message, message ?: "")
            manager.updateAppWidget(ids, v)

            // Wake up again when the first bus has left, at the 00:00/12:00 switch, or in 30 min at the latest.
            var at = minOf(Schedule.nextSwitch(now).toInstant().toEpochMilli(), System.currentTimeMillis() + MAX_WAIT_MS)
            deps.firstOrNull()?.let { at = minOf(at, it.time.plusMinutes(1).toInstant().toEpochMilli()) }
            val am = ctx.getSystemService(AlarmManager::class.java)
            val pi = pending(ctx, ACTION_REFRESH)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC, at, pi)
            }
        }
    }
}
