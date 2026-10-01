package io.nisfeb.talon.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import org.jetbrains.compose.resources.painterResource
import talon.composeapp.generated.resources.Res
import talon.composeapp.generated.resources.talon_logo

/**
 * The logo from Compose resources, which `embedAndSignAppleFrameworkForXcode`
 * copies into the app bundle. It used to read `icon.png` from the bundle,
 * a file the Xcode target never carried, so it was transparent on every
 * iOS surface: the chat list's top right, the ship picker, sign-in.
 */
@Composable
actual fun talonLogoPainter(): Painter = painterResource(Res.drawable.talon_logo)
