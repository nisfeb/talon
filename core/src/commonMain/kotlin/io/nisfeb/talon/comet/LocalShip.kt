package io.nisfeb.talon.comet

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A ship Talon runs itself on this machine: a comet booted from a
 * downloaded runtime, for someone who has no ship of their own.
 *
 * Same-shape interface in common, one real implementation on desktop
 * (`DesktopLocalShip`, which drives vere under a pseudo-terminal), and
 * [Noop] everywhere else so the login screen can simply not offer it.
 * The UI gates itself on `isLocalCometSupported`; this interface is the
 * seam the gate protects.
 */
interface LocalShip {
    val state: StateFlow<LocalShipState>

    /** True when a pier exists on disk from an earlier setup. */
    fun pierExists(): Boolean

    /**
     * First-time setup: fetch the runtime if missing, boot a new comet,
     * wait for its dojo, read the login code. Long: minutes on the
     * first run. Progress lands in [state]; the result is the
     * [LocalShipState.Ready] the app logs in with.
     */
    suspend fun setup(): LocalShipState.Ready

    /** Run the existing pier and read its code; seconds. */
    suspend fun start(): LocalShipState.Ready

    /** Ask the ship to exit and wait for it. Safe when not running. */
    suspend fun stop()

    object Noop : LocalShip {
        override val state: StateFlow<LocalShipState> = MutableStateFlow(LocalShipState.Idle)
        override fun pierExists(): Boolean = false
        override suspend fun setup(): LocalShipState.Ready = error("no local ship on this platform")
        override suspend fun start(): LocalShipState.Ready = error("no local ship on this platform")
        override suspend fun stop() = Unit
    }

    companion object {
        /** Where a fresh comet lands: the public Nisfeb Software group. */
        const val LANDING_GROUP = "~darduc-mitfen/v12s6q7e"
    }
}

sealed interface LocalShipState {
    data object Idle : LocalShipState

    /** Fetching the runtime. [total] is null when the server sent no length. */
    data class Downloading(val bytes: Long, val total: Long?) : LocalShipState

    /** Vere is running but the dojo has not answered yet. [detail] is
     *  the last informative boot line, for the progress screen. */
    data class Booting(val firstBoot: Boolean, val detail: String) : LocalShipState

    /** Up: logged-in-able at [url] with [code]. */
    data class Ready(val ship: String, val url: String, val code: String) : LocalShipState

    data object Stopped : LocalShipState

    data class Failed(val why: String) : LocalShipState
}
