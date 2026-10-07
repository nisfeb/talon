package io.nisfeb.talon.ui

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import java.io.File

actual fun fontFromFile(path: String, weight: FontWeight, style: FontStyle): Font =
    androidx.compose.ui.text.platform.Font(File(path), weight, style)

actual fun fontLoads(path: String): Boolean =
    runCatching { org.jetbrains.skia.FontMgr.default.makeFromFile(path) != null }.getOrDefault(false)
