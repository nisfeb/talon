package io.nisfeb.talon.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Hue, saturation and value, each 0..1 except hue in degrees 0..360. */
data class Hsv(val h: Float, val s: Float, val v: Float) {
    fun toColor(): Color = Color.hsv(h.coerceIn(0f, 360f) % 360f, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f))
}

fun Color.toHsv(): Hsv {
    val max = maxOf(red, green, blue)
    val min = minOf(red, green, blue)
    val d = max - min
    val h = when {
        d == 0f -> 0f
        max == red -> 60f * (((green - blue) / d) % 6f)
        max == green -> 60f * ((blue - red) / d + 2f)
        else -> 60f * ((red - green) / d + 4f)
    }.let { if (it < 0f) it + 360f else it }
    val s = if (max == 0f) 0f else d / max
    return Hsv(h, s, max)
}

/**
 * A hue and saturation disc with a brightness slider. Hue runs around
 * the ring, saturation grows from the white centre outward, and the
 * slider darkens the whole disc so what you see is what you get.
 */
@Composable
fun ColorWheel(
    color: Color,
    onColor: (Color) -> Unit,
    modifier: Modifier = Modifier,
    diameter: androidx.compose.ui.unit.Dp = 200.dp,
) {
    var hsv by remember { mutableStateOf(color.toHsv()) }
    LaunchedEffect(color) { if (hsv.toColor() != color) hsv = color.toHsv() }
    fun pick(p: Offset, size: androidx.compose.ui.geometry.Size) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = min(size.width, size.height) / 2f
        val dx = p.x - c.x
        val dy = p.y - c.y
        val dist = sqrt(dx * dx + dy * dy)
        val hue = ((atan2(dy, dx) * 180f / PI.toFloat()) + 360f) % 360f
        hsv = hsv.copy(h = hue, s = (dist / r).coerceIn(0f, 1f))
        onColor(hsv.toColor())
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val hues = remember { (0..12).map { Color.hsv(it * 30f % 360f, 1f, 1f) } }
        Canvas(
            Modifier
                .size(diameter)
                .pointerInput(Unit) {
                    detectTapGestures { pick(it, size.toSize()) }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ -> change.consume(); pick(change.position, size.toSize()) }
                },
        ) {
            val r = min(size.width, size.height) / 2f
            val c = Offset(size.width / 2f, size.height / 2f)
            drawCircle(Brush.sweepGradient(hues, c), r, c)
            drawCircle(Brush.radialGradient(listOf(Color.White, Color.White.copy(alpha = 0f)), c, r), r, c)
            drawCircle(Color.Black.copy(alpha = 1f - hsv.v), r, c)
            val rad = hsv.h * PI.toFloat() / 180f
            val m = Offset(c.x + cos(rad) * hsv.s * r, c.y + sin(rad) * hsv.s * r)
            drawCircle(Color.Black, 9.dp.toPx(), m, style = Stroke(2.dp.toPx()))
            drawCircle(Color.White, 7.dp.toPx(), m, style = Stroke(2.dp.toPx()))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Brightness", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = hsv.v,
                onValueChange = { hsv = hsv.copy(v = it); onColor(hsv.toColor()) },
                modifier = Modifier.size(width = diameter - 80.dp, height = 32.dp),
            )
        }
    }
}

private fun androidx.compose.ui.unit.IntSize.toSize() =
    androidx.compose.ui.geometry.Size(width.toFloat(), height.toFloat())
