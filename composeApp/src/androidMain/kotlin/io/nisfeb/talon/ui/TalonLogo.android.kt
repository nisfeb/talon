package io.nisfeb.talon.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import io.nisfeb.talon.compose.R

@Composable
actual fun talonLogoPainter(): Painter =
    painterResource(R.mipmap.ic_launcher_foreground)
