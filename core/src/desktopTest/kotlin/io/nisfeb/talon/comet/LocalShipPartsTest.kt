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
}
