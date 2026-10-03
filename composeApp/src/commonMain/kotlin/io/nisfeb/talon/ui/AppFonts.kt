package io.nisfeb.talon.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import io.nisfeb.talon.util.dataDirPath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * One font file the owner installed: a weight and style of a family.
 * Kept by its contents' hash, on this device and on their ship, so the
 * same file installed twice is one font, and a copy fetched from the
 * ship can be checked against what was installed.
 */
@Serializable
data class InstalledFont(
    /** sha256 of the file, hex. */
    val id: String,
    val family: String,
    val weight: Int = 400,
    val italic: Boolean = false,
    /** What the file was called, for the list. */
    val fileName: String = "",
    /**
     * Its name in the ship's talon/fonts where the owner put it there
     * themselves (grubbery's page keeps a file's own name); null for one
     * Talon put there, as `<id>.font`.
     */
    val shipName: String? = null,
) {
    /** The file's name in the ship's talon/fonts. */
    val shipFile: String get() = shipName ?: "$id.font"
}

/**
 * The fonts the owner installed and the one the app is set in, synced
 * across their devices. The text size is not here: a phone and a
 * desktop want different sizes, so it stays per device.
 */
@Serializable
data class FontSettings(
    val fonts: List<InstalledFont> = emptyList(),
    /** The family text is set in: null for the system's, [SERIF], [MONOSPACE], or an installed family. */
    val family: String? = null,
    /**
     * Files the owner removed, by id. A removal is said, never left to
     * an absence: another device's list without a font must not take
     * it off this one, and a removal must reach every device.
     */
    val removed: List<String> = emptyList(),
) {
    /** The installed families, by name. */
    val families: List<String> get() = fonts.map { it.family }.distinct().sortedBy { it.lowercase() }

    /**
     * Another device's settings taken in: the fonts both have, less any
     * either removed; its choice of family, the more recent word.
     */
    fun mergedWith(remote: FontSettings): FontSettings {
        val gone = (removed + remote.removed).toSet()
        val fonts = (remote.fonts + fonts).distinctBy { it.id }.filter { it.id !in gone }
        return FontSettings(fonts, remote.family, gone.sorted())
    }

    /**
     * [f] installed: a file removed before comes back, and one already
     * installed for the same family, weight and style is replaced (and
     * said removed, or another device's copy would bring it back).
     */
    fun adding(f: InstalledFont): FontSettings {
        val replaced = fonts.filter { it.id != f.id && it.family == f.family && it.weight == f.weight && it.italic == f.italic }
        return copy(
            fonts = fonts.filter { it.id != f.id && it !in replaced } + f,
            removed = (removed - f.id + replaced.map { it.id }).distinct().sorted(),
        )
    }

    /** A family and all its files taken off, here and on every device. */
    fun removingFamily(name: String): FontSettings {
        val ids = fonts.filter { it.family == name }.map { it.id }
        return copy(
            fonts = fonts.filter { it.family != name },
            removed = (removed + ids).distinct().sorted(),
            family = family.takeIf { it != name },
        )
    }

    fun toJson(): String = JSON.encodeToString(serializer(), this)

    companion object {
        const val SERIF = "serif"
        const val MONOSPACE = "monospace"
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun fromJson(text: String?): FontSettings? =
            text?.let { runCatching { JSON.decodeFromString(serializer(), it) }.getOrNull() }
    }
}

/** What a font file says of itself. */
data class FontInfo(val family: String, val weight: Int, val italic: Boolean)

/**
 * The family, weight and style a TrueType or OpenType file names in its
 * own tables (name, OS/2), or why it is not one this app can set text
 * in, in words for the person who chose it.
 */
fun readFontInfo(bytes: ByteArray): Result<FontInfo> = runCatching {
    fun u16(at: Int): Int {
        require(at >= 0 && at + 2 <= bytes.size) { NOT_A_FONT }
        return ((bytes[at].toInt() and 0xff) shl 8) or (bytes[at + 1].toInt() and 0xff)
    }
    fun u32(at: Int): Long = (u16(at).toLong() shl 16) or u16(at + 2).toLong()
    if (bytes.size < 12) error(NOT_A_FONT)
    when (bytes.copyOfRange(0, 4).decodeToString()) {
        "wOFF", "wOF2" -> error("That is a web font (WOFF). Install the .ttf or .otf from the same download.")
        "ttcf" -> error("That is a font collection (.ttc). Install the single fonts it holds instead.")
        "OTTO", "true" -> Unit
        else -> require(u32(0) == 0x00010000L) { NOT_A_FONT }
    }
    val tables = (0 until u16(4)).associate { i ->
        val rec = 12 + i * 16
        bytes.copyOfRange(rec, rec + 4).decodeToString() to u32(rec + 8).toInt()
    }
    val name = tables["name"] ?: error(NOT_A_FONT)
    val strings = name + u16(name + 4)
    fun nameOf(id: Int): String? {
        var mac: String? = null
        for (i in 0 until u16(name + 2)) {
            val r = name + 6 + i * 12
            if (u16(r + 6) != id) continue
            val len = u16(r + 8)
            val at = strings + u16(r + 10)
            if (at + len > bytes.size) continue
            when (u16(r)) {
                // Windows, UTF-16 big-endian: what nearly every font carries.
                3 -> return CharArray(len / 2) { k -> u16(at + 2 * k).toChar() }.concatToString()
                1 -> mac = bytes.copyOfRange(at, at + len).decodeToString()
            }
        }
        return mac
    }
    // The typographic family groups weights a legacy family splits.
    val family = (nameOf(16) ?: nameOf(1))?.trim()?.takeIf { it.isNotEmpty() } ?: error(NOT_A_FONT)
    val os2 = tables["OS/2"]
    val weight = os2?.let { u16(it + 4) }?.takeIf { it in 1..1000 } ?: 400
    val italic = os2?.let { u16(it + 62) and 1 == 1 }
        ?: (nameOf(2)?.contains("italic", ignoreCase = true) == true)
    FontInfo(family, weight, italic)
}

private const val NOT_A_FONT = "That is not a font file this app can use. A .ttf or .otf is."

/**
 * The font files on this device, by id: in the app's data dir, which
 * the system does not clear, since a font installed with no ship to
 * share it through has no other copy.
 */
class FontFiles(private val dir: Path = "$dataDirPath/fonts".toPath()) {
    private val fs = FileSystem.SYSTEM
    private val _version = MutableStateFlow(0)
    /** Bumped when a file comes or goes, for whatever sets text in them. */
    val version: StateFlow<Int> = _version.asStateFlow()

    fun path(id: String): Path = dir / "$id.font"
    fun has(id: String): Boolean = fs.exists(path(id))
    fun read(id: String): ByteArray? = runCatching { fs.read(path(id)) { readByteArray() } }.getOrNull()

    /** Written whole or not at all: a half-written font must never be read. */
    fun write(id: String, bytes: ByteArray) {
        fs.createDirectories(dir)
        val part = dir / "$id.part"
        fs.write(part) { write(bytes) }
        fs.atomicMove(part, path(id))
        _version.value++
    }

    fun delete(id: String) {
        runCatching { fs.delete(path(id)) }
        _version.value++
    }

    companion object {
        val default: FontFiles by lazy { FontFiles() }
    }
}

/** A file's id: the hex sha256 of what it holds. */
fun fontId(bytes: ByteArray): String = bytes.toByteString().sha256().hex()

/** A font for text, from a file on disk. */
expect fun fontFromFile(path: String, weight: FontWeight, style: FontStyle): Font

/**
 * Whether the platform can set text in the file at [path]. Asked before
 * a font is used at all: one that fails to load while text is laid out
 * crashes the app on every launch, with no way left to remove it.
 */
expect fun fontLoads(path: String): Boolean

/** The family [s] chooses, from the files this device has; null for the system's. */
fun appFontFamily(s: FontSettings, files: FontFiles): FontFamily? = when (s.family) {
    null -> null
    FontSettings.SERIF -> FontFamily.Serif
    FontSettings.MONOSPACE -> FontFamily.Monospace
    else -> s.fonts
        .filter { it.family == s.family && files.has(it.id) }
        .map { fontFromFile(files.path(it.id).toString(), FontWeight(it.weight), if (it.italic) FontStyle.Italic else FontStyle.Normal) }
        // Until its files arrive, the system's.
        .takeIf { it.isNotEmpty() }
        ?.let { FontFamily(it) }
}

/** The family the app is set in, following the setting and the files as they arrive. */
@Composable
fun rememberAppFontFamily(settings: UiSettings, files: FontFiles = FontFiles.default): FontFamily? {
    val chosen by settings.fontSettings.collectAsState()
    val have by files.version.collectAsState()
    return remember(chosen, have) { appFontFamily(chosen, files) }
}
