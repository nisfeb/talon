package io.nisfeb.talon.comet

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalShipPartsTest {
    // ── VereRelease ──────────────────────────────────────────────

    @Test
    fun `runtime asset follows os and arch`() {
        assertEquals("linux-x86_64.tgz", VereRelease.assetFor("Linux", "amd64"))
        assertEquals("linux-aarch64.tgz", VereRelease.assetFor("Linux", "aarch64"))
        assertEquals("macos-aarch64.tgz", VereRelease.assetFor("Mac OS X", "aarch64"))
        assertEquals("macos-x86_64.tgz", VereRelease.assetFor("Mac OS X", "x86_64"))
        assertEquals("windows-x86_64.tgz", VereRelease.assetFor("Windows 11", "amd64"))
        assertNull(VereRelease.assetFor("Windows 11", "aarch64"))
        assertNull(VereRelease.assetFor("FreeBSD", "amd64"))
    }

    @Test
    fun `binary keeps the tarball member name and windows gets exe`() {
        assertEquals("vere-v4.6-linux-x86_64", VereRelease.memberName("linux-x86_64.tgz"))
        assertEquals("vere-v4.6-linux-x86_64", VereRelease.binaryName("linux-x86_64.tgz"))
        assertEquals("vere-v4.6-windows-x86_64.exe", VereRelease.binaryName("windows-x86_64.tgz"))
        assertEquals(
            "https://github.com/urbit/vere/releases/download/vere-v4.6/macos-aarch64.tgz",
            VereRelease.downloadUrl("macos-aarch64.tgz"),
        )
    }

    @Test
    fun `release tags and version order`() {
        assertEquals("4.7", VereRelease.versionFromTag("vere-v4.7"))
        assertNull(VereRelease.versionFromTag("vere-v4.7-rc1"))
        assertTrue(VereRelease.isNewer("4.7", "4.6"))
        assertTrue(VereRelease.isNewer("5.0", "4.12"))
        assertFalse(VereRelease.isNewer("4.6", "4.6"))
        assertFalse(VereRelease.isNewer("4.6", "4.10"))
    }

    // ── CometTerminal ────────────────────────────────────────────

    private val boot = """
        boot: mining a comet. May take up to an hour.
        boot: found comet ~dotwet-monbep-risnes-tabwex--midhec-micdus-noltyc-binzod
        boot: verifying keys
    """.trimIndent().replace("\n", "\r\n")

    @Test
    fun `prompt, mined name, code and detail come off a raw terminal`() {
        // The prompt abbreviates a comet with an underscore; the boot
        // line has the full name.
        val prompt = boot + "\r\n[?25h[2K~dotwet_binzod:dojo> "
        assertEquals("~dotwet_binzod", CometTerminal.promptShip(prompt))
        assertEquals("~dotwet-monbep-risnes-tabwex--midhec-micdus-noltyc-binzod", CometTerminal.minedShip(prompt))
        assertNull(CometTerminal.promptShip(boot))
        assertEquals("boot: verifying keys", CometTerminal.lastDetail(boot))
        val answer = "+code\r\n[1mhanmec-tirsup-fodpec-sablud[0m\r\n~zod:dojo> "
        assertEquals("hanmec-tirsup-fodpec-sablud", CometTerminal.code(answer))
        assertNull(CometTerminal.code("~zod:dojo> +code\r\n"))
        // A comet's name is not its code, however code-like its halves look.
        assertNull(CometTerminal.code("ames: ~tabdec-hopsep-rilmer-riglup--dolsev-lostug-tilter-binzod\r\n"))
        assertEquals(
            "hanmec-tirsup-fodpec-sablud",
            CometTerminal.code("drum: ~tabdec-hopsep-rilmer-riglup--dolsev-lostug-tilter-binzod\r\n hanmec-tirsup-fodpec-sablud \r\n~tabdec_binzod:dojo> "),
        )
        // The busy spinner is redrawn with backspaces around the answer.
        assertEquals(
            "hanmec-tirsup-fodpec-sablud",
            CometTerminal.code("+code\r\n/«behn»\u0008\u0008\u0008\u0008\u0008\u0008\u0008-«behn»\u0008\u0008\u0008\u0008\u0008\u0008\u0008hanmec-tirsup-fodpec-sablud\r\n"),
        )
    }

    @Test
    fun `public port is read from http ports`() {
        assertEquals(8099, CometTerminal.publicPort("12321 insecure loopback\n8099 insecure public\n"))
        assertNull(CometTerminal.publicPort("12321 insecure loopback\n"))
    }

    // ── Tarball ──────────────────────────────────────────────────

    private fun tar(vararg members: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((name, body) in members) {
            val h = ByteArray(512)
            name.toByteArray().copyInto(h, 0)
            "0000755".toByteArray().copyInto(h, 100)
            "0001000".toByteArray().copyInto(h, 108)
            "0001000".toByteArray().copyInto(h, 116)
            body.size.toString(8).padStart(11, '0').toByteArray().copyInto(h, 124)
            "00000000000".toByteArray().copyInto(h, 136)
            "        ".toByteArray().copyInto(h, 148)
            h[156] = '0'.code.toByte()
            "ustar".toByteArray().copyInto(h, 257)
            val sum = h.sumOf { it.toInt() and 0xff }
            sum.toString(8).padStart(6, '0').toByteArray().copyInto(h, 148)
            h[154] = 0
            h[155] = ' '.code.toByte()
            out.write(h)
            out.write(body)
            val pad = (512 - body.size % 512) % 512
            out.write(ByteArray(pad))
        }
        out.write(ByteArray(1024))
        return out.toByteArray()
    }

    @Test
    fun `a single member is pulled out of a tar and a missing one is reported`() {
        val bytes = "hello vere".toByteArray()
        val archive = tar("README" to "x".toByteArray(), "vere-v4.6-linux-x86_64" to bytes)
        val dest = File.createTempFile("vere", ".bin").apply { deleteOnExit() }
        assertTrue(Tarball.extractMember(ByteArrayInputStream(archive), "vere-v4.6-linux-x86_64", dest))
        assertEquals("hello vere", dest.readText())
        assertFalse(Tarball.extractMember(ByteArrayInputStream(archive), "nope", dest))
    }

    // A binary cut short was trusted on every start after: it was written
    // in place, and only its size over 1 MB was checked.
    @Test
    fun `a member cut short leaves nothing at the destination`() {
        val bytes = ByteArray(5000) { it.toByte() }
        val archive = tar("vere-v4.6-linux-x86_64" to bytes)
        val dir = File(System.getProperty("java.io.tmpdir"), "talon-tar-${System.nanoTime()}").apply { mkdirs() }
        val dest = File(dir, "vere")
        val cut = archive.copyOf(512 + 3000)
        assertTrue(runCatching { Tarball.extractMember(ByteArrayInputStream(cut), "vere-v4.6-linux-x86_64", dest) }.isFailure)
        assertFalse(dest.exists())
        assertEquals(emptyList(), dir.list()!!.toList())
        assertTrue(Tarball.extractMember(ByteArrayInputStream(archive), "vere-v4.6-linux-x86_64", dest))
        assertTrue(bytes.contentEquals(dest.readBytes()))
        assertEquals(listOf("vere"), dir.list()!!.toList())
        dir.deleteRecursively()
    }

    // ── TerminalScreen: the dojo panel's view ───────────────────

    private val esc = "\u001b"
    private val bs7 = "\u0008".repeat(7)
    private fun spin(vararg frames: String) = frames.joinToString("") { "$it$bs7" }
    private fun drawn(vararg chunks: String) = TerminalScreen().apply { chunks.forEach(::feed) }

    @Test
    fun `a prompt redrawn in place, with a command, is drawn once`() {
        // A user's paste: "~picwyc_samzod:dojo> ~picwyc_samzod:dojo> .^(...)> .^(...)",
        // every redraw of one line laid side by side.
        val screen = drawn(
            "\r\n$esc[?25h$esc[2K~dotwet_binzod:dojo> ",
            "\r$esc[2K~dotwet_binzod:dojo> (add 2 2)",
            "\r\n$esc[2K> (add 2 2)\r\n4\r\n$esc[2K~dotwet_binzod:dojo> ",
        )
        assertEquals(listOf("", "~dotwet_binzod:dojo> (add 2 2)", "> (add 2 2)", "4", "~dotwet_binzod:dojo>"), screen.text().lines())
    }

    @Test
    fun `a real comet's dojo, two lines typed and answered and a busy spell, draws as a terminal does`() {
        // Cut from vere 4.6's terminal on a new comet, 2026-10-30: each line
        // typed is redrawn on the prompt, replaced by its "> line" print-back
        // and answered; then ames lines, and the spinner while an http event
        // ran, ended by the prompt drawn again.
        val raw = javaClass.getResource("/comet/dojo-sample.txt")!!.readText()
        val spinEnds = raw.lastIndexOf("$esc[40;1H$esc[K~dotful_samzod:dojo> $esc[u")
        val busy = drawn(raw.substring(0, spinEnds))
        assertTrue(busy.busy(), "the spinner is up")
        assertEquals("~dotful_samzod:dojo>", busy.text().lines().last(), "and not drawn")
        val done = drawn(raw)
        assertFalse(done.busy())
        assertEquals(
            listOf("> (add 109 1)", "110", "> (add 110 1)", "111", "ames: packet dropped (1000 total)", "l2-sig-failed", "~dotful_samzod:dojo>"),
            done.text().lines(),
        )
        // Read in pieces, as the pty hands it over.
        val pieces = TerminalScreen().apply { raw.chunked(37).forEach(::feed) }
        assertEquals(done.text(), pieces.text())
    }

    @Test
    fun `lines drawn on the bottom row and scrolled up read as lines`() {
        // As vere 4.6 printed a comet's boot: to row 40, erase, the line in
        // grey, a newline; then back to the row, the cursor put back, and
        // the next line drawn over it.
        val screen = drawn(
            "boot: 1-21\r\n",
            "$esc[40;1H$esc[K$esc[38;5;244m1-b$esc[0m\n",
            "$esc[40;1H$esc[K$esc[u$esc[40;1H$esc[K$esc[38;5;244m1-c (compiling compiler, wait a few minutes)$esc[0m\n",
            "$esc[40;1H$esc[K$esc[u$esc[40;1H$esc[K~dotful_samzod:dojo> ",
            "$esc[s$esc[40;1H$esc[K$esc[38;5;244mmdns: getaddrinfo error: unknown node or service$esc[0m\n",
            "$esc[40;1H$esc[K$esc[u$esc[40;1H$esc[K~dotful_samzod:dojo> $esc[40;22H",
        )
        assertEquals(
            listOf("boot: 1-21", "1-b", "1-c (compiling compiler, wait a few minutes)", "mdns: getaddrinfo error: unknown node or service", "~dotful_samzod:dojo>"),
            screen.text().lines(),
            "no prompt glued to the front of every line, as the stripped text had",
        )
        // Saved and put back: the column, on the prompt's row.
        assertEquals("ab-Xd", drawn("ab$esc[s-cd$esc[u$esc[1CX").text())
    }

    @Test
    fun `the spinner says busy while it turns, and is not drawn`() {
        val screen = drawn("~dotwet_binzod:dojo> +code\r\n", spin("|«behn»", "/«behn»", "-«behn»"))
        assertTrue(screen.busy(), "an event is running")
        assertEquals(listOf("~dotwet_binzod:dojo> +code", ""), screen.text().lines())
        // The answer overwrites the last frame, as +code's did.
        screen.feed("hanmec-tirsup-fodpec-sablud\r\n~dotwet_binzod:dojo> ")
        assertFalse(screen.busy())
        assertEquals(listOf("~dotwet_binzod:dojo> +code", "hanmec-tirsup-fodpec-sablud", "~dotwet_binzod:dojo>"), screen.text().lines())
        // On the prompt line too, and a prompt drawn again clears it.
        screen.feed(spin("\\«clay»"))
        assertTrue(screen.busy())
        screen.feed("\r$esc[2K~dotwet_binzod:dojo> ")
        assertFalse(screen.busy())
    }

    @Test
    fun `minutes of spinning keep the lines before it`() {
        // A raw window filled with frames in two minutes and pushed out what came before.
        val screen = drawn("kiln: finished downloading update for %groups\r\n~dotwet_binzod:dojo> ")
        repeat(20_000) { screen.feed(spin("|«behn»", "/«behn»")) }
        assertEquals(listOf("kiln: finished downloading update for %groups", "~dotwet_binzod:dojo>"), screen.text().lines())
        assertTrue(screen.busy())
    }

    @Test
    fun `an escape cut between two reads still draws`() {
        val screen = drawn("old text\r$esc[2", "K~dotwet_binzod:dojo> ")
        assertEquals("~dotwet_binzod:dojo>", screen.text())
    }

    @Test
    fun `the cursor moves and erases as vere asks`() {
        // To a column, right, left, then erase to the end of the line.
        assertEquals("ab", drawn("abcdef$esc[3G$esc[K").text())
        assertEquals("abXdef", drawn("abcdef$esc[1G$esc[2CX").text())
        assertEquals("abcdXf", drawn("abcdef$esc[2DX").text())
        assertEquals("   def", drawn("abcdef$esc[1G$esc[2C$esc[1K").text())
        assertEquals("X", drawn("\u0008\u0008X").text(), "no further left than the start")
        assertEquals("ab", drawn("ab$esc[31mcolour$esc[0m$esc[6D$esc[K").text())
        assertEquals("abcxyz", drawn("abc$esc(Bxyz").text(), "a character-set choice draws nothing")
        assertEquals("abcxyz", drawn("abc${esc}7xyz").text(), "nor does a two-character escape")
    }

    @Test
    fun `only the newest lines are kept`() {
        val screen = TerminalScreen(maxLines = 3)
        (1..10).forEach { screen.feed("line $it\r\n") }
        assertEquals(listOf("line 9", "line 10", ""), screen.text().lines())
    }
}
