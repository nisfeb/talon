package io.nisfeb.talon.notify

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The seam between the iOS native VoIP/CallKit layer (AppDelegate +
 * CallPush.swift) and shared Kotlin.
 *
 * Swift → Kotlin: the PushKit VoIP token (so the relay can address
 * this device), and the answer / decline the user tapped on the
 * native CallKit screen. Kotlin → app: [actions], which an app-layer
 * wiring sets to drive the shared CallController.
 *
 * Exposed to Swift as `IosVoipBridge.shared`.
 */
object IosVoipBridge {
    /** The PushKit VoIP token as lowercase hex, or null until PushKit
     *  delivers it (shortly after launch). */
    val voipToken = MutableStateFlow<String?>(null)

    /** Called by Swift in `didUpdate pushCredentials`. */
    fun setVoipToken(hex: String) {
        voipToken.value = hex
    }

    /** Set by the app layer to connect the native call screen's
     *  buttons to the call stack. Null before it is wired, and on a
     *  cold VoIP launch before the app graph is up — Swift still
     *  reports the call to CallKit either way, so the phone rings;
     *  this only gates joining once the user answers. */
    var actions: IosCallActions? = null

    /** Called by Swift on CXAnswerCallAction. */
    fun answer(callId: String) {
        actions?.onAnswer(callId)
    }

    /** Called by Swift on CXEndCallAction (decline or hang up). */
    fun decline(callId: String) {
        actions?.onDecline(callId)
    }
}

/** What the app layer implements to join or drop a call the user
 *  acted on from the CallKit UI. */
interface IosCallActions {
    fun onAnswer(callId: String)
    fun onDecline(callId: String)
}

/**
 * iOS push transport: PushKit VoIP. The "token" is the hex PushKit
 * token and [platform] is "ios-voip", which the relay routes to APNs
 * VoIP. [token] waits briefly for PushKit to deliver on a cold start
 * rather than registering a null endpoint.
 */
class IosPushTokenProvider : PushTokenProvider {
    override val platform: String = "ios-voip"

    override suspend fun token(): String? =
        withTimeoutOrNull(TOKEN_WAIT_MS) {
            IosVoipBridge.voipToken.first { it != null }
        }

    private companion object {
        private const val TOKEN_WAIT_MS = 5_000L
    }
}
