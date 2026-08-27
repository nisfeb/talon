package io.nisfeb.talon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.nisfeb.talon.call.TrunkSig
import io.nisfeb.talon.call.TrunkWire
import io.nisfeb.talon.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Declining a call from the notification, without opening the app.
 *
 * Android-only: no desktop analog — desktop has no notification
 * actions of its own, and its ring surface is always on screen.
 *
 * A decline is one poke, so it needs no media stack and no running
 * CallController — which is the point. Answering is deliberately not
 * handled here: it needs the media negotiation that only the app can
 * do, so the answer action opens MainActivity instead.
 */
class CallActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DECLINE) return
        val from = intent.getStringExtra(Notifications.EXTRA_ANSWER_FROM) ?: return
        val callId = intent.getStringExtra(Notifications.EXTRA_ANSWER_CALL_ID) ?: return

        // Stop ringing immediately — the poke can take a moment, and a
        // phone that keeps ringing after you hit Decline feels broken.
        Notifications.cancelIncomingCall(context)

        val app = context.applicationContext as? TalonApplication ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val channel = app.session.openChannel()
                channel.poke(
                    TrunkWire.AGENT,
                    TrunkWire.ACTION_MARK,
                    TrunkWire.sendAction(from, TrunkSig.Reject(callId, "declined")),
                )
                Log.i(TAG, "declined call $callId from $from")
            } catch (t: Throwable) {
                // The caller's own ring watchdog gives up regardless,
                // so a failed decline degrades to an unanswered call.
                Log.w(TAG, "decline poke failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_DECLINE = "io.nisfeb.talon.CALL_DECLINE"
        private const val TAG = "CallActionReceiver"
    }
}
