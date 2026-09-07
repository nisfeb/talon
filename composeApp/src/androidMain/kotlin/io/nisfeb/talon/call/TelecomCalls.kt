package io.nisfeb.talon.call

import android.content.Context
import android.telecom.DisconnectCause
import io.nisfeb.talon.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
 * us, the call survives the screen locking — and, through our own
 * account in [TalonTelecom], it shows in the Phone app's call log,
 * where a missed one reads as Missed and a tap calls back over Talon.
 * None of that needs us to give up our own UI.
 *
 * Both a 1:1 call and a party line register, each for as long as it
 * lasts. Telecom's requests come back through [TalonTelecom.Controls];
 * our own state changes are pushed to the connection. If telecom
 * refuses a call (it can, while a cellular call is up), the call
 * carries on app-managed exactly as before this class existed.
 */
class TelecomCalls(
    context: Context,
    private val controller: CallController,
    private val party: PartyLine,
    private val nameFor: (String) -> String,
    private val scope: CoroutineScope,
) {
    private val app = context.applicationContext
    private var jobs: List<Job> = emptyList()

    /** Audio routing for whichever call is registered, for the device
     *  picker. Empty while no call is. */
    val route = TelecomRoute()

    // Hold has no wire in %trunk, so being put on hold mutes us and
    // being taken off it unmutes — only if the hold is what muted.
    private var callHeldMute = false
    private var partyHeldMute = false

    private val callControls = object : TalonTelecom.Controls {
        override fun onAnswer() = controller.accept()
        override fun onReject() = controller.reject()
        override fun onDisconnect() {
            if (controller.state.value is CallUiState.Incoming) controller.reject() else controller.hangup()
        }
        override fun onHold() {
            val s = controller.state.value
            if (s is CallUiState.Active && !s.muted) { controller.setMuted(true); callHeldMute = true }
        }
        override fun onUnhold() {
            if (callHeldMute) { controller.setMuted(false); callHeldMute = false }
        }
    }

    private val partyControls = object : TalonTelecom.Controls {
        override fun onAnswer() = Unit
        override fun onReject() = party.leave()
        override fun onDisconnect() = party.leave()
        override fun onHold() {
            val s = party.state.value
            if (s is PartyState.Live && !s.muted) { party.setMuted(true); partyHeldMute = true }
        }
        override fun onUnhold() {
            if (partyHeldMute) { party.setMuted(false); partyHeldMute = false }
        }
    }

    fun start() {
        if (!TalonTelecom.register(app)) return
        TalonTelecom.hooks = object : TalonTelecom.Hooks {
            override val route: TelecomRoute get() = this@TelecomCalls.route
            override fun controls(id: String): TalonTelecom.Controls =
                if (id == PARTY) partyControls else callControls
            override fun callBack(ship: String) = controller.placeCall(ship)
        }
        jobs = listOf(scope.launch { trackCalls() }, scope.launch { trackParty() })
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        TalonTelecom.hooks = null
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
                val id = controller.currentCallId ?: peer
                current = scope.launch { registerCall(id, peer, incoming = s is CallUiState.Incoming) }
            }
        }
    }

    private suspend fun registerCall(id: String, peer: String, incoming: Boolean) {
        val started = if (incoming) {
            TalonTelecom.startIncoming(app, id, peer, nameFor(peer))
        } else {
            TalonTelecom.startOutgoing(app, id, peer, nameFor(peer))
        }
        if (!started) {
            Log.w(TAG, "telecom would not take the call with $peer; app-managed")
            return
        }
        val conn = TalonTelecom.await(id) ?: return
        var answered = false
        controller.state
            .takeWhile { it !is CallUiState.None && it !is CallUiState.Ended }
            .collect { s ->
                if (s is CallUiState.Active && !answered) { answered = true; conn.setActive() }
            }
        // Which the call log files it as: missed if a ring nobody
        // answered, rejected if we declined it, otherwise who hung up.
        val end = controller.state.value
        val cause = when {
            incoming && !answered && end is CallUiState.Ended && end.reason != "declined" -> DisconnectCause.MISSED
            incoming && !answered -> DisconnectCause.REJECTED
            end is CallUiState.Ended -> DisconnectCause.REMOTE
            else -> DisconnectCause.LOCAL
        }
        conn.setDisconnected(DisconnectCause(cause))
        conn.destroy()
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
        if (!TalonTelecom.startOutgoing(app, PARTY, PARTY, title)) {
            Log.w(TAG, "telecom would not take the party line; app-managed")
            return
        }
        val conn = TalonTelecom.await(PARTY) ?: return
        var live = false
        party.state
            .takeWhile { it !is PartyState.Idle && it !is PartyState.Failed }
            .collect { s ->
                if (s is PartyState.Live && !live) { live = true; conn.setActive() }
            }
        conn.setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
        conn.destroy()
    }

    private companion object {
        const val PARTY = "party"
        const val TAG = "TelecomCalls"
    }
}
