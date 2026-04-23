package io.nisfeb.talon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/**
 * Circular avatar. Renders the image at `url` if it looks like an http
 * URL; otherwise falls back to a monogram tile whose background tint
 * is derived from the label so the same ship always gets the same
 * color.
 */
@Composable
fun Avatar(
    label: String,
    url: String?,
    size: Dp = 36.dp,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    colorHex: String? = null,
) {
    val isHttp = url?.let { it.startsWith("http://") || it.startsWith("https://") } == true
    val tint = remember(label, colorHex) { colorHex?.let(::parseHexColor) ?: colorForLabel(label) }
    val mono = remember(label) { monogramFor(label) }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(tint)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
        contentAlignment = Alignment.Center,
    ) {
        if (isHttp) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(CircleShape),
            )
        } else {
            Text(
                mono,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = (size.value * 0.4f).sp,
                    color = Color.White,
                ),
            )
        }
    }
}

private fun monogramFor(label: String): String {
    val cleaned = label.removePrefix("~").removePrefix("#").trim()
    if (cleaned.isEmpty()) return "?"
    val parts = cleaned.split('-', ' ', '_').filter { it.isNotEmpty() }
    return when (parts.size) {
        0 -> cleaned.take(1).uppercase()
        1 -> parts[0].take(2).uppercase()
        else -> (parts[0].take(1) + parts[1].take(1)).uppercase()
    }
}

private val AVATAR_PALETTE = listOf(
    Color(0xFF6B4AE0), Color(0xFF3D7AED), Color(0xFF1AA2C6),
    Color(0xFF12A183), Color(0xFF4FA84C), Color(0xFFBC923A),
    Color(0xFFCE6A32), Color(0xFFD74C4C), Color(0xFFC94FA3),
    Color(0xFF8E4FCE),
)

private fun colorForLabel(label: String): Color {
    var hash = 0
    for (c in label) hash = hash * 31 + c.code
    val idx = ((hash % AVATAR_PALETTE.size) + AVATAR_PALETTE.size) % AVATAR_PALETTE.size
    return AVATAR_PALETTE[idx]
}

/** Parses `#RRGGBB` into a Compose Color, or null if malformed. */
private fun parseHexColor(hex: String): Color? {
    val trimmed = hex.trim().removePrefix("#")
    if (trimmed.length != 6) return null
    val r = trimmed.substring(0, 2).toIntOrNull(16) ?: return null
    val g = trimmed.substring(2, 4).toIntOrNull(16) ?: return null
    val b = trimmed.substring(4, 6).toIntOrNull(16) ?: return null
    return Color(red = r, green = g, blue = b)
}
