package io.nisfeb.talon.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.util.TypedValue
import android.widget.RemoteViews
import io.nisfeb.talon.MainActivity
import io.nisfeb.talon.R
import io.nisfeb.talon.ui.OpenMeteoWeather
import io.nisfeb.talon.util.Log
import io.nisfeb.talon.util.nowMs
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "ClockWidget"

/**
 * The clock and weather dial, on the home screen.
 *
 * A widget is not the app: it draws through RemoteViews in the
 * launcher's process, with no Compose and no canvas of its own. So the
 * dial is rendered to a bitmap here and handed over as a picture, and
 * the time written across it is a TextClock, which the system ticks
 * for free rather than us waking up once a minute to redraw a circle
 * that has barely moved.
 *
 * Android-only: no desktop analogue, and iOS widgets are a separate
 * extension target with their own language and their own build.
 */
class ClockWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        ids: IntArray,
    ) {
        refresh(context, manager, ids)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        id: Int,
        newOptions: Bundle,
    ) {
        // Resized on the home screen. The dial is a bitmap, so it has
        // to be drawn again at the new size or it stretches.
        refresh(context, manager, intArrayOf(id))
    }

    private fun refresh(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return
        // Held past the end of onUpdate, because the forecast is a
        // network call and a broadcast receiver's process is killable
        // the moment it returns.
        val finish = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val settings = WidgetSky.settings(context)
                val at = nowMs()

                // Drawn twice on purpose. The first pass uses whatever
                // is already to hand, so the widget is never blank
                // while a network call is in flight — and never blank
                // at all if that call hangs or the process is killed
                // before it answers, which is the one failure that
                // looks exactly like the feature not existing.
                val known = WidgetSky.cachedForecast(context, settings.place, at)
                    ?: WidgetSky.staleForecast(context)
                paint(context, manager, ids, settings, at, known)

                // Then a fresh one, if what we had was not current.
                if (WidgetSky.cachedForecast(context, settings.place, at) == null) {
                    val fetched = fetch(context, settings, at)
                    if (fetched != null && fetched != known) {
                        paint(context, manager, ids, settings, at, fetched)
                    }
                }
            } finally {
                finish.finish()
            }
        }
    }

    private fun paint(
        context: Context,
        manager: AppWidgetManager,
        ids: IntArray,
        settings: WidgetSky.Settings,
        atMs: Long,
        body: String?,
    ) {
        val sky = WidgetSky.skyFor(atMs, settings.place, body)
        val note = when {
            settings.place == null -> "Set a location in Talon"
            body == null -> "No forecast yet"
            sky.currentC == null -> "Forecast unreadable"
            else -> null
        }
        // Logged as well as drawn: the three cases look identical from
        // a home screen and want telling apart when somebody reports a
        // dial with no weather on it.
        Log.i(
            TAG,
            "paint: place=${settings.place != null} body=${body?.length ?: -1} " +
                "temp=${sky.currentC != null} note=$note",
        )
        for (id in ids) {
            runCatching { draw(context, manager, id, sky, settings, note) }
                .onFailure { Log.w(TAG, "widget $id: ${it.message}") }
        }
    }

    /**
     * A fresh forecast, or null.
     *
     * The engine is the app's own rather than one named here: it is
     * configured once, in one place, and a widget quietly speaking
     * over a differently-built client is a difference nobody would
     * think to look for.
     */
    private suspend fun fetch(
        context: Context,
        settings: WidgetSky.Settings,
        atMs: Long,
    ): String? {
        val place = settings.place ?: return null
        val fetched = withTimeoutOrNull(WEATHER_TIMEOUT_MS) {
            runCatching {
                val http = HttpClient(io.nisfeb.talon.util.httpEngineFactory())
                try {
                    val resp = http.get(OpenMeteoWeather.requestUrl(place))
                    if (!resp.status.isSuccess()) {
                        Log.w(TAG, "forecast HTTP ${resp.status.value}")
                        null
                    } else {
                        resp.bodyAsText()
                    }
                } finally {
                    http.close()
                }
            }.onFailure { Log.w(TAG, "forecast failed: $it") }.getOrNull()
        }
        if (fetched != null) WidgetSky.rememberForecast(context, place, fetched, atMs)
        return fetched
    }

    private fun draw(
        context: Context,
        manager: AppWidgetManager,
        id: Int,
        sky: io.nisfeb.talon.ui.SkyClock.Sky,
        settings: WidgetSky.Settings,
        note: String?,
    ) {
        val options = manager.getAppWidgetOptions(id)
        // The larger of the two figures the launcher reports for each
        // axis. The minimum is what the widget shrinks to in the other
        // orientation; drawing at that and letting the ImageView scale
        // up is how a big widget comes out soft.
        val widthDp = maxOf(
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0),
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0),
        ).takeIf { it > 0 } ?: DEFAULT_SIDE_DP
        val heightDp = maxOf(
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0),
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0),
        ).takeIf { it > 0 } ?: DEFAULT_SIDE_DP
        val px = { dp: Int ->
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp.toFloat(),
                context.resources.displayMetrics,
            ).toInt().coerceIn(MIN_SIDE_PX, MAX_SIDE_PX)
        }
        val dark = (context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val face = if (dark) 0xFF15161B.toInt() else 0xFFF7F7FA.toInt()
        val ink = if (dark) 0xFFE6E8EC.toInt() else 0xFF1A1C20.toInt()
        val faint = if (dark) 0xFF9AA0A8.toInt() else 0xFF5F656D.toInt()

        val bitmap = DialPainter.render(
            widthPx = px(widthDp),
            heightPx = px(heightDp),
            sky = sky,
            fahrenheit = settings.fahrenheit,
            twentyFourHour = settings.twentyFourHour,
            note = note,
            onSurface = ink,
            onSurfaceVariant = faint,
            face = face,
        )

        val views = RemoteViews(context.packageName, R.layout.widget_clock)
        views.setImageViewBitmap(R.id.widget_dial, bitmap)
        views.setContentDescription(R.id.widget_dial, spoken(sky, settings))
        views.setOnClickPendingIntent(R.id.widget_dial, openApp(context))
        manager.updateAppWidget(id, views)
    }

    private fun spoken(
        sky: io.nisfeb.talon.ui.SkyClock.Sky,
        settings: WidgetSky.Settings,
    ): String {
        val time = io.nisfeb.talon.ui.SkyClock.clockLabel(sky.minuteOfDay, settings.twentyFourHour)
        val temp = sky.currentC?.let {
            ", " + io.nisfeb.talon.ui.SkyClock.tempLabel(it, settings.fahrenheit)
        }.orEmpty()
        return "$time$temp. ${sky.dateLabel}."
    }

    private fun openApp(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val WEATHER_TIMEOUT_MS = 8_000L

        /** What to draw at when the launcher will not say. */
        private const val DEFAULT_SIDE_DP = 180

        private const val MIN_SIDE_PX = 96

        /**
         * The biggest bitmap worth handing over.
         *
         * A RemoteViews bitmap crosses a process boundary, and while
         * large ones go through shared memory rather than the binder's
         * own buffer, there is no sense sending more than anybody can
         * see. Seven hundred and sixty-eight square is a widget filling
         * most of a tablet at two-times density; past that the
         * ImageView scales down and nothing is lost.
         */
        private const val MAX_SIDE_PX = 768

        /** Redraw every widget on the home screen. For the app to call
         *  when the place or the units change under it. */
        fun nudge(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(
                ComponentName(context, ClockWidgetProvider::class.java),
            )
            if (ids.isEmpty()) return
            context.sendBroadcast(
                Intent(context, ClockWidgetProvider::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                },
            )
        }
    }
}
