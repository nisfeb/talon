package io.nisfeb.talon.ui

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import java.io.File

actual fun fontFromFile(path: String, weight: FontWeight, style: FontStyle): Font = Font(File(path), weight, style)

// Typeface.Builder answers null for a file it cannot use, where the
// Font above would only fail later, mid-layout.
actual fun fontLoads(path: String): Boolean =
    runCatching { android.graphics.Typeface.Builder(File(path)).build() != null }.getOrDefault(false)
