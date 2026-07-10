package io.nisfeb.talon.ui

import androidx.compose.ui.text.font.FontFamily

/** iOS renders color emoji natively via the system font stack, so the
 *  default family already draws Apple Color Emoji — no bundled font. */
actual val EmojiFontFamily: FontFamily = FontFamily.Default
