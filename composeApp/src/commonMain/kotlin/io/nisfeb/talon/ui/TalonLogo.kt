package io.nisfeb.talon.ui

import androidx.compose.foundation.Image
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import org.jetbrains.compose.resources.painterResource
import talon.composeapp.generated.resources.Res
import talon.composeapp.generated.resources.talon_logo

/**
 * The Talon mark in the theme's primary colour ("make the logo match the
 * theme"). One centred mark for every platform, from Compose resources:
 * it was three copies, iOS's never in its bundle, desktop's the app icon
 * with its navy square cropped to hard corners, all of them sitting low.
 * The speech bubble is a hole in the mark, so the tint keeps it.
 * scripts/make-icons.py makes it, and the app icons, from branding/.
 */
@Composable
fun TalonLogo(contentDescription: String?, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(Res.drawable.talon_logo),
        contentDescription = contentDescription,
        modifier = modifier,
        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
    )
}
