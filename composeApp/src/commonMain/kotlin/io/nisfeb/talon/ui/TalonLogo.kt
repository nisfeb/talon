package io.nisfeb.talon.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter

/**
 * The Talon logo as a Painter. Android pulls from
 * `@mipmap/ic_launcher_foreground` via the R generated for the
 * androidMain res/ tree. Desktop decodes the bundled
 * `icon.png` resource via Skia.
 *
 * Used by DmListScreen's top-left brand mark in place of the
 * earlier Icons.Filled.Home placeholder.
 */
@Composable
expect fun talonLogoPainter(): Painter
