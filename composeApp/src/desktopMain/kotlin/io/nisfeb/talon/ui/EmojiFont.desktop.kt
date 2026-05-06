package io.nisfeb.talon.ui

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import io.nisfeb.talon.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

private const val TAG = "EmojiFont"

/**
 * Resolve the user's system color emoji font and expose it as a
 * Compose [FontFamily]. Compose Desktop's text shaper on Linux
 * doesn't reliably hit fontconfig-priority for color emoji fallback
 * (DejaVu Sans's monochrome glyphs win out), but if we hand Skia
 * the actual font file, COLR / CBDT tables render in color.
 *
 * Resolution path (first hit wins):
 *   1. `fc-match emoji --format='%{file}'` — what fontconfig
 *      thinks is the user's emoji font. Authoritative on every
 *      modern Linux desktop.
 *   2. A short list of well-known package paths — fallback for
 *      environments without `fc-match` (rare).
 *   3. [FontFamily.Default] — graceful degradation. Emojis
 *      render whatever Compose picks by default (likely B&W),
 *      but text is otherwise unaffected.
 *
 * No bundle — the AppImage trusts the host system to have a color
 * emoji font installed. ~all distros ship one (Arch needs
 * `noto-fonts-emoji`, Debian `fonts-noto-color-emoji`, Fedora
 * `google-noto-emoji-color-fonts`).
 */
private val SystemEmojiFamily: FontFamily by lazy {
    val path = resolveSystemEmojiPath() ?: run {
        Log.w(TAG, "no system color emoji font found — emojis will render via Compose default")
        return@lazy FontFamily.Default
    }
    runCatching {
        val bytes = File(path).readBytes()
        FontFamily(
            Font(
                identity = "SystemEmoji:$path",
                data = bytes,
                weight = FontWeight.Normal,
                style = FontStyle.Normal,
            ),
        )
    }.getOrElse {
        Log.w(TAG, "failed to load system emoji font at $path", it)
        FontFamily.Default
    }
}

private fun resolveSystemEmojiPath(): String? {
    runCatching {
        val proc = ProcessBuilder("fc-match", "emoji", "--format=%{file}")
            .redirectErrorStream(true)
            .start()
        if (!proc.waitFor(2, TimeUnit.SECONDS)) {
            proc.destroy()
            return@runCatching null
        }
        val out = proc.inputStream.bufferedReader().readText().trim()
        if (out.isNotEmpty() && File(out).exists()) return out
    }
    val candidates = listOf(
        "/usr/share/fonts/TTF/NotoColorEmoji.ttf",
        "/usr/share/fonts/noto/NotoColorEmoji.ttf",
        "/usr/share/fonts/noto-color-emoji/NotoColorEmoji.ttf",
        "/usr/share/fonts/google-noto-emoji/NotoColorEmoji.ttf",
        "/usr/share/fonts/truetype/noto/NotoColorEmoji.ttf",
    )
    return candidates.firstOrNull { File(it).exists() }
}

actual val EmojiFontFamily: FontFamily get() = SystemEmojiFamily
