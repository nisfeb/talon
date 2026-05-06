package io.nisfeb.talon.ui

import androidx.compose.ui.text.font.FontFamily

/**
 * Android handles color emoji natively via the platform emoji font,
 * so falling back to [FontFamily.Default] gives the user whatever
 * the OS wants — including the latest Unicode emoji set on newer
 * platform versions. No bundled font on this side.
 */
actual val EmojiFontFamily: FontFamily = FontFamily.Default
