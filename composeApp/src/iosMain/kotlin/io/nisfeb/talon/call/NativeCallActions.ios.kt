package io.nisfeb.talon.call

import io.nisfeb.talon.notify.IosCallActions
import io.nisfeb.talon.notify.IosCallKit
import io.nisfeb.talon.notify.IosVoipBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * CallKit both ways: the system's answer/end/mute/hold into the shared
 * call stack, and every call and party line reported out so iOS
 * treats them as phone calls. The iOS half of Android's TelecomCalls.
 *
 * Incoming 1:1 calls are reported by CallPush.swift when the VoIP push
 * arrives — Kotlin only tells CallKit when they are answered in our UI
 * or end. Outgoing calls and the party line are reported from here.
 * Ids are %trunk call ids, and [IosCallKit.PARTY] for the line, which
 * is how Swift maps a CallKit UUID back to what we mean.
 *
 * Still not handled — the cold path: a killed app woken purely by the
 * VoIP push has no controller state for the call. See docs/ios-voip.md.
 */
actual fun bindNativeCallActions(
    controller: CallController,
    partyLine: PartyLine?,
    nameFor: (String) -> String,
) = IosCallKitCalls.bind(controller, partyLine, nameFor)

internal object IosCallKitCalls {
    private var scope: CoroutineScope? = null

    fun bind(controller: CallController, party: PartyLine?, nameFor: (String) -> String) {
        scope?.cancel()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = s
        // Hold has no wire in %trunk: being held mutes us and being
        // resumed unmutes — only if the hold is what muted.
        var heldMute = false
        IosVoipBridge.actions = object : IosCallActions {
            override fun onAnswer(callId: String) = controller.accept(forCallId = callId)

            override fun onEnd(callId: String) {
                when {
                    callId == IosCallKit.PARTY -> party?.leave()
                    controller.state.value is CallUiState.Incoming -> controller.reject()
                    else -> controller.hangup()
                }
            }

            override fun onSetMuted(callId: String, muted: Boolean) {
                if (callId == IosCallKit.PARTY) party?.setMuted(muted) else controller.setMuted(muted)
            }

            override fun onSetHeld(callId: String, held: Boolean) {
                val muted = if (callId == IosCallKit.PARTY) {
                    (party?.state?.value as? PartyState.Live)?.muted
                } else {
                    (controller.state.value as? CallUiState.Active)?.muted
                }
                if (held) {
                    if (muted == false) { onSetMuted(callId, true); heldMute = true }
                } else if (heldMute) {
                    onSetMuted(callId, false); heldMute = false
                }
            }
        }
        s.launch { trackCalls(controller, nameFor) }
        party?.let { s.launch { trackParty(it) } }
    }

    private suspend fun trackCalls(controller: CallController, nameFor: (String) -> String) {
        var reported: String? = null
        var outgoing = false
        var connected = false
        var lastMuted: Boolean? = null
        controller.state.collect { s ->
            val kit = IosVoipBridge.callKit ?: return@collect
            when (s) {
                is CallUiState.Outgoing -> {
                    val id = controller.currentCallId ?: s.peer
                    if (reported != id) {
                        kit.reportOutgoing(id, nameFor(s.peer))
                        reported = id; outgoing = true; connected = false; lastMuted = null
                    }
                }
                is CallUiState.Incoming -> {
                    // CallPush.swift reported this one from the push.
                    reported = controller.currentCallId; outgoing = false; connected = false; lastMuted = null
                }
                is CallUiState.Active -> {
                    val id = reported ?: controller.currentCallId ?: s.peer
                    if (!connected) {
                        if (outgoing) kit.reportConnected(id) else kit.reportAnswered(id)
                        connected = true
                    }
                    if (lastMuted != s.muted) { kit.reportMuted(id, s.muted); lastMuted = s.muted }
                }
                is CallUiState.Ended -> {
                    reported?.let { kit.reportEnded(it, remote = true) }
                    reported = null; connected = false; lastMuted = null
                }
                CallUiState.None -> {
                    reported?.let { kit.reportEnded(it, remote = false) }
                    reported = null; connected = false; lastMuted = null
                }
            }
        }
    }

    private suspend fun trackParty(party: PartyLine) {
        var reported = false
        var connected = false
        var lastMuted: Boolean? = null
        party.state.collect { s ->
            val kit = IosVoipBridge.callKit ?: return@collect
            when (s) {
                is PartyState.Connecting -> if (!reported) {
                    // The room is a Galène id, not a name for a car's
                    // screen; the topic is, once one is known.
                    kit.reportOutgoing(IosCallKit.PARTY, "Party line")
                    reported = true; connected = false; lastMuted = null
                }
                is PartyState.Live -> {
                    if (!reported) {
                        kit.reportOutgoing(IosCallKit.PARTY, s.topic.ifBlank { "Party line" })
                        reported = true
                    }
                    if (!connected) { kit.reportConnected(IosCallKit.PARTY); connected = true }
                    if (lastMuted != s.muted) { kit.reportMuted(IosCallKit.PARTY, s.muted); lastMuted = s.muted }
                }
                is PartyState.Idle, is PartyState.Failed -> {
                    if (reported) kit.reportEnded(IosCallKit.PARTY, remote = s is PartyState.Failed)
                    reported = false; connected = false; lastMuted = null
                }
            }
        }
    }
}
