package io.nisfeb.talon.ui

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import okio.FileSystem
import okio.Path.Companion.toPath

// Skia on iOS takes a font as bytes; the path is its identity.
actual fun fontFromFile(path: String, weight: FontWeight, style: FontStyle): Font =
    androidx.compose.ui.text.platform.Font(path, FileSystem.SYSTEM.read(path.toPath()) { readByteArray() }, weight, style)

actual fun fontLoads(path: String): Boolean = runCatching {
    val bytes = FileSystem.SYSTEM.read(path.toPath()) { readByteArray() }
    org.jetbrains.skia.FontMgr.default.makeFromData(org.jetbrains.skia.Data.makeFromBytes(bytes)) != null
}.getOrDefault(false)
