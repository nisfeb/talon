package io.nisfeb.talon.ui

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font

/**
 * Loads the bundled `NotoColorEmoji.ttf` (~10 MB) from JAR resources
 * once at first access and exposes it as a [FontFamily]. Lazy so
 * cold launches don't pay the read upfront — the moment any
 * emoji-only Text composes, we slurp the bytes and hand them to
 * Compose's Skia-backed font loader.
 */
private val NotoColorEmojiFamily: FontFamily by lazy {
    val bytes = checkNotNull(
        EmojiFontHandle::class.java.getResourceAsStream("/fonts/NotoColorEmoji.ttf"),
    ) { "NotoColorEmoji.ttf not bundled at /fonts/NotoColorEmoji.ttf" }.use { it.readBytes() }

    FontFamily(
        Font(
            identity = "NotoColorEmoji",
            data = bytes,
            weight = FontWeight.Normal,
            style = FontStyle.Normal,
        ),
    )
}

private object EmojiFontHandle

actual val EmojiFontFamily: FontFamily get() = NotoColorEmojiFamily
