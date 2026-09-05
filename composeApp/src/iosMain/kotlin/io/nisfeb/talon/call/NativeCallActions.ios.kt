package io.nisfeb.talon.call

import io.nisfeb.talon.notify.IosCallActions
import io.nisfeb.talon.notify.IosVoipBridge

/**
 * Route CallKit's answer / decline (from CallPush.swift, via
 * [IosVoipBridge]) into the shared [CallController].
 *
 * This covers the warm path: the process is alive and the controller
 * already holds the incoming call (its /calls subscription delivered
 * the ring), so accept/reject act on it directly. `accept` and
 * `reject` early-return when there is no ringing call, so a stale tap
 * is harmless.
 *
 * NOT yet handled — the cold path: a killed app woken purely by the
 * VoIP push has no controller state for the call (the ring came over
 * APNs, not the suspended /calls channel), and the caller's one-shot
 * %offer may already have flown by. Joining from cold needs the
 * caller to re-offer when the callee answers, which is a %trunk
 * protocol change and can only be validated on a device. Until then,
 * a cold answer rings and connects the CallKit UI but does not join
 * media. See docs/ios-voip.md.
 */
actual fun bindNativeCallActions(controller: CallController) {
    IosVoipBridge.actions = object : IosCallActions {
        override fun onAnswer(callId: String) = controller.accept(forCallId = callId)
        override fun onDecline(callId: String) = controller.reject()
    }
}
