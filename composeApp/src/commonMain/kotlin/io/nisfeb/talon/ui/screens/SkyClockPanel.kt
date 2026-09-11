package io.nisfeb.talon.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.Thunderstorm
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorPainter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.ui.Moon
import io.nisfeb.talon.ui.SkyClock
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The day as a dial: night at the bottom, noon at the top, the sun
 * travelling clockwise around it, and the whole ring changing colour as
 * the hours pass and the weather does.
 *
 * Meant to be looked at. This is the one panel somebody leaves open on
 * a desk all day, so the sun's mark moves continuously rather than
 * snapping each minute, and the band colours cross-fade rather than
 * cutting. Nothing here blinks, pulses or demands anything: the delight
 * is supposed to be that it is quietly correct every time you glance.
 */
@Composable
fun SkyClockDial(
    sky: SkyClock.Sky,
    fahrenheit: Boolean,
    twentyFourHour: Boolean,
    modifier: Modifier = Modifier,
) {
    // The ring is drawn as a run of short segments rather than four
    // bands, so the colour slides through sunrise, day, sunset and a
    // deep blue night instead of cutting between them.
    val mixes = remember(
        sky.sunriseMinute, sky.sunsetMinute, sky.twilight, sky.polar, sky.polarDay,
    ) {
        FloatArray(SEGMENTS) { i ->
            SkyClock.skyMix(
                minuteOfDay = i * SEGMENT_MINUTES + SEGMENT_MINUTES / 2,
                sunriseMinute = sky.sunriseMinute,
                sunsetMinute = sky.sunsetMinute,
                twilightMinutes = sky.twilight,
                polar = sky.polar,
                polarDay = sky.polarDay,
            )
        }
    }

    // The sun crawls rather than ticks. A minute of dial is a quarter of
    // a degree, so without this it would visibly jump once a minute in
    // front of somebody who is not even looking at it.
    val sunAngle by animateFloatAsState(
        targetValue = SkyClock.angleOf(sky.minuteOfDay),
        animationSpec = tween(durationMillis = 900),
        label = "sun",
    )

    val moonMinute = sky.moonElongationDeg?.let { Moon.dialMinute(sky.minuteOfDay, it) }
    val moonAngle by animateFloatAsState(
        targetValue = moonMinute?.let { SkyClock.angleOf(it) } ?: 0f,
        animationSpec = tween(durationMillis = 900),
        label = "moon",
    )

    val dayColor by animateColorAsState(
        targetValue = dayBand(sky),
        animationSpec = tween(2_000),
        label = "day",
    )
    val nightColor by animateColorAsState(
        targetValue = nightBand(sky),
        animationSpec = tween(2_000),
        label = "night",
    )
    val twilightColor by animateColorAsState(
        targetValue = twilightBand(sky),
        animationSpec = tween(2_000),
        label = "twilight",
    )

    // Rain takes the light out of a sky. Animated with the rest so a
    // shower arriving does not snap the dial to a different day.
    val gloom by animateFloatAsState(
        targetValue = sky.condition.gloom,
        animationSpec = tween(2_000),
        label = "gloom",
    )
    val cloudiness by animateFloatAsState(
        targetValue = sky.cloudCover ?: 0f,
        animationSpec = tween(2_000),
        label = "cloud",
    )
    // The same glyph the condition line uses, painted onto the ring.
    // Three overlapping circles were a good enough cloud for a
    // thumbnail and an obvious three circles at this size.
    val cloudPainter = rememberVectorPainter(Icons.Filled.Cloud)
    val faceColor = MaterialTheme.colorScheme.surface
    val inkColor = MaterialTheme.colorScheme.onSurface
    val markColor = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall
        .copy(fontWeight = FontWeight.SemiBold, color = markColor)

    Box(modifier, contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).widthIn(max = 320.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val ring = size.minDimension * 0.16f
                val inset = ring / 2f
                val box = Size(size.minDimension - ring, size.minDimension - ring)
                val topLeft = Offset(
                    (size.width - box.width) / 2f,
                    (size.height - box.height) / 2f,
                )

                // A hair of overlap on each segment: without it the
                // seams show as hairline gaps all the way round.
                val sweep = 360f / SEGMENTS + 0.7f
                for (i in 0 until SEGMENTS) {
                    drawArc(
                        color = skyColor(
                            mixes[i], dayColor, twilightColor, nightColor, gloom,
                        ),
                        // Compose measures from three o'clock; the dial
                        // measures from twelve.
                        startAngle = SkyClock.angleOf(i * SEGMENT_MINUTES) - 90f,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = topLeft,
                        size = box,
                        style = Stroke(width = ring),
                    )
                }

                val radius = box.minDimension / 2f
                val centre = Offset(size.width / 2f, size.height / 2f)

                // Cloud, drawn rather than only drained out of the
                // colour. A flat grey ring says "overcast" to somebody
                // who already knows that is what it means; puffs say it
                // to everybody, and they say it at night too, where a
                // drained night band looks the same as a clear one.
                drawClouds(centre, radius, ring, cloudiness, cloudPainter)
                // Laid down before anything that sits inside the sky
                // ring, or it paints over them.
                drawCircle(color = faceColor, radius = radius - ring / 2f, center = centre)

                // The high and low, as graduations on the sky ring
                // with a letter each.
                //
                // Unlabelled ticks were the first thing anyone asked
                // about — a grey bar says nothing about what it marks,
                // whether it sits on the sky or on a ring of its own.
                // A letter answers the question on the face of it, which
                // no amount of colour was going to do.
                sky.highAtMinute?.let {
                    graduation(it, "H", centre, radius, ring, markColor(sky.highC), measurer, labelStyle)
                }
                if (sky.marksDistinct) {
                    sky.lowAtMinute?.let {
                        graduation(it, "L", centre, radius, ring, markColor(sky.lowC), measurer, labelStyle)
                    }
                }

                // The moon, where it actually is and the shape it
                // actually is. It lags the sun by its phase, so it is
                // only opposite the sun when it is full — drawing it
                // wherever the sun is not was a picture of nothing.
                if (moonMinute != null && sky.moonElongationDeg != null) {
                    drawMoon(
                        centre = centre,
                        angleDeg = moonAngle,
                        orbit = radius,
                        r = ring * 0.30f,
                        elongationDeg = sky.moonElongationDeg,
                        lit = MOON,
                        dark = MOON_DARK,
                    )
                }

                // The sun, which is also where "now" is. Below the
                // horizon it stays on the dial and dims: at two in the
                // morning the sun is under the earth, which is exactly
                // what the dark bottom of the ring is showing.
                drawCircle(
                    color = if (sky.sunUp) SUN else SUN_DOWN,
                    radius = ring * 0.36f,
                    center = pointOn(sunAngle, centre, radius),
                )
            }

            Column(
                Modifier.fillMaxSize().padding(horizontal = 28.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    SkyClock.clockLabel(sky.minuteOfDay, twentyFourHour),
                    style = MaterialTheme.typography.headlineMedium
                        .copy(fontWeight = FontWeight.Medium),
                    color = inkColor,
                )
                Text(
                    sky.dateLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = markColor,
                )
                conditionIcon(sky.condition)?.let { (icon, word) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(top = 2.dp),
                    ) {
                        Icon(
                            icon,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = markColor,
                        )
                        Text(word, style = MaterialTheme.typography.labelMedium, color = markColor)
                    }
                }
                if (sky.currentC != null) {
                    Text(
                        SkyClock.tempLabel(sky.currentC, fahrenheit),
                        style = MaterialTheme.typography.headlineSmall,
                        color = inkColor,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        HiLo("H", sky.highC, sky.highAtMinute, fahrenheit, twentyFourHour, markColor)
                        HiLo("L", sky.lowC, sky.lowAtMinute, fahrenheit, twentyFourHour, markColor)
                    }
                }
            }
        }
    }
}

@Composable
private fun HiLo(
    label: String,
    celsius: Double?,
    atMinute: Int?,
    fahrenheit: Boolean,
    twentyFourHour: Boolean,
    color: Color,
) {
    if (celsius == null) return
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "$label ${SkyClock.tempLabel(celsius, fahrenheit)}",
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
        if (atMinute != null) {
            Text(
                SkyClock.clockLabel(atMinute, twentyFourHour),
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
        }
    }
}

private fun pointOn(angleDeg: Float, centre: Offset, radius: Float): Offset {
    val rad = (angleDeg - 90f) * PI.toFloat() / 180f
    return Offset(centre.x + radius * cos(rad), centre.y + radius * sin(rad))
}

/**
 * One graduation on the sky ring, with its letter just inside.
 *
 * The tick spans the ring's own width the way a clock's hour mark
 * does, so it reads as part of the dial rather than as something laid
 * across it. The letter sits on the plain face, where nothing behind
 * it can swallow it.
 */
private fun DrawScope.graduation(
    minute: Int,
    label: String,
    centre: Offset,
    radius: Float,
    ring: Float,
    color: Color,
    measurer: TextMeasurer,
    style: TextStyle,
) {
    val a = SkyClock.angleOf(minute)
    drawLine(
        color = color,
        start = pointOn(a, centre, radius - ring * 0.5f),
        end = pointOn(a, centre, radius + ring * 0.5f),
        strokeWidth = ring * 0.09f,
    )
    val laid = measurer.measure(label, style.copy(color = color))
    val p = pointOn(a, centre, radius - ring * 0.5f - laid.size.height * 0.60f)
    drawText(
        laid,
        topLeft = Offset(p.x - laid.size.width / 2f, p.y - laid.size.height / 2f),
    )
}

// ---- the palette -------------------------------------------------------
//
// Two things move the colours: the hour, through which band is where,
// and the weather, through warmth and cloud. Warmth is a red/blue shift
// applied to the lit bands only — the night does not get warmer because
// the afternoon was hot, and tinting it would just look like a bug.

private const val SEGMENTS = 180
private const val SEGMENT_MINUTES = SkyClock.MINUTES_IN_DAY / SEGMENTS

private val SUN = Color(0xFFF5B740)

/** The sun under the earth: still on the dial, plainly not lighting it. */
private val SUN_DOWN = Color(0xFF6B5526)

private val MOON = Color(0xFFE8E4DA)

/** The unlit limb. A new moon is still there; it is simply catching
 *  nothing, and a disc that vanished entirely would read as a bug. */
private val MOON_DARK = Color(0xFF3A3F4D)

// The high and low marks are tinted by the temperature they mark, but
// from a brighter palette than the ring's own: a cold mark in the ring's
// own blue would sit on the day band and disappear.
private val MARK_COLD = Color(0xFF7FC4FF)
private val MARK_MILD = Color(0xFFE6E9EE)
private val MARK_HOT = Color(0xFFFF7A5C)

private val DAY_TEMPERATE = Color(0xFF6E9BEA)
private val DAY_COLD = Color(0xFF86BEEC)
private val DAY_WARM = Color(0xFF5C86D8)
private val DAY_OVERCAST = Color(0xFF9AA4B0)

// Night is a deep blue, not black. A black ring reads as a hole in
// the dial; the sky at two in the morning is very dark and still blue.
private val NIGHT_BASE = Color(0xFF0C1533)
private val NIGHT_OVERCAST = Color(0xFF232B3D)

/** What a sky under rain heads toward. Not grey: a wet sky keeps its
 *  blue, it just stops being lit. */
private val GLOOM = Color(0xFF1E2530)

private val TWILIGHT_BASE = Color(0xFFF0A33C)
private val TWILIGHT_COLD = Color(0xFFE8956B)

/**
 * Which icon and word say what the sky is doing, or null when it is
 * doing nothing worth a line. A clear day should not have to carry a
 * label saying so.
 */
private fun conditionIcon(w: SkyClock.Weather): Pair<ImageVector, String>? = when (w) {
    SkyClock.Weather.CLEAR -> null
    SkyClock.Weather.CLOUD -> Icons.Filled.Cloud to "Cloudy"
    SkyClock.Weather.FOG -> Icons.Filled.Air to "Fog"
    SkyClock.Weather.DRIZZLE -> Icons.Filled.Grain to "Drizzle"
    SkyClock.Weather.RAIN -> Icons.Filled.WaterDrop to "Rain"
    SkyClock.Weather.SLEET -> Icons.Filled.Grain to "Sleet"
    SkyClock.Weather.SNOW -> Icons.Filled.AcUnit to "Snow"
    SkyClock.Weather.THUNDER -> Icons.Filled.Thunderstorm to "Storm"
}

/**
 * Where the puffs go, in the order they appear.
 *
 * Fixed angles rather than anything random: the drawing is recomposed
 * every few seconds, and clouds that jumped to new places each time
 * would be the most distracting thing on the page. Ordered so that
 * each new one lands away from those already there, which keeps four
 * clouds looking scattered rather than bunched.
 */
private val CLOUD_SLOTS = floatArrayOf(
    34f, 196f, 108f, 274f, 72f, 232f, 148f, 312f, 12f, 168f,
)

private fun DrawScope.drawClouds(
    centre: Offset,
    radius: Float,
    ring: Float,
    cover: Float,
    painter: VectorPainter,
) {
    if (cover <= 0.05f) return
    val count = (cover * CLOUD_SLOTS.size).roundToInt().coerceIn(1, CLOUD_SLOTS.size)
    // The glyph sits inside its own 24-square with room above and
    // below, so the box is drawn wider than the band to put the cloud
    // itself at about the band's height.
    val box = ring * 1.3f
    // Thin enough that the band still reads through them, which is
    // what cloud actually looks like from underneath.
    val tint = ColorFilter.tint(Color.White)
    val alpha = (0.20f + 0.30f * cover).coerceIn(0f, 1f)
    // Upright wherever they sit, like the H and L: a cloud rotated to
    // the ring reads as a decoration going round a dial rather than as
    // weather.
    for (i in 0 until count) {
        val p = pointOn(CLOUD_SLOTS[i], centre, radius)
        translate(p.x - box / 2f, p.y - box / 2f) {
            with(painter) { draw(Size(box, box), alpha = alpha, colorFilter = tint) }
        }
    }
}

/**
 * The ring's colour at one point in the day.
 *
 * The horizon colour is the hinge: the sky runs down to it from full
 * day and on past it into night, so sunrise and sunset are where the
 * ring is most coloured rather than two stripes laid over it.
 */
private fun skyColor(
    mix: Float,
    day: Color,
    horizon: Color,
    night: Color,
    gloom: Float,
): Color {
    val base = if (mix >= 0f) lerp(horizon, day, mix) else lerp(horizon, night, -mix)
    // Rain does not recolour a sky so much as take the light out of
    // it, so this pulls toward a dark slate rather than toward grey.
    return lerp(base, GLOOM, gloom.coerceIn(0f, 1f))
}

/** A temperature as a mark colour, cold through mild to hot. */
private fun markColor(celsius: Double?): Color {
    val w = SkyClock.warmth(celsius)
    return if (w >= 0f) lerp(MARK_MILD, MARK_HOT, w) else lerp(MARK_MILD, MARK_COLD, -w)
}

/**
 * The lit part of the moon's disc.
 *
 * Two curves: the limb facing the sun, which is a half circle, and the
 * terminator, which is that same half circle squashed by the phase.
 * The squash is signed, so it bulges into the lit side for a crescent
 * and away from it for a gibbous, and goes flat at the quarters. Drawn
 * as a path rather than cut out with an overdrawn disc, because the
 * moon can sit over any part of the ring and there is no one colour to
 * cut it with.
 */
private fun moonLitPath(c: Offset, r: Float, elongationDeg: Double): Path {
    val e = elongationDeg * PI / 180.0
    val side = if (elongationDeg < 180.0) 1f else -1f
    val term = side * cos(e).toFloat()
    val path = Path()
    val steps = 32
    for (i in 0..steps) {
        val t = PI * i / steps
        val x = c.x + side * r * sin(t).toFloat()
        val y = c.y - r * cos(t).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    for (i in steps downTo 0) {
        val t = PI * i / steps
        path.lineTo(c.x + term * r * sin(t).toFloat(), c.y - r * cos(t).toFloat())
    }
    path.close()
    return path
}

private fun DrawScope.drawMoon(
    centre: Offset,
    angleDeg: Float,
    orbit: Float,
    r: Float,
    elongationDeg: Double,
    lit: Color,
    dark: Color,
) {
    val p = pointOn(angleDeg, centre, orbit)
    drawCircle(color = dark, radius = r, center = p)
    drawPath(moonLitPath(p, r, elongationDeg), color = lit)
}

private fun dayBand(sky: SkyClock.Sky): Color {
    // The sky stays sky. Temperature only nudges it between a cool and
    // a warm blue: blending a blue all the way to an orange crosses
    // through purple in RGB, which is neither hot nor a sky.
    val w = sky.warmth
    val nudged = if (w >= 0f) lerp(DAY_TEMPERATE, DAY_WARM, w) else lerp(DAY_TEMPERATE, DAY_COLD, -w)
    // Cloud drains the colour rather than painting grey over it, so an
    // overcast afternoon reads as flat instead of as a different hour.
    return lerp(nudged, DAY_OVERCAST, sky.overcast * 0.8f)
}

private fun nightBand(sky: SkyClock.Sky): Color =
    lerp(NIGHT_BASE, NIGHT_OVERCAST, sky.overcast * 0.7f)

private fun twilightBand(sky: SkyClock.Sky): Color {
    val w = sky.warmth
    val base = if (w >= 0f) TWILIGHT_BASE else lerp(TWILIGHT_BASE, TWILIGHT_COLD, -w * 0.6f)
    return lerp(base, DAY_OVERCAST, sky.overcast * 0.5f)
}
