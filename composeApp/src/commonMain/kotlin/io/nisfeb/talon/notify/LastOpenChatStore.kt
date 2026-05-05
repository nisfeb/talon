package io.nisfeb.talon.notify

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Per-ship persistent record of which chat was open when the user
 * last sent the app to background or quit. Powers the wide-window
 * "open straight to your last conversation" behaviour driven by
 * [io.nisfeb.talon.ui.ChatPaneScaffold].
 *
 * Keyed on the ship's patp, value is the conversation `whom` —
 * same identifier the existing `openChat` state and chat-list rows
 * use. Reads happen on first composition / ship-switch; writes on
 * every `openChat` flip.
 *
 * The store is intentionally thin: no expiry, no cross-ship index.
 * A persisted entry pointing at a chat that no longer exists in
 * the local DB is harmless — the seed lookup returns null and the
 * scaffold renders [io.nisfeb.talon.ui.EmptyChatPane].
 */
interface LastOpenChatStore {
    val state: StateFlow<Map<String, String>>
    fun set(patp: String, whom: String)
    fun clear(patp: String)
}

class InMemoryLastOpenChatStore(
    initial: Map<String, String> = emptyMap(),
) : LastOpenChatStore {
    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<Map<String, String>> = _state.asStateFlow()
    override fun set(patp: String, whom: String) {
        if (_state.value[patp] == whom) return
        _state.value = _state.value + (patp to whom)
    }
    override fun clear(patp: String) {
        if (patp !in _state.value) return
        _state.value = _state.value.minus(patp)
    }
}

object NoopLastOpenChatStore : LastOpenChatStore {
    override val state: StateFlow<Map<String, String>> =
        MutableStateFlow(emptyMap<String, String>()).asStateFlow()
    override fun set(patp: String, whom: String) = Unit
    override fun clear(patp: String) = Unit
}
