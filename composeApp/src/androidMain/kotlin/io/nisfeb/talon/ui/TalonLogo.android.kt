package io.nisfeb.talon.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import io.nisfeb.talon.R

/**
 * Reads the 1024-px Talon glyph from `drawable-nodpi/talon_logo.png`
 * rather than the launcher mipmap. The mipmap variants top out at
 * 192 px (xxxhdpi); rendering them at 96 dp on a 408-dpi display
 * upscales by ~2× and makes the icon look low-poly. The drawable-
 * nodpi bucket is density-agnostic, so Compose downsamples from the
 * 1024 source for whatever the call-site requested.
 */
@Composable
actual fun talonLogoPainter(): Painter =
    painterResource(R.drawable.talon_logo)
