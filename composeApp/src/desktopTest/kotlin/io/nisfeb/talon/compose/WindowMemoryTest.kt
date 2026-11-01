package io.nisfeb.talon.compose

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import io.nisfeb.talon.ui.JvmUiSettingsStore
import io.nisfeb.talon.ui.UiSettingsStore
import java.awt.Rectangle
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.launch

/**
 * Talon opens where and as large as it was left (a user's report,
 * 2026-11-01: it came back small, somewhere else, with the left-hand
 * columns hidden until it was stretched again).
 */
class WindowMemoryTest {
    private val dir = createTempDirectory("talon-window").toFile()
    private val file = File(dir, "window.json")
    private val oneScreen = listOf(Rectangle(0, 0, 1920, 1080))

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun floating(x: Int, y: Int, w: Int, h: Int) =
        WindowState(position = WindowPosition(x.dp, y.dp), size = DpSize(w.dp, h.dp))

    private fun reopened(screens: List<Rectangle> = oneScreen) = WindowMemory(JvmUiSettingsStore(file)) { screens }.state()

    private fun assertWindow(want: WindowState, got: WindowState) {
        assertEquals(want.placement, got.placement, "placement")
        assertEquals(want.position, got.position, "position")
        assertEquals(want.size, got.size, "size")
    }

    @Test
    fun `the place and size it was left at are where it opens next time`() {
        WindowMemory(JvmUiSettingsStore(file)) { oneScreen }.keep(floating(300, 200, 1400, 900))
        assertWindow(floating(300, 200, 1400, 900), reopened())
    }

    @Test
    fun `maximized opens maximized, and comes back down to the place it had`() {
        val memory = WindowMemory(JvmUiSettingsStore(file)) { oneScreen }
        memory.keep(floating(300, 200, 1400, 900))
        memory.keep(floating(0, 0, 1920, 1080).apply { placement = WindowPlacement.Maximized })
        assertWindow(floating(300, 200, 1400, 900).apply { placement = WindowPlacement.Maximized }, reopened())
    }

    @Test
    fun `maximized before it was ever moved keeps that`() {
        WindowMemory(JvmUiSettingsStore(file)) { oneScreen }
            .keep(floating(0, 0, 1920, 1080).apply { placement = WindowPlacement.Maximized })
        assertEquals(WindowPlacement.Maximized, reopened().placement)
    }

    // Full screen is a call's meeting mode. Windows parks a minimized
    // window at -32000, -32000.
    @Test
    fun `full screen and minimized are not kept, the place before them is`() {
        val memory = WindowMemory(JvmUiSettingsStore(file)) { oneScreen }
        memory.keep(floating(300, 200, 1400, 900))
        memory.keep(floating(0, 0, 1920, 1080).apply { placement = WindowPlacement.Fullscreen })
        memory.keep(floating(-32000, -32000, 160, 28).apply { isMinimized = true })
        assertWindow(floating(300, 200, 1400, 900), reopened())
    }

    @Test
    fun `a place on a second screen stands while that screen is there`() {
        val two = oneScreen + Rectangle(1920, 0, 2560, 1440)
        WindowMemory(JvmUiSettingsStore(file)) { two }.keep(floating(2100, 100, 2400, 1300))
        assertWindow(floating(2100, 100, 2400, 1300), reopened(two))
    }

    @Test
    fun `a place no screen shows any more opens in the middle of the main one, cut to fit it`() {
        WindowMemory(JvmUiSettingsStore(file)) { oneScreen }.keep(floating(2100, 100, 2400, 1300))
        val got = reopened()
        assertEquals(WindowPosition(Alignment.Center), got.position)
        assertEquals(DpSize(1920.dp, 1080.dp), got.size)
    }

    @Test
    fun `nothing kept, a damaged file, or nonsense opens as it always has`() {
        assertWindow(WindowState(), reopened())
        file.writeText("{not json")
        assertWindow(WindowState(), reopened())
        file.writeText("""{"x":0,"y":0,"width":-5,"height":600}""")
        assertWindow(WindowState(), reopened())
    }

    @Test
    fun `a write that fails is no crash, and an unchanged window is not written again`() {
        var writes = 0
        val counted = object : UiSettingsStore {
            override fun read(): String? = null
            override fun write(text: String) { writes++ }
        }
        val memory = WindowMemory(counted) { oneScreen }
        memory.keep(floating(300, 200, 1400, 900))
        memory.keep(floating(300, 200, 1400, 900))
        assertEquals(1, writes)
        val broken = object : UiSettingsStore {
            override fun read(): String? = null
            override fun write(text: String) = throw java.io.IOException("disk full")
        }
        WindowMemory(broken) { oneScreen }.keep(floating(300, 200, 1400, 900))
    }
}

/** A window the window manager shrinks as it opens gets its size back, once. */
class HoldOpeningSizeTest {
    private fun floating(w: Int, h: Int) = WindowState(position = WindowPosition(140.dp, 90.dp), size = DpSize(w.dp, h.dp))

    private fun set(state: WindowState, w: Int, h: Int) {
        state.size = DpSize(w.dp, h.dp)
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
    }

    @Test
    fun `shrunk by the window manager as it opens, it gets its size back`() = kotlinx.coroutines.runBlocking {
        val state = floating(1300, 850)
        val held = launch { holdOpeningSize(state) }
        kotlinx.coroutines.delay(100)
        set(state, 1290, 848)
        held.join()
        assertEquals(DpSize(1300.dp, 850.dp), state.size)
        // and only once: what the user does next stands
        set(state, 1000, 700)
        kotlinx.coroutines.delay(100)
        assertEquals(DpSize(1000.dp, 700.dp), state.size)
    }

    // Maximized at once, the size can grow before the placement says so.
    @Test
    fun `growing as it opens, or shrinking by more than a border, stands`() = kotlinx.coroutines.runBlocking {
        val grown = floating(1300, 850)
        val held = launch { holdOpeningSize(grown, within = kotlin.time.Duration.parse("300ms")) }
        kotlinx.coroutines.delay(50)
        set(grown, 3840, 1036)
        held.join()
        assertEquals(DpSize(3840.dp, 1036.dp), grown.size)
        val dragged = floating(1300, 850)
        val held2 = launch { holdOpeningSize(dragged, within = kotlin.time.Duration.parse("300ms")) }
        kotlinx.coroutines.delay(50)
        set(dragged, 1000, 850)
        held2.join()
        assertEquals(DpSize(1000.dp, 850.dp), dragged.size)
    }

    @Test
    fun `a window left alone, or opened maximized, is not touched`() = kotlinx.coroutines.runBlocking {
        val still = floating(1300, 850)
        holdOpeningSize(still, within = kotlin.time.Duration.parse("200ms"))
        assertEquals(DpSize(1300.dp, 850.dp), still.size)
        val maximized = floating(1300, 850).apply { placement = WindowPlacement.Maximized }
        val held = launch { holdOpeningSize(maximized) }
        set(maximized, 3840, 1036)
        held.join()
        assertEquals(DpSize(3840.dp, 1036.dp), maximized.size)
    }
}
