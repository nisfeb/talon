package io.nisfeb.talon.util

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * "The default network just changed" (wifi to cellular, a VPN coming
 * up). Each platform's watcher calls [bump]; a live party line rejoins
 * and a 1:1 call restarts ICE on it, instead of waiting the fifteen to
 * thirty seconds it takes ICE to notice on its own.
 */
object NetworkChanges {
    private val _events = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<Unit> = _events

    fun bump() {
        _events.tryEmit(Unit)
    }
}
