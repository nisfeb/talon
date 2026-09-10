package io.nisfeb.talon.ui

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Save a posted image to the user's local device. Backends differ by
 * platform: Android writes through MediaStore so the result shows up
 * in Photos; desktop drops files into ~/Downloads/Talon/. The same
 * interface keeps the viewer-screen download button platform-neutral.
 *
 * Implementations should be best-effort — no exceptions propagate to
 * the caller; everything is reported through [SaveResult].
 */
interface ImageDownloader {
    suspend fun saveImage(url: String): SaveResult

    /**
     * Save bytes we already hold, under a name the CALLER has already
     * made safe. Mail attachments come this way: they need the ship
     * session to fetch, so the download happens where the session is
     * and only the result lands here.
     *
     * Defaulted to unsupported so a leaf without a backend keeps
     * compiling and the UI hides the control, per the Noop convention.
     */
    suspend fun saveBytes(fileName: String, bytes: ByteArray): SaveResult =
        SaveResult.Unsupported
}

/** Outcome of a save attempt — drives the snackbar text in the viewer. */
sealed interface SaveResult {
    /** Where the file landed, in the user's words ("Pictures/Talon"). */
    data class Saved(val location: String) : SaveResult

    /** Best-effort. The body should explain the failure plainly. */
    data class Failed(val message: String) : SaveResult

    /** Backend isn't wired on this platform — UI hides the button. */
    data object Unsupported : SaveResult
}

/**
 * Default no-op downloader. Returns [SaveResult.Unsupported] so the
 * UI knows to hide the download button. Wired by leaves that haven't
 * implemented a backend yet (or by tests).
 */
object NoopImageDownloader : ImageDownloader {
    override suspend fun saveImage(url: String): SaveResult = SaveResult.Unsupported
}

/**
 * Composition-local handle so deep UI (the fullscreen viewer) can
 * trigger a save without threading the dependency through every
 * caller. Hosts bind it once in their App composable.
 */
val LocalImageDownloader = staticCompositionLocalOf<ImageDownloader> { NoopImageDownloader }
