package io.nisfeb.talon.call

import android.content.Context
import android.net.Uri
import android.telecom.DisconnectCause
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallEndpointCompat
import androidx.core.telecom.CallsManager
import io.nisfeb.talon.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch

/**
 * Every Urbit call is a self-managed telecom call.
 *
 * Android-only. iOS has the same thing in CallKit (TalonRtc /
 * CallPush); desktop has no telecom stack to register with.
 *
 * Registering a call with telecom is what makes it behave like a
 * phone call rather than an app making noise: the system routes audio
 * to the car or the headset, a Bluetooth button answers or hangs up,
 * an incoming cellular call puts us on hold instead of talking over
 * us, and the call survives the screen locking. None of that needs
 * us to give up our own UI — a self-managed call keeps it.
 *
 * Both a 1:1 call and a party line register, each for as long as it
 * lasts. Telecom's requests come back as the callbacks below; our
 * own state changes are pushed to it inside the call's scope. If
 * telecom refuses a call (it can, while a cellular call is up), the
 * call carries on app-managed exactly as before this class existed.
 */
class TelecomCalls(
    context: Context,
    private val controller: CallController,
    private val party: PartyLine,
    private val nameFor: (String) -> String,
    private val scope: CoroutineScope,
) {
    private val manager = CallsManager(context.applicationContext)
    private var jobs: List<Job> = emptyList()

    /** Audio routing for whichever call is registered, for the device
     *  picker. Empty while no call is. */
    val route = TelecomRoute()

    fun start() {
        val registered = runCatching {
            manager.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE)
        }.onFailure { Log.w(TAG, "telecom registration failed; calls stay app-managed", it) }
            .isSuccess
        if (!registered) return
        jobs = listOf(scope.launch { trackCalls() }, scope.launch { trackParty() })
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
    }

    private suspend fun trackCalls() {
        var current: Job? = null
        controller.state.collect { s ->
            val live = s is CallUiState.Incoming || s is CallUiState.Outgoing || s is CallUiState.Active
            if (live && current?.isActive != true) {
                val peer = when (s) {
                    is CallUiState.Incoming -> s.peer
                    is CallUiState.Outgoing -> s.peer
                    is CallUiState.Active -> s.peer
                    else -> return@collect
                }
                current = scope.launch { registerCall(peer, incoming = s is CallUiState.Incoming) }
            }
        }
    }

    private suspend fun registerCall(peer: String, incoming: Boolean) {
        val attrs = CallAttributesCompat(
            nameFor(peer),
            Uri.parse("urbit:$peer"),
            if (incoming) CallAttributesCompat.DIRECTION_INCOMING else CallAttributesCompat.DIRECTION_OUTGOING,
            CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
            CallAttributesCompat.SUPPORTS_SET_INACTIVE,
        )
        // Hold has no wire in %trunk, so being put on hold mutes us and
        // being taken off it unmutes — only if the hold is what muted.
        var heldMute = false
        // addCall's block is plain, but the scope it hands us lives as
        // long as the call: the tracking runs inside it, and this
        // function waits for the end so one registration spans the call.
        val ended = CompletableDeferred<Unit>()
        val added = runCatching {
            manager.addCall(
                attrs,
                onAnswer = { controller.accept() },
                onDisconnect = {
                    if (controller.state.value is CallUiState.Incoming) controller.reject() else controller.hangup()
                },
                onSetActive = {
                    if (heldMute) { controller.setMuted(false); heldMute = false }
                },
                onSetInactive = {
                    val s = controller.state.value
                    if (s is CallUiState.Active && !s.muted) { controller.setMuted(true); heldMute = true }
                },
            ) {
                val control = this
                route.bind(control)
                launch {
                    try {
                        var told = false
                        controller.state
                            .takeWhile { it !is CallUiState.None && it !is CallUiState.Ended }
                            .collect { s ->
                                if (s is CallUiState.Active && !told) {
                                    told = true
                                    if (incoming) control.answer(CallAttributesCompat.CALL_TYPE_AUDIO_CALL)
                                    else control.setActive()
                                }
                            }
                        control.disconnect(
                            DisconnectCause(
                                if (controller.state.value is CallUiState.Ended) DisconnectCause.REMOTE
                                else DisconnectCause.LOCAL,
                            ),
                        )
                    } finally {
                        route.unbind(control)
                        ended.complete(Unit)
                    }
                }
            }
        }.onFailure { Log.w(TAG, "telecom would not take the call with $peer; app-managed", it) }
            .isSuccess
        if (added) ended.await()
    }

    private suspend fun trackParty() {
        var current: Job? = null
        party.state.collect { s ->
            val live = s is PartyState.Connecting || s is PartyState.Live
            if (live && current?.isActive != true) {
                current = scope.launch { registerParty() }
            }
        }
    }

    private suspend fun registerParty() {
        // The room is a Galène id, not a name for a car's screen; the
        // topic is, when an admin set one.
        val title = (party.state.value as? PartyState.Live)?.topic?.takeIf { it.isNotBlank() } ?: "Party line"
        val attrs = CallAttributesCompat(
            title,
            Uri.parse("urbit:party"),
            CallAttributesCompat.DIRECTION_OUTGOING,
            CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
            CallAttributesCompat.SUPPORTS_SET_INACTIVE,
        )
        var heldMute = false
        val ended = CompletableDeferred<Unit>()
        val added = runCatching {
            manager.addCall(
                attrs,
                onAnswer = { },
                onDisconnect = { party.leave() },
                onSetActive = {
                    if (heldMute) { party.setMuted(false); heldMute = false }
                },
                onSetInactive = {
                    val s = party.state.value
                    if (s is PartyState.Live && !s.muted) { party.setMuted(true); heldMute = true }
                },
            ) {
                val control = this
                route.bind(control)
                launch {
                    try {
                        var told = false
                        party.state
                            .takeWhile { it !is PartyState.Idle && it !is PartyState.Failed }
                            .collect { s ->
                                if (s is PartyState.Live && !told) { told = true; control.setActive() }
                            }
                        control.disconnect(DisconnectCause(DisconnectCause.LOCAL))
                    } finally {
                        route.unbind(control)
                        ended.complete(Unit)
                    }
                }
            }
        }.onFailure { Log.w(TAG, "telecom would not take the party line; app-managed", it) }
            .isSuccess
        if (added) ended.await()
    }

    private companion object {
        const val TAG = "TelecomCalls"
    }
}

/**
 * The registered call's audio endpoints, as [AndroidAudioDevices]
 * shows them. While a call is registered, telecom owns routing, and
 * setting a communication device behind its back is at best ignored;
 * the picker asks it instead.
 */
class TelecomRoute {
    private val endpoints = MutableStateFlow<List<CallEndpointCompat>>(emptyList())
    private val current = MutableStateFlow<CallEndpointCompat?>(null)
    @Volatile private var control: CallControlScope? = null

    val active: Boolean get() = control != null
    val selected: String? get() = current.value?.identifier?.toString()

    fun devices(): List<AudioDevice> =
        endpoints.value.map { AudioDevice(id = it.identifier.toString(), label = it.name.toString()) }

    fun select(id: String?) {
        val c = control ?: return
        val target = endpoints.value.firstOrNull { it.identifier.toString() == id } ?: return
        c.launch { c.requestEndpointChange(target) }
    }

    internal fun bind(scope: CallControlScope) {
        control = scope
        scope.launch { scope.availableEndpoints.collect { endpoints.value = it } }
        scope.launch { scope.currentCallEndpoint.collect { current.value = it } }
    }

    internal fun unbind(scope: CallControlScope) {
        if (control === scope) {
            control = null
            endpoints.value = emptyList()
            current.value = null
        }
    }
}
