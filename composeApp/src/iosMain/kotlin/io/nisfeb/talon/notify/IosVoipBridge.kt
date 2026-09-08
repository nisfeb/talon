package io.nisfeb.talon.notify

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The seam between CallPush.swift and the Kotlin call stack.
 *
 * Swift owns CallKit and PushKit and calls in with the token and the
 * user's actions on the system call UI; Kotlin owns the calls and
 * reports their lifecycle out through [callKit], so a call we place or
 * a line we join is a phone call to iOS — car, watch, headset button,
 * hold for a cellular call — the same as one we receive.
 */
object IosVoipBridge {
    val voipToken = MutableStateFlow<String?>(null)

    fun setVoipToken(hex: String) {
        voipToken.value = hex
    }

    /** The APNs device token for user-visible alerts; null until iOS
     *  hands one over, empty forever if notifications were refused. */
    val alertToken = MutableStateFlow<String?>(null)

    /**
     * The user's microphone processing choice as libwebrtc audio-source
     * constraints, read by TalonRtc.swift when it creates the mic.
     */
    fun micConstraints(): Map<String, String> {
        val mine = io.nisfeb.talon.call.MicProcessingSettings.current
        return mapOf(
            "googNoiseSuppression" to mine.noiseSuppression.toString(),
            "googEchoCancellation" to mine.echoCancellation.toString(),
            "googAutoGainControl" to mine.autoGainControl.toString(),
        )
    }

    fun setAlertToken(hex: String) {
        alertToken.value = hex
    }

    /** iOS asks before showing a foreground alert: not for the chat
     *  the user is already reading. */
    fun shouldPresentAlert(whom: String): Boolean = NotificationFocus.openWhom != whom

    /** A tapped alert: open its chat. */
    fun openChat(whom: String, postId: String?) = OpenChatRequests.request(whom, postId)

    /** Set by the Kotlin side (NativeCallActions.ios.kt). */
    var actions: IosCallActions? = null

    /** Set by CallPush.swift at launch. */
    var callKit: IosCallKit? = null

    fun answer(callId: String) {
        actions?.onAnswer(callId)
    }

    fun end(callId: String) {
        actions?.onEnd(callId)
    }

    fun setMuted(callId: String, muted: Boolean) {
        actions?.onSetMuted(callId, muted)
    }

    fun setHeld(callId: String, held: Boolean) {
        actions?.onSetHeld(callId, held)
    }

    /** A tap on a Recents entry: the handle we reported, a ship. */
    fun callBack(handle: String) {
        actions?.onCallBack(handle)
    }
}

/** What the system call UI asks of us. Ids are %trunk call ids, or
 *  [IosCallKit.PARTY] for the party line. */
interface IosCallActions {
    fun onAnswer(callId: String)
    fun onEnd(callId: String)
    fun onSetMuted(callId: String, muted: Boolean)
    fun onSetHeld(callId: String, held: Boolean)
    fun onCallBack(handle: String)
}

/** What we tell the system call UI. Implemented in Swift. */
interface IosCallKit {
    /** A call we placed, or a line we are joining. [handle] is what a
     *  Recents tap hands back — the ship — and [name] what it shows. */
    fun reportOutgoing(id: String, handle: String, name: String)
    /** An incoming call answered in our own UI rather than CallKit's. */
    fun reportAnswered(id: String)
    fun reportConnected(id: String)
    fun reportEnded(id: String, remote: Boolean)
    fun reportMuted(id: String, muted: Boolean)
    /** A line for the call trace from the Kotlin side. */
    fun note(msg: String)

    companion object {
        const val PARTY = "party"
    }
}

class IosPushTokenProvider : PushTokenProvider {
    // One registration carries both APNs tokens as "<voip>|<alert>":
    // the relay rings through PushKit and alerts through the app
    // topic. The alert half may be empty (permission refused); calls
    // still ring then.
    override val platform: String = "ios"

    override suspend fun token(): String? {
        val voip = withTimeoutOrNull(TOKEN_WAIT_MS) {
            IosVoipBridge.voipToken.first { it != null }
        } ?: return null
        val alert = withTimeoutOrNull(TOKEN_WAIT_MS) {
            IosVoipBridge.alertToken.first { it != null }
        }.orEmpty()
        return "$voip|$alert"
    }

    private companion object {
        private const val TOKEN_WAIT_MS = 5_000L
    }
}
