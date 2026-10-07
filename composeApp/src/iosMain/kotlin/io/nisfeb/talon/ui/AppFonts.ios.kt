package io.nisfeb.talon.ui

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

// Skia on iOS takes a font as bytes; the path is its identity.
actual fun fontFromFile(path: String, weight: FontWeight, style: FontStyle): Font =
    androidx.compose.ui.text.platform.Font(path, io.nisfeb.talon.util.readFileBytes(path), weight, style)

actual fun fontLoads(path: String): Boolean = runCatching {
    val bytes = io.nisfeb.talon.util.readFileBytes(path)
    org.jetbrains.skia.FontMgr.default.makeFromData(org.jetbrains.skia.Data.makeFromBytes(bytes)) != null
}.getOrDefault(false)
