package io.nisfeb.talon.compose

import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import io.nisfeb.talon.ui.UiSettingsStore
import io.nisfeb.talon.util.Log
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The window's last floating place and size, in dp, and whether it was maximized. */
@Serializable
internal data class SavedWindow(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val maximized: Boolean = false,
)

/**
 * The main window opens where and as large as it was when Talon last
 * closed (a user's report, 2026-11-01: it came back small, and the
 * left-hand columns hidden until it was stretched again). Kept per
 * machine in [store], never synced: a place on one desk means nothing
 * on another.
 */
internal class WindowMemory(
    private val store: UiSettingsStore,
    private val screens: () -> List<Rectangle> = ::screenBounds,
) {
    private var kept: SavedWindow? = runCatching { store.read()?.let { json.decodeFromString<SavedWindow>(it) } }
        .getOrNull()
        ?.takeIf { listOf(it.x, it.y, it.width, it.height).all(Float::isFinite) && it.width > 0 && it.height > 0 }

    fun state(): WindowState = restoredWindow(kept, screens())

    /** Writes [state] when it changes what is kept. */
    fun keep(state: WindowState) {
        val now = keptWindow(state, kept)
        if (now == null || now == kept) return
        kept = now
        runCatching { store.write(json.encodeToString(SavedWindow.serializer(), now)) }
            .onFailure { Log.w("WindowMemory", "could not keep the window's place: ${it.message}") }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

/**
 * Puts the window's size back, once, if the window manager shrinks it as
 * it opens. An X11 manager says how wide its borders are only once the
 * window is up, and AWT then takes its guess's error off the size (10 by
 * 2 px under KWin): a kept window shrank a little at every start.
 */
internal suspend fun holdOpeningSize(state: WindowState, within: Duration = 2.seconds) {
    if (state.placement != WindowPlacement.Floating) return
    val opened = state.size
    withTimeoutOrNull(within) { snapshotFlow { state.size }.first { it != opened } } ?: return
    if (state.placement == WindowPlacement.Floating) state.size = opened
}

/**
 * What to keep of [state], given what was [kept]: the floating place and
 * size, and whether it is maximized. Maximized keeps the floating place
 * it will return to. Full screen (a call's meeting mode) and minimized
 * keep what was.
 */
internal fun keptWindow(state: WindowState, kept: SavedWindow?): SavedWindow? {
    val position = state.position
    val floating = if (position is WindowPosition.Absolute && state.size.isSpecified) {
        SavedWindow(position.x.value, position.y.value, state.size.width.value, state.size.height.value)
    } else {
        null
    }
    return when {
        state.isMinimized || state.placement == WindowPlacement.Fullscreen -> kept
        state.placement == WindowPlacement.Maximized -> (kept ?: floating)?.copy(maximized = true)
        else -> floating ?: kept
    }
}

/**
 * The window [saved] describes, on [screens] as they are now (the main
 * one first). A place no screen shows any more, as when a monitor has
 * gone, is dropped for the middle of the main screen, and a size larger
 * than its screen is cut to it. Nothing saved is Compose's default.
 */
internal fun restoredWindow(saved: SavedWindow?, screens: List<Rectangle>): WindowState {
    if (saved == null) return WindowState()
    // The title bar's middle: where the window is picked up to move it.
    val grip = java.awt.Point((saved.x + saved.width / 2).toInt(), (saved.y + TITLE_BAR).toInt())
    val screen = screens.firstOrNull { it.contains(grip) }
    val fit = screen ?: screens.firstOrNull()
    val size = DpSize(
        (fit?.let { minOf(saved.width, it.width.toFloat()) } ?: saved.width).dp,
        (fit?.let { minOf(saved.height, it.height.toFloat()) } ?: saved.height).dp,
    )
    val position = if (screen != null || screens.isEmpty()) {
        WindowPosition(saved.x.dp, saved.y.dp)
    } else {
        WindowPosition(Alignment.Center)
    }
    return WindowState(
        placement = if (saved.maximized) WindowPlacement.Maximized else WindowPlacement.Floating,
        position = position,
        size = size,
    )
}

private const val TITLE_BAR = 15f

/** Every screen's bounds, the main one first; empty where there is no display. */
private fun screenBounds(): List<Rectangle> = runCatching {
    val env = GraphicsEnvironment.getLocalGraphicsEnvironment()
    val main = env.defaultScreenDevice
    (listOf(main) + env.screenDevices.filter { it != main }).map { it.defaultConfiguration.bounds }
}.getOrDefault(emptyList())
