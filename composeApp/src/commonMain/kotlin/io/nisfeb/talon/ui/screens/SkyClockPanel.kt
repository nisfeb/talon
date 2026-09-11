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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.ui.SkyClock
import kotlin.math.PI
import kotlin.math.cos
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
    val arcs = remember(sky.sunriseMinute, sky.sunsetMinute, sky.twilight, sky.polar) {
        if (sky.polar) {
            // One band, because there is no sunrise to draw a boundary
            // at. A ring showing dawn and dusk on a day that has
            // neither would be a picture of somewhere else.
            listOf(
                SkyClock.Arc(
                    0f, 360f,
                    if (sky.polarDay) SkyClock.Band.DAY else SkyClock.Band.NIGHT,
                ),
            )
        } else {
            SkyClock.arcs(sky.sunriseMinute, sky.sunsetMinute, sky.twilight)
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

                for (a in arcs) {
                    drawArc(
                        color = when (a.band) {
                            SkyClock.Band.DAY -> dayColor
                            SkyClock.Band.NIGHT -> nightColor
                            else -> twilightColor
                        },
                        // Compose measures from three o'clock; the dial
                        // measures from twelve.
                        startAngle = a.startDeg - 90f,
                        sweepAngle = a.sweepDeg,
                        useCenter = false,
                        topLeft = topLeft,
                        size = box,
                        style = Stroke(width = ring),
                    )
                }

                val radius = box.minDimension / 2f
                val centre = Offset(size.width / 2f, size.height / 2f)
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
                    graduation(it, "H", centre, radius, ring, markColor, measurer, labelStyle)
                }
                if (sky.marksDistinct) {
                    sky.lowAtMinute?.let {
                        graduation(it, "L", centre, radius, ring, markColor, measurer, labelStyle)
                    }
                }

                // The sun, or the moon once it is down.
                val p = pointOn(sunAngle, centre, radius)
                drawCircle(
                    color = if (sky.sunUp) SUN else MOON,
                    radius = ring * 0.36f,
                    center = p,
                )
                if (!sky.sunUp) {
                    // A crescent, cut by overdrawing the night band's own
                    // colour rather than by a second shape with a hole.
                    drawCircle(
                        color = nightColor,
                        radius = ring * 0.30f,
                        center = Offset(p.x + ring * 0.16f, p.y - ring * 0.10f),
                    )
                }

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
    val laid = measurer.measure(label, style)
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

private val SUN = Color(0xFFF5B740)
private val MOON = Color(0xFFE8E4DA)

private val DAY_TEMPERATE = Color(0xFF6E9BEA)
private val DAY_COLD = Color(0xFF86BEEC)
private val DAY_WARM = Color(0xFF5C86D8)
private val DAY_OVERCAST = Color(0xFF9AA4B0)

private val NIGHT_BASE = Color(0xFF11131A)
private val NIGHT_OVERCAST = Color(0xFF2A2F38)

private val TWILIGHT_BASE = Color(0xFFF0A33C)
private val TWILIGHT_COLD = Color(0xFFE8956B)

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
