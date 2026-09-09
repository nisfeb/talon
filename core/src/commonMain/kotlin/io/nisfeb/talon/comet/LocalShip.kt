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

    /** The ship's terminal as a person would see it, control sequences
     *  stripped, most recent output last. Feeds the dojo panel in
     *  Settings. */
    val terminal: StateFlow<String>

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

    /** Type one line into the dojo, as if at the terminal. Ignored
     *  when the ship is not running. */
    fun send(line: String)

    /** What is on disk, for Settings. Null when there is no pier. */
    fun describe(): LocalShipInfo?

    /** Bytes the pier occupies; walks the directory, so call it off
     *  the main thread. */
    suspend fun pierBytes(): Long

    /** The login code kept from first boot, for signing in from another
     *  client on this machine. Null until a first boot completed. */
    fun keptCode(): String?

    /** The newest runtime release, when it is newer than the installed
     *  one; null when current. Network. */
    suspend fun checkRuntimeUpdate(): RuntimeUpdate?

    /** Stop the ship, fetch [version], start it again. The pier
     *  migrates itself on the new runtime's first start. */
    suspend fun upgradeRuntime(version: String)

    object Noop : LocalShip {
        override val state: StateFlow<LocalShipState> = MutableStateFlow(LocalShipState.Idle)
        override val terminal: StateFlow<String> = MutableStateFlow("")
        override fun pierExists(): Boolean = false
        override suspend fun setup(): LocalShipState.Ready = error("no local ship on this platform")
        override suspend fun start(): LocalShipState.Ready = error("no local ship on this platform")
        override suspend fun stop() = Unit
        override fun send(line: String) = Unit
        override fun describe(): LocalShipInfo? = null
        override suspend fun pierBytes(): Long = 0L
        override fun keptCode(): String? = null
        override suspend fun checkRuntimeUpdate(): RuntimeUpdate? = null
        override suspend fun upgradeRuntime(version: String) = Unit
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

/** What Settings shows about the ship on disk. */
data class LocalShipInfo(
    /** Known once a first boot completed; null for a pier that never got there. */
    val ship: String?,
    val pierPath: String,
    /** The vere version the pier runs on, e.g. "4.6". */
    val runtimeVersion: String,
)

data class RuntimeUpdate(val installed: String, val latest: String)
