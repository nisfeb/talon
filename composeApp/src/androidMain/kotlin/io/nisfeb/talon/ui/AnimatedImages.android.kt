package io.nisfeb.talon.ui

import android.os.Build
import coil3.decode.Decoder
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder

/** The platform's own animated decoder from Android 9, Coil's GIF decoder before. */
actual fun animatedImageDecoder(): Decoder.Factory? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) AnimatedImageDecoder.Factory() else GifDecoder.Factory()
