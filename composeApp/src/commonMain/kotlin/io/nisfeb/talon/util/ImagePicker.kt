package io.nisfeb.talon.util

import androidx.compose.runtime.Composable

/**
 * Compose-bound image picker. The Android impl uses
 * rememberLauncherForActivityResult(PickVisualMedia) under the hood,
 * which requires being inside a Composable scope. The desktop impl
 * delegates to DesktopFilePicker (Swing JFileChooser).
 *
 * Returns a suspend lambda that opens the platform's image picker
 * and resolves to a PickedImage or null on cancel/failure.
 */
@Composable
expect fun rememberImagePicker(): suspend () -> PickedImage?

/**
 * Decode width + height from image bytes. Returns null on unsupported
 * format or decode error. Android uses BitmapFactory's bounds-only
 * decode (cheap, doesn't allocate the bitmap); desktop uses ImageIO.
 */
expect fun decodeImageDimensions(bytes: ByteArray): Pair<Int, Int>?
