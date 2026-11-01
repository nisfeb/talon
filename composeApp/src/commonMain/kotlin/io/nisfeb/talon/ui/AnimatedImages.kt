package io.nisfeb.talon.ui

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.decode.Decoder

/**
 * The decoder that makes a GIF move, where the platform has one: Coil's
 * own decoders on Android (coil-gif), Skia's codec on desktop and iOS.
 * Without it Coil decodes a GIF to its first frame, and a GIF in the
 * chat or the picture viewer stood still (a user's report, 2026-11-01).
 */
expect fun animatedImageDecoder(): Decoder.Factory?

/** The app's image loader: Coil's defaults, with [animatedImageDecoder] first. [more] is the shell's own. */
fun talonImageLoader(context: PlatformContext, more: ImageLoader.Builder.() -> Unit = {}): ImageLoader =
    ImageLoader.Builder(context)
        .components { animatedImageDecoder()?.let { add(it) } }
        .apply(more)
        .build()
