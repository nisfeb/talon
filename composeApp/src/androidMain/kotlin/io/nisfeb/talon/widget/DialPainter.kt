package io.nisfeb.talon.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.RectF
import androidx.core.graphics.PathParser
import androidx.compose.ui.graphics.toArgb
import io.nisfeb.talon.ui.Moon
import io.nisfeb.talon.ui.SkyClock
import io.nisfeb.talon.ui.screens.MOON
import io.nisfeb.talon.ui.screens.MOON_DARK
import io.nisfeb.talon.ui.screens.MOON_TRACK_INSET
import io.nisfeb.talon.ui.screens.SUN
import io.nisfeb.talon.ui.screens.SUN_DOWN
import io.nisfeb.talon.ui.screens.CLOUD_GLYPH_FILL
import io.nisfeb.talon.ui.screens.CLOUD_OFFSETS
import io.nisfeb.talon.ui.screens.STAR_COUNT
import io.nisfeb.talon.ui.screens.cloudBox
import io.nisfeb.talon.ui.screens.cloudMinutes
import io.nisfeb.talon.ui.screens.cloudScale
import io.nisfeb.talon.ui.screens.dayBand
import io.nisfeb.talon.ui.screens.starBrightness
import io.nisfeb.talon.ui.screens.starNoise
import io.nisfeb.talon.ui.screens.starOffset
import io.nisfeb.talon.ui.screens.starRadius
import io.nisfeb.talon.ui.screens.markColor
import io.nisfeb.talon.ui.screens.nightBand
import io.nisfeb.talon.ui.screens.skyColor
import io.nisfeb.talon.ui.screens.twilightBand
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
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
        /** Why there is no weather, where there is none. A blank dial
         *  that will not say what it is waiting for is the worst of
         *  the states it can be in. */
        note: String?,
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

        // ---- stars and cloud, cut to the band ------------------------
        //
        // Where each one goes is the app's own arithmetic, not a second
        // guess at it: the same scatter, the same hours, the same sizes.
        // Only the drawing is different, because this canvas is.
        val band = bandPath(cx, cy, radius, ring)
        val cover = sky.cloudCover ?: 0f
        c.save()
        c.clipPath(band)
        stars(c, paint, sky, cover, cx, cy, radius, ring)
        clouds(c, paint, sky, cover, cx, cy, radius, ring)
        c.restore()

        // ---- the day's high and low ----------------------------------
        sky.highAtMinute?.let { graduation(c, paint, it, "H", cx, cy, radius, ring, markColor(sky.highC).toArgb()) }
        if (sky.marksDistinct) {
            sky.lowAtMinute?.let { graduation(c, paint, it, "L", cx, cy, radius, ring, markColor(sky.lowC).toArgb()) }
        }

        // ---- the moon, where it actually is --------------------------
        // Only when it is up, as in the app. A moon below the horizon
        // is one nobody can go outside and see.
        sky.moonElongationDeg?.takeIf { sky.moonVisible }?.let { elong ->
            val at = Moon.dialMinute(sky.minuteOfDay, elong)
            // Its own track, just inside the sun's, and a rim so a new
            // moon is a dark disc rather than nothing. Both for the
            // same reason: at a new moon the two are a couple of
            // degrees apart and the moon is unlit, so sharing a track
            // put an invisible disc under a larger marker.
            val p = pointOn(SkyClock.angleOf(at), cx, cy, radius - ring * MOON_TRACK_INSET)
            val r = ring * 0.30f
            paint.style = Paint.Style.FILL
            paint.color = MOON_DARK.toArgb()
            c.drawCircle(p.first, p.second, r, paint)
            paint.color = MOON.toArgb()
            c.drawPath(moonPath(p.first, p.second, r, elong), paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = r * 0.10f
            paint.color = MOON.copy(alpha = 0.45f).toArgb()
            c.drawCircle(p.first, p.second, r, paint)
            // Put the brush back. One Paint is carried through the
            // whole dial, so a style left on it is inherited by
            // whatever draws next -- and what draws next is the sun,
            // which came out as an empty ring on any morning the moon
            // was still up.
            paint.style = Paint.Style.FILL
        }

        // ---- the sun, which is also where now is ---------------------
        val sunAt = pointOn(SkyClock.angleOf(sky.minuteOfDay), cx, cy, radius)
        paint.color = (if (sky.sunUp) SUN else SUN_DOWN).toArgb()
        c.drawCircle(sunAt.first, sunAt.second, ring * 0.36f, paint)

        // ---- what is written across it -------------------------------
        readout(
            c, paint, sky, fahrenheit, twentyFourHour, note, cx, cy, side,
            onSurface, onSurfaceVariant,
        )
        return bmp
    }

    /** The ring itself, for cutting things off at its edges. */
    private fun bandPath(cx: Float, cy: Float, radius: Float, ring: Float): Path {
        val p = Path()
        // Even-odd, so the inner circle punches a hole rather than
        // filling the middle back in.
        p.fillType = Path.FillType.EVEN_ODD
        p.addCircle(cx, cy, radius + ring / 2f, Path.Direction.CW)
        p.addCircle(cx, cy, radius - ring / 2f, Path.Direction.CW)
        return p
    }

    private fun stars(
        c: Canvas,
        paint: Paint,
        sky: SkyClock.Sky,
        cover: Float,
        cx: Float,
        cy: Float,
        radius: Float,
        ring: Float,
    ) {
        val nightMinutes = SkyClock.MINUTES_IN_DAY - sky.daylightMinutes
        // Nothing to put them in through a polar summer.
        if (nightMinutes < 60) return
        paint.style = Paint.Style.FILL
        for (i in 0 until STAR_COUNT) {
            val minute = sky.sunsetMinute + (starNoise(i, 1) * nightMinutes).roundToInt()
            val mix = SkyClock.skyMix(
                minuteOfDay = minute,
                sunriseMinute = sky.sunriseMinute,
                sunsetMinute = sky.sunsetMinute,
                twilightMinutes = sky.twilight,
                polar = sky.polar,
                polarDay = sky.polarDay,
            )
            // They come out as the sky goes, rather than switching on
            // at sunset.
            if (mix >= 0f) continue
            val a = starBrightness(-mix, cover, starNoise(i, 3))
            if (a < 0.02f) continue
            paint.color = Color.argb((a * 255).toInt().coerceIn(0, 255), 255, 255, 255)
            val p = pointOn(
                SkyClock.angleOf(minute), cx, cy, radius + starOffset(starNoise(i, 2), ring),
            )
            c.drawCircle(p.first, p.second, starRadius(starNoise(i, 4), ring), paint)
        }
    }

    /**
     * The Material cloud, as a path.
     *
     * Parsed once from the same outline the app's dial paints, so the
     * two agree about what a cloud looks like. It spans the full width
     * of its twenty-four square and the middle two thirds of its
     * height, which is what [CLOUD_GLYPH_FILL] records.
     */
    private val cloudGlyph: Path by lazy {
        PathParser.createPathFromPathData(
            "M19.35 10.04C18.67 6.59 15.64 4 12 4 9.11 4 6.6 5.64 5.35 8.04 " +
                "2.34 8.36 0 10.91 0 14c0 3.31 2.69 6 6 6h13c2.76 0 5-2.24 5-5 " +
                "0-2.64-2.05-4.78-4.65-4.96z",
        )
    }

    private fun clouds(
        c: Canvas,
        paint: Paint,
        sky: SkyClock.Sky,
        cover: Float,
        cx: Float,
        cy: Float,
        radius: Float,
        ring: Float,
    ) {
        val at = cloudMinutes(
            hourlyCloud = sky.hourlyCloud,
            currentCover = cover,
            sunriseMinute = sky.sunriseMinute,
            dayMinutes = sky.daylightMinutes,
        )
        if (at.isEmpty()) return
        paint.style = Paint.Style.FILL
        val box = cloudBox(ring)
        val scratch = Path()
        val matrix = Matrix()
        at.forEachIndexed { i, (minute, coverAt) ->
            val w = box * cloudScale(coverAt)
            val p = pointOn(
                SkyClock.angleOf(minute),
                cx,
                cy,
                radius + ring * CLOUD_OFFSETS[i % CLOUD_OFFSETS.size],
            )
            // Drawn into a square of side w, the same as the app does,
            // so the glyph's own padding puts the cloud at about the
            // band's height rather than over both its edges.
            matrix.setScale(w / 24f, w / 24f)
            matrix.postTranslate(p.first - w / 2f, p.second - w / 2f)
            scratch.reset()
            cloudGlyph.transform(matrix, scratch)
            val a = (0.15f + 0.22f * coverAt).coerceIn(0f, 1f)
            paint.color = Color.argb((a * 255).toInt().coerceIn(0, 255), 255, 255, 255)
            c.drawPath(scratch, paint)
        }
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
        note: String?,
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
        val showRange = side >= 440f

        val lines = buildList {
            add(SkyClock.clockLabel(sky.minuteOfDay, twentyFourHour) to true)
            if (showDate) add(sky.dateLabel to false)
            if (showTemp && sky.currentC != null) {
                add(SkyClock.tempLabel(sky.currentC, fahrenheit) to true)
            }
            // The day's range, on one line rather than the app's two
            // columns: a widget is small and two stacked pairs would
            // cost more height than the figures are worth.
            // Said once there is room for a second line at all, since
            // it is the line that explains the rest of the dial.
            if (note != null && showDate) add(note to false)
            if (showRange && sky.currentC != null) {
                val hi = sky.highC?.let { "H " + SkyClock.tempLabel(it, fahrenheit) }
                val lo = sky.lowC?.let { "L " + SkyClock.tempLabel(it, fahrenheit) }
                listOfNotNull(hi, lo).takeIf { it.isNotEmpty() }?.let {
                    add(it.joinToString("   ") to false)
                }
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
