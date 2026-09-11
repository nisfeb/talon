package io.nisfeb.talon.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.ui.graphics.toArgb
import io.nisfeb.talon.ui.Moon
import io.nisfeb.talon.ui.SkyClock
import io.nisfeb.talon.ui.screens.MOON
import io.nisfeb.talon.ui.screens.MOON_DARK
import io.nisfeb.talon.ui.screens.SUN
import io.nisfeb.talon.ui.screens.SUN_DOWN
import io.nisfeb.talon.ui.screens.dayBand
import io.nisfeb.talon.ui.screens.markColor
import io.nisfeb.talon.ui.screens.nightBand
import io.nisfeb.talon.ui.screens.skyColor
import io.nisfeb.talon.ui.screens.twilightBand
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The dial, drawn to a bitmap for the home-screen widget.
 *
 * A widget renders through RemoteViews in the launcher's process,
 * where there is no Compose and no canvas of our own — only the
 * handful of views RemoteViews knows. So the dial is drawn here and
 * handed over as a picture.
 *
 * The colours and the bands come from the app's own dial rather than
 * from a second copy of the palette. Two dials that disagree about
 * what six in the evening looks like would be worse than one dial.
 */
object DialPainter {

    private const val SEGMENTS = 180
    private const val SEGMENT_MINUTES = SkyClock.MINUTES_IN_DAY / SEGMENTS

    fun render(
        widthPx: Int,
        heightPx: Int,
        sky: SkyClock.Sky,
        fahrenheit: Boolean,
        twentyFourHour: Boolean,
        onSurface: Int,
        onSurfaceVariant: Int,
        face: Int,
    ): Bitmap {
        val w = widthPx.coerceAtLeast(1)
        val h = heightPx.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        val side = minOf(w, h).toFloat()
        val ring = side * 0.16f
        val radius = (side - ring) / 2f
        val cx = w / 2f
        val cy = h / 2f

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // ---- the sky, as a run of short arcs -------------------------
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = ring
        val box = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        val day = dayBand(sky)
        val night = nightBand(sky)
        val horizon = twilightBand(sky)
        // A hair of overlap, or the seams show as hairlines all the way
        // round — the same reason the app's dial overlaps them.
        val sweep = 360f / SEGMENTS + 0.7f
        for (i in 0 until SEGMENTS) {
            val minute = i * SEGMENT_MINUTES + SEGMENT_MINUTES / 2
            val mix = SkyClock.skyMix(
                minuteOfDay = minute,
                sunriseMinute = sky.sunriseMinute,
                sunsetMinute = sky.sunsetMinute,
                twilightMinutes = sky.twilight,
                polar = sky.polar,
                polarDay = sky.polarDay,
            )
            val gloom = gloomAt(minute, sky)
            paint.color = skyColor(mix, day, horizon, night, gloom).toArgb()
            c.drawArc(box, SkyClock.angleOf(i * SEGMENT_MINUTES) - 90f, sweep, false, paint)
        }

        // ---- the face ------------------------------------------------
        paint.style = Paint.Style.FILL
        paint.color = face
        c.drawCircle(cx, cy, radius - ring / 2f, paint)

        // ---- the day's high and low ----------------------------------
        sky.highAtMinute?.let { graduation(c, paint, it, "H", cx, cy, radius, ring, markColor(sky.highC).toArgb()) }
        if (sky.marksDistinct) {
            sky.lowAtMinute?.let { graduation(c, paint, it, "L", cx, cy, radius, ring, markColor(sky.lowC).toArgb()) }
        }

        // ---- the moon, where it actually is --------------------------
        sky.moonElongationDeg?.let { elong ->
            val at = Moon.dialMinute(sky.minuteOfDay, elong)
            val p = pointOn(SkyClock.angleOf(at), cx, cy, radius)
            paint.style = Paint.Style.FILL
            paint.color = MOON_DARK.toArgb()
            c.drawCircle(p.first, p.second, ring * 0.30f, paint)
            paint.color = MOON.toArgb()
            c.drawPath(moonPath(p.first, p.second, ring * 0.30f, elong), paint)
        }

        // ---- the sun, which is also where now is ---------------------
        val sunAt = pointOn(SkyClock.angleOf(sky.minuteOfDay), cx, cy, radius)
        paint.color = (if (sky.sunUp) SUN else SUN_DOWN).toArgb()
        c.drawCircle(sunAt.first, sunAt.second, ring * 0.36f, paint)

        // ---- what is written across it -------------------------------
        readout(c, paint, sky, fahrenheit, twentyFourHour, cx, cy, side, onSurface, onSurfaceVariant)
        return bmp
    }

    /** How dark the sky is at a minute, hour by hour where that is known. */
    private fun gloomAt(minute: Int, sky: SkyClock.Sky): Float {
        val hourly = sky.hourlyCondition
        if (hourly.size != 24) return sky.condition.gloom
        val m = ((minute % 1440) + 1440) % 1440
        val h = m / 60
        val next = (h + 1) % 24
        val t = (m % 60) / 60f
        return hourly[h].gloom + (hourly[next].gloom - hourly[h].gloom) * t
    }

    private fun graduation(
        c: Canvas,
        paint: Paint,
        minute: Int,
        label: String,
        cx: Float,
        cy: Float,
        radius: Float,
        ring: Float,
        color: Int,
    ) {
        val a = SkyClock.angleOf(minute)
        val inner = pointOn(a, cx, cy, radius - ring * 0.5f)
        val outer = pointOn(a, cx, cy, radius + ring * 0.5f)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = ring * 0.09f
        paint.color = color
        c.drawLine(inner.first, inner.second, outer.first, outer.second, paint)

        paint.style = Paint.Style.FILL
        paint.textSize = ring * 0.42f
        paint.textAlign = Paint.Align.CENTER
        val at = pointOn(a, cx, cy, radius - ring * 0.5f - paint.textSize * 0.62f)
        c.drawText(label, at.first, at.second + paint.textSize * 0.35f, paint)
    }

    /**
     * The lit part of the moon: the limb facing the sun, and the same
     * half circle squashed by the phase. Signed, so it bulges into the
     * lit side for a crescent and away for a gibbous.
     */
    private fun moonPath(cx: Float, cy: Float, r: Float, elongationDeg: Double): Path {
        val e = elongationDeg * PI / 180.0
        val side = if (elongationDeg < 180.0) 1f else -1f
        val term = side * cos(e).toFloat()
        val path = Path()
        val steps = 32
        for (i in 0..steps) {
            val t = PI * i / steps
            val x = cx + side * r * sin(t).toFloat()
            val y = cy - r * cos(t).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        for (i in steps downTo 0) {
            val t = PI * i / steps
            path.lineTo(cx + term * r * sin(t).toFloat(), cy - r * cos(t).toFloat())
        }
        path.close()
        return path
    }

    /**
     * Time, date and temperature, thinning out as the dial does — the
     * same tiers the app's dial uses, because a widget is small and a
     * readout that spilled over the circle would be the first thing
     * anybody noticed.
     */
    private fun readout(
        c: Canvas,
        paint: Paint,
        sky: SkyClock.Sky,
        fahrenheit: Boolean,
        twentyFourHour: Boolean,
        cx: Float,
        cy: Float,
        side: Float,
        onSurface: Int,
        onSurfaceVariant: Int,
    ) {
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        val showDate = side >= 260f
        val showTemp = side >= 340f

        val lines = buildList {
            add(SkyClock.clockLabel(sky.minuteOfDay, twentyFourHour) to true)
            if (showDate) add(sky.dateLabel to false)
            if (showTemp && sky.currentC != null) {
                add(SkyClock.tempLabel(sky.currentC, fahrenheit) to true)
            }
        }
        val big = side * 0.11f
        val small = side * 0.065f
        val heights = lines.map { if (it.second) big else small }
        val total = heights.sum() + (lines.size - 1) * side * 0.02f
        var y = cy - total / 2f
        lines.forEachIndexed { i, (text, strong) ->
            paint.textSize = heights[i]
            paint.color = if (strong) onSurface else onSurfaceVariant
            y += heights[i]
            c.drawText(text, cx, y, paint)
            y += side * 0.02f
        }
    }

    private fun pointOn(angleDeg: Float, cx: Float, cy: Float, radius: Float): Pair<Float, Float> {
        val rad = (angleDeg - 90f) * PI.toFloat() / 180f
        return (cx + radius * cos(rad)) to (cy + radius * sin(rad))
    }

}
