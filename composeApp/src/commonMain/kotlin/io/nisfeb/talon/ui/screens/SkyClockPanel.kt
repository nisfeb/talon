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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorPainter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.ui.Moon
import io.nisfeb.talon.ui.SkyClock
import kotlin.math.PI
import kotlin.math.abs
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
            // The richest thing on the page was a bare Canvas, which
            // reads out as nothing at all. The numbers in the middle are
            // separate Text and are spoken on their own; this is for
            // what only the drawing says.
            val spoken = dialDescription(sky, fahrenheit, twentyFourHour)
            Canvas(
                Modifier.fillMaxSize().semantics { contentDescription = spoken },
            ) {
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
                drawStars(
                    centre, radius, ring, sky, cloudiness,
                    bandPath(centre, radius, ring),
                )
                drawClouds(
                    centre, radius, ring, cloudiness, cloudPainter,
                    sky.sunriseMinute, sky.daylightMinutes,
                )
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
 * Where the clouds go, in the order they appear.
 *
 * Fixed angles rather than anything random: the drawing is recomposed
 * every few seconds, and clouds that jumped to new places each time
 * would be the most distracting thing on the page. Ordered so each new
 * one lands away from those already there, which keeps four clouds
 * looking scattered rather than bunched.
 */
/** Sizes that differ a lot, not a little: these are meant to read as
 *  separate masses of cloud, not as one shape repeated. Its length is
 *  also the most cloud the ring will ever carry. */
internal val CLOUD_SCALES = floatArrayOf(1f, 1.34f, 0.86f, 1.16f)

/** How far off the band's centreline each one sits, as a fraction of
 *  the band's width. Some crop against the outer edge and some against
 *  the inner, which is what stops four identical crescents. */
internal val CLOUD_OFFSETS = floatArrayOf(-0.22f, 0.18f, -0.08f, 0.26f)

/** Nudges off an even spread. Even spacing was what made the last
 *  version read as decoration rather than weather. */
internal val CLOUD_JITTER = floatArrayOf(-0.06f, 0.05f, -0.03f, 0.07f)

/** Roughly what one cloud takes up, so a short winter day does not get
 *  the same four a midsummer one does. */
internal const val CLOUD_ROOM_DEG = 60f

/**
 * How much of its own square the cloud glyph actually fills, top to
 * bottom. The Material cloud spans the full width of its 24-square but
 * only the middle two thirds of its height.
 */
internal const val CLOUD_GLYPH_FILL = 0.67f

/**
 * The side of the square a cloud is drawn into.
 *
 * Bigger than the band on purpose. Clouds sized to fit inside it came
 * out as a row of equidistant emoji; clouds that overrun it and get
 * cut off by its edges read as weather passing across the dial.
 */
internal fun cloudBox(ring: Float): Float = ring * 1.9f

/**
 * How many clouds, given the cover and how much daylight there is to
 * put them in.
 *
 * Bounded by the day's length because they only go in the lit part of
 * the ring: four clouds crammed into a December afternoon would be one
 * continuous smear, and a polar night has nowhere to put any.
 */
internal fun cloudCount(cover: Float, dayDegrees: Float): Int {
    if (cover <= 0.05f) return 0
    val room = (dayDegrees / CLOUD_ROOM_DEG).toInt()
    if (room < 1) return 0
    return (cover * CLOUD_SCALES.size).roundToInt().coerceIn(1, minOf(room, CLOUD_SCALES.size))
}

/**
 * Where one cloud sits along the daylight arc: 0 is sunrise and 1 is
 * sunset.
 *
 * Spread across whatever room there is and then nudged off even, with
 * the ends left clear so a cloud does not hang past sunrise into a
 * night that, by the look of it, has no weather at all.
 */
internal fun cloudFraction(index: Int, count: Int): Float {
    if (count <= 0) return 0.5f
    val even = (index + 0.5f) / count
    return (even + CLOUD_JITTER[index % CLOUD_JITTER.size]).coerceIn(0.12f, 0.88f)
}

/**
 * How far a cloud of side [w] at [angleDeg] reaches away from the ring
 * it sits on. Larger than half the band means it gets cut off there,
 * which is the point rather than the bug.
 */
internal fun cloudReach(w: Float, angleDeg: Float): Float {
    val rad = (angleDeg - 90f) * PI.toFloat() / 180f
    val halfW = w / 2f
    val halfH = w * CLOUD_GLYPH_FILL / 2f
    return halfW * abs(cos(rad)) + halfH * abs(sin(rad))
}

/**
 * The dial, in words.
 *
 * Everything the drawing carries and the centre text does not: whether
 * the sun is up, when it rises or sets, and what the sky is doing. Not
 * the time or the temperature — those are Text already and would be
 * read out twice.
 */
internal fun dialDescription(
    sky: SkyClock.Sky,
    fahrenheit: Boolean,
    twentyFourHour: Boolean,
): String {
    fun at(m: Int) = SkyClock.clockLabel(m, twentyFourHour)
    val parts = mutableListOf<String>()
    parts += when {
        sky.polar && sky.polarDay -> "The sun does not set today"
        sky.polar -> "The sun does not rise today"
        sky.sunUp -> "The sun is up. It set" + "s at ${at(sky.sunsetMinute)}"
        else -> "The sun is down. It rises at ${at(sky.sunriseMinute)}"
    }
    if (!sky.polar) {
        val h = sky.daylightMinutes / 60
        val m = sky.daylightMinutes % 60
        parts += "${h} hours and ${m} minutes of daylight"
    }
    conditionIcon(sky.condition)?.let { parts += it.second }
    sky.highC?.let { hi ->
        val whenAt = sky.highAtMinute?.let { " at ${at(it)}" }.orEmpty()
        parts += "High ${SkyClock.tempLabel(hi, fahrenheit)}$whenAt"
    }
    sky.lowC?.let { lo ->
        val whenAt = sky.lowAtMinute?.let { " at ${at(it)}" }.orEmpty()
        parts += "Low ${SkyClock.tempLabel(lo, fahrenheit)}$whenAt"
    }
    return parts.joinToString(". ") + "."
}

/** The ring itself, as a path, for cutting things off at its edges. */
private fun bandPath(centre: Offset, radius: Float, ring: Float): Path {
    val outer = Path().apply { addOval(Rect(centre, radius + ring / 2f)) }
    val inner = Path().apply { addOval(Rect(centre, radius - ring / 2f)) }
    return Path().apply { op(outer, inner, PathOperation.Difference) }
}

// ---- stars -------------------------------------------------------------

internal const val STAR_COUNT = 34

/** How far off the band's centreline a star may sit, as a fraction of
 *  the band's width. Kept clear of both edges so none is a half dot
 *  clipped against a rim. */
internal const val STAR_SPREAD = 0.68f

/** The brightest a star gets, before depth of night and cloud take
 *  their cut. Subtle is the whole point: these are meant to be noticed
 *  on the second look, not the first. */
internal const val STAR_ALPHA = 0.62f

/**
 * A stable scatter.
 *
 * The canvas recomposes every few seconds, so the positions have to
 * come out of the index rather than out of a random number: stars that
 * moved between frames would be a fault, not a sky. Different salts
 * give independent-looking values for the same star.
 */
internal fun starNoise(i: Int, salt: Int): Float {
    var h = i * 374_761_393 + salt * 668_265_263
    h = (h xor (h shr 13)) * 1_274_126_177
    return ((h xor (h shr 16)) and 0x7fff_ffff) / 0x7fff_ffff.toFloat()
}

/**
 * How brightly one star shows: not at all while the sky still holds
 * any light, fully once the night is deep, and dimmed by whatever
 * cloud is in the way. [depth] is the night's own depth, 0 at the
 * horizon and 1 in the small hours.
 */
internal fun starBrightness(depth: Float, cover: Float, noise: Float): Float {
    val clear = (1f - cover * 0.85f).coerceIn(0f, 1f)
    return STAR_ALPHA * depth.coerceIn(0f, 1f) * clear * (0.4f + 0.6f * noise)
}

/** How far off the band's centreline a star sits. */
internal fun starOffset(noise: Float, ring: Float): Float =
    (noise - 0.5f) * STAR_SPREAD * ring

/**
 * A star's own size: a speck, and not quite a uniform one.
 *
 * Under a hundredth of the band's width. At that size a star is a
 * pixel or two on a sharp screen and a soft one on a blunt screen,
 * which is about right for something meant to be noticed on the
 * second look rather than the first.
 */
internal fun starRadius(noise: Float, ring: Float): Float =
    ring * (0.0066f + 0.0078f * noise)

private fun DrawScope.drawStars(
    centre: Offset,
    radius: Float,
    ring: Float,
    sky: SkyClock.Sky,
    cover: Float,
    band: Path,
) {
    val nightMinutes = SkyClock.MINUTES_IN_DAY - sky.daylightMinutes
    // Nothing to put them in through a polar summer.
    if (nightMinutes < 60) return
    if (starBrightness(1f, cover, 1f) < 0.03f) return

    clipPath(band) {
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
            // Stars come out as the sky goes, so they fade in through
            // dusk rather than switching on at sunset.
            if (mix >= 0f) continue
            val a = starBrightness(-mix, cover, starNoise(i, 3))
            if (a < 0.02f) continue
            drawCircle(
                color = Color.White.copy(alpha = a),
                radius = starRadius(starNoise(i, 4), ring),
                center = pointOn(
                    SkyClock.angleOf(minute),
                    centre,
                    radius + starOffset(starNoise(i, 2), ring),
                ),
            )
        }
    }
}

private fun DrawScope.drawClouds(
    centre: Offset,
    radius: Float,
    ring: Float,
    cover: Float,
    painter: VectorPainter,
    sunriseMinute: Int,
    dayMinutes: Int,
) {
    // Daylight only. Nights are cloudy too, but a dark band with pale
    // shapes on it reads as smudges rather than as weather, and the
    // dial is better for leaving them off.
    val dayDegrees = dayMinutes / SkyClock.MINUTES_IN_DAY.toFloat() * 360f
    val count = cloudCount(cover, dayDegrees)
    if (count == 0) return
    val box = cloudBox(ring)

    // The clip is the shape. Each cloud is drawn far larger than the
    // band and cut off by both its edges, which is what gives a bank of
    // cloud rather than a sticker sitting in a slot.
    val band = bandPath(centre, radius, ring)

    // Cloud seen from underneath is not opaque. The sky has to keep
    // reading through it, or the ring stops being a clock.
    val tint = ColorFilter.tint(Color.White)
    val alpha = (0.15f + 0.22f * cover).coerceIn(0f, 1f)

    clipPath(band) {
        for (i in 0 until count) {
            val w = box * CLOUD_SCALES[i]
            val minute = sunriseMinute + (cloudFraction(i, count) * dayMinutes).roundToInt()
            // Off the centreline, so they crop against different edges.
            val p = pointOn(
                SkyClock.angleOf(minute), centre, radius + ring * CLOUD_OFFSETS[i],
            )
            translate(p.x - w / 2f, p.y - w / 2f) {
                with(painter) { draw(Size(w, w), alpha = alpha, colorFilter = tint) }
            }
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
