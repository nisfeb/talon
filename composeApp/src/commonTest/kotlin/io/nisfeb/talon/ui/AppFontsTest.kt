package io.nisfeb.talon.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fonts the owner installs: what a file says of itself, and how the list
 * of them travels between devices. An empty list from another device
 * takes nothing off this one; a removal does, everywhere.
 */
class AppFontsTest {
    // ─── a font file, as the tables in it say ─────────────────────

    /** A font file made of [tables], as sfnt lays them out. */
    private fun sfnt(tables: Map<String, ByteArray>, tag: ByteArray = byteArrayOf(0, 1, 0, 0)): ByteArray {
        val out = ArrayList<Byte>()
        fun u16(v: Int) { out += (v shr 8).toByte(); out += v.toByte() }
        fun u32(v: Int) { u16(v ushr 16); u16(v and 0xffff) }
        out.addAll(tag.toList()); u16(tables.size); u16(0); u16(0); u16(0)
        var at = 12 + 16 * tables.size
        tables.forEach { (name, data) ->
            out.addAll(name.encodeToByteArray().toList()); u32(0); u32(at); u32(data.size)
            at += data.size
        }
        tables.values.forEach { out.addAll(it.toList()) }
        return out.toByteArray()
    }

    /** A name table with Windows (UTF-16BE) strings by name id. */
    private fun names(vararg byId: Pair<Int, String>): ByteArray {
        val strings = byId.map { (_, s) -> s.flatMap { c -> listOf((c.code shr 8).toByte(), c.code.toByte()) }.toByteArray() }
        val out = ArrayList<Byte>()
        fun u16(v: Int) { out += (v shr 8).toByte(); out += v.toByte() }
        u16(0); u16(byId.size); u16(6 + 12 * byId.size)
        var off = 0
        byId.forEachIndexed { i, (id, _) ->
            u16(3); u16(1); u16(0x409); u16(id); u16(strings[i].size); u16(off)
            off += strings[i].size
        }
        strings.forEach { out.addAll(it.toList()) }
        return out.toByteArray()
    }

    private fun os2(weight: Int, italic: Boolean) = ByteArray(96).also {
        it[4] = (weight shr 8).toByte(); it[5] = weight.toByte()
        it[63] = if (italic) 1 else 0
    }

    @Test
    fun `a font says its family, weight and style`() {
        val f = sfnt(mapOf("name" to names(1 to "Testa", 2 to "Bold Italic"), "OS/2" to os2(700, true)))
        assertEquals(FontInfo("Testa", 700, true), readFontInfo(f).getOrThrow())
    }

    // The typographic family groups the weights a legacy family splits:
    // "Testa Light" is family "Testa", weight 300.
    @Test
    fun `the typographic family is preferred, and a font with no OS-2 reads its style from its name`() {
        val f = sfnt(mapOf("name" to names(1 to "Testa Light", 2 to "Italic", 16 to "Testa")), tag = "OTTO".encodeToByteArray())
        assertEquals(FontInfo("Testa", 400, true), readFontInfo(f).getOrThrow())
    }

    @Test
    fun `what is not a font this app can use says why, and never throws`() {
        fun why(b: ByteArray) = readFontInfo(b).exceptionOrNull()?.message.orEmpty()
        assertTrue("web font" in why("wOF2".encodeToByteArray() + ByteArray(40)), "WOFF2")
        assertTrue("collection" in why("ttcf".encodeToByteArray() + ByteArray(40)), "TTC")
        assertTrue(".ttf or .otf" in why("hello, world, this is text".encodeToByteArray()), "text")
        assertTrue(".ttf or .otf" in why(ByteArray(3)), "too short")
        // Tables that point past the end of the file.
        val broken = sfnt(mapOf("name" to names(1 to "Testa"))).copyOf(30)
        assertTrue(".ttf or .otf" in why(broken), "truncated")
        assertTrue(".ttf or .otf" in why(sfnt(mapOf("OS/2" to os2(400, false)))), "no name table")
    }

    // ─── the list, across devices ─────────────────────────────────

    private fun font(id: String, family: String = "Testa", weight: Int = 400, italic: Boolean = false) =
        InstalledFont(id, family, weight, italic, "$id.ttf")

    @Test
    fun `another device's list adds to this one, and an empty one takes nothing off`() {
        val here = FontSettings(listOf(font("a")), family = "Testa")
        val merged = here.mergedWith(FontSettings(listOf(font("b", "Other")), family = "Other"))
        assertEquals(listOf("a", "b"), merged.fonts.map { it.id }.sorted())
        assertEquals("Other", merged.family, "the choice is the latest word")
        assertEquals(listOf("a"), here.mergedWith(FontSettings()).fonts.map { it.id }, "empty is not a removal")
    }

    @Test
    fun `a removal reaches every device, and adding the file again brings it back`() {
        val here = FontSettings(listOf(font("a"), font("b", "Other")), family = "Other")
        val removed = here.removingFamily("Other")
        assertEquals(listOf("a"), removed.fonts.map { it.id })
        assertNull(removed.family, "the system's, once the chosen family is gone")
        // Another device that still lists it does not bring it back.
        assertEquals(listOf("a"), removed.mergedWith(here.copy(removed = emptyList())).fonts.map { it.id })
        // Nor does this device keep one another device removed.
        assertEquals(listOf("a"), here.mergedWith(removed).fonts.map { it.id })
        // Installed again, it is back, and says so to the others.
        val again = removed.adding(font("b", "Other"))
        assertEquals(listOf("a", "b"), again.fonts.map { it.id })
        assertTrue("b" !in again.removed)
    }

    @Test
    fun `a new file for a weight and style already installed replaces it everywhere`() {
        val here = FontSettings(listOf(font("old", weight = 700), font("reg")))
        val after = here.adding(font("new", weight = 700))
        assertEquals(listOf("new", "reg"), after.fonts.map { it.id }.sorted())
        assertEquals(listOf("old"), after.removed)
        // An italic of the same weight is a font of its own.
        assertEquals(3, after.adding(font("it", weight = 700, italic = true)).fonts.size)
    }

    @Test
    fun `the settings survive the trip as text`() {
        val s = FontSettings(listOf(font("a", weight = 300, italic = true)), family = "Testa", removed = listOf("z"))
        assertEquals(s, FontSettings.fromJson(s.toJson()))
        assertNull(FontSettings.fromJson("not json"))
        assertEquals(FontSettings(), FontSettings.fromJson("{}"), "fields a writer left out")
    }
}
