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
import io.ktor.client.engine.okhttp.OkHttp
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
                val body = forecast(context, settings, at)
                val sky = WidgetSky.skyFor(at, settings.place, body)
                for (id in ids) {
                    runCatching { draw(context, manager, id, sky, settings) }
                        .onFailure { Log.w(TAG, "widget $id: ${it.message}") }
                }
            } finally {
                finish.finish()
            }
        }
    }

    /**
     * Today's forecast: the one already in hand if it is recent enough
     * and for the same place, otherwise a fresh one.
     *
     * A failed fetch falls back to whatever was cached, however old.
     * A dial that has yesterday's temperature on it is better than one
     * that has dropped the temperature entirely because the phone was
     * briefly on a train.
     */
    private suspend fun forecast(
        context: Context,
        settings: WidgetSky.Settings,
        atMs: Long,
    ): String? {
        val place = settings.place ?: return null
        WidgetSky.cachedForecast(context, place, atMs)?.let { return it }
        val fetched = withTimeoutOrNull(WEATHER_TIMEOUT_MS) {
            runCatching {
                val http = HttpClient(OkHttp)
                try {
                    val url = OpenMeteoWeather.requestUrl(place)
                    val resp = http.get(url)
                    if (!resp.status.isSuccess()) null else resp.bodyAsText()
                } finally {
                    http.close()
                }
            }.getOrNull()
        }
        if (fetched != null) {
            WidgetSky.rememberForecast(context, place, fetched, atMs)
            return fetched
        }
        // Stale rather than nothing.
        return WidgetSky.staleForecast(context)
    }

    private fun draw(
        context: Context,
        manager: AppWidgetManager,
        id: Int,
        sky: io.nisfeb.talon.ui.SkyClock.Sky,
        settings: WidgetSky.Settings,
    ) {
        val options = manager.getAppWidgetOptions(id)
        val widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 180)
        val heightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 180)
        val px = { dp: Int ->
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp.toFloat(),
                context.resources.displayMetrics,
            ).toInt().coerceIn(96, 1024)
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
