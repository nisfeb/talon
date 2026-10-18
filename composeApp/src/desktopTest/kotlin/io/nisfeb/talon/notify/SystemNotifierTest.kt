package io.nisfeb.talon.notify

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SystemNotifierTest {
    @Test fun `the id is read out of gdbus's answer`() {
        assertEquals(42L, gdbusId("(uint32 42,)\n"))
        assertNull(gdbusId("Error: GDBus.Error:org.freedesktop.DBus.Error.ServiceUnknown"))
    }

    /** A process that printed [out] and exited with [code]; nothing is run. */
    private class Ran(private val out: String, private val code: Int) : Process() {
        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(out.toByteArray())
        override fun getErrorStream(): InputStream = InputStream.nullInputStream()
        override fun waitFor(): Int = code
        override fun exitValue(): Int = code
        override fun destroy() {}
    }

    @Test fun `gdbus's positionals come after a double dash`() {
        val args = gdbusNotifyArgs("/icon.png", "-title", "-body")
        assertEquals(listOf("--", "Talon", "0", "/icon.png", "-title", "-body", "[]", "{}", "-1"), args.dropWhile { it != "--" })
        assertEquals(listOf("gdbus", "call", "--session"), args.take(3))
    }

    @Test fun `notify-send's title and body come after a double dash`() {
        val args = notifySendArgs("/icon.png", "-title", "-body")
        assertEquals(listOf("--", "-title", "-body"), args.takeLast(3))
        assertTrue(listOf("-i", "/icon.png") == args.subList(args.indexOf("-i"), args.indexOf("-i") + 2))
        assertTrue("-i" !in notifySendArgs(null, "t", "b"))
    }

    private fun notifier(answers: Map<String, Ran>, ran: MutableList<String>, tray: MutableList<String>) = SystemNotifier(
        trayFallback = { t, _ -> tray += t },
        linux = true,
        start = { args -> ran += args.first(); answers.getValue(args.first()) },
    )

    @Test fun `a refused notify-send falls to gdbus`() {
        val ran = mutableListOf<String>(); val tray = mutableListOf<String>()
        val n = notifier(mapOf("notify-send" to Ran("", 1), "gdbus" to Ran("(uint32 7,)\n", 0)), ran, tray)
        n.deliver("a", "b", null)
        n.deliver("c", "d", null)
        // The demotion holds: the second toast goes straight to gdbus.
        assertEquals(listOf("notify-send", "gdbus", "gdbus"), ran)
        assertEquals(emptyList(), tray)
    }

    @Test fun `a refused gdbus falls to the tray`() {
        val ran = mutableListOf<String>(); val tray = mutableListOf<String>()
        val n = notifier(mapOf("notify-send" to Ran("", 1), "gdbus" to Ran("Usage: gdbus call", 1)), ran, tray)
        n.deliver("a", "b", null)
        n.deliver("c", "d", null)
        assertEquals(listOf("notify-send", "gdbus"), ran)
        assertEquals(listOf("a", "c"), tray)
    }

    @Test fun `a shown notify-send toast stays on notify-send`() {
        val ran = mutableListOf<String>(); val tray = mutableListOf<String>()
        val n = notifier(mapOf("notify-send" to Ran("9\n", 0)), ran, tray)
        n.deliver("a", "b", "~zod")
        n.deliver("c", "d", null)
        assertEquals(listOf("notify-send", "notify-send"), ran)
        assertEquals(emptyList(), tray)
    }

    @Test fun `off linux it is the tray from the start`() {
        val ran = mutableListOf<String>(); val tray = mutableListOf<String>()
        SystemNotifier(trayFallback = { t, _ -> tray += t }, linux = false, start = { ran += it.first(); Ran("", 0) })
            .deliver("a", "b", null)
        assertEquals(emptyList(), ran)
        assertEquals(listOf("a"), tray)
    }
}
