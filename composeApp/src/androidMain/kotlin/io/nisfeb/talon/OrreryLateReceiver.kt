package io.nisfeb.talon

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.nisfeb.talon.orrery.LateMove
import io.nisfeb.talon.orrery.OrreryApi
import io.nisfeb.talon.orrery.lateMove
import io.nisfeb.talon.util.Log
import kotlinx.coroutines.launch

/**
 * Done and Snooze 1 h on orrery's nag about a late action, without
 * opening the app: as the owner, on the app's scope, as [lateMove] in
 * common decides. The nag is taken back when it went through, and posted
 * again over itself, quietly, with what failed when it did not.
 *
 * Android-only: desktop gets no ship pushes, so it has no nag to answer.
 * iOS port pending: buttons on a pushed notice need a UNNotificationCategory
 * registered in Swift.
 *
 * ponytail: goAsync gives a receiver about ten seconds, and Done on a
 * proposal on a busy ship (approve, read back up to six seconds, done)
 * can take longer; a WorkManager job if that shows.
 */
class OrreryLateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val move = intent.getStringExtra(EXTRA_MOVE)?.let { m -> LateMove.entries.firstOrNull { it.name == m } } ?: return
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val app = context.applicationContext as? TalonApplication ?: return
        val shipUrl = app.sessionStore.active()?.shipUrl ?: return
        val pending = goAsync()
        app.appScope.launch {
            try {
                val failed = OrreryApi(app.session.http, app.session.http, shipUrl).lateMove(id, move)
                if (failed == null) Notifications.clearAction(app, id)
                else Notifications.showAction(app, id, title, failed, late = true, quiet = true)
                Log.i(TAG, "${move.name} on $id: ${failed ?: "went through"}")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION = "io.nisfeb.talon.ORRERY_LATE"
        private const val EXTRA_ID = "orrery_action"
        private const val EXTRA_MOVE = "orrery_move"
        private const val EXTRA_TITLE = "orrery_title"
        private const val TAG = "OrreryLateReceiver"

        /** One button's intent: its own request code, so Done and Snooze on one nag, and on two, stay apart. */
        fun intent(context: Context, id: String, title: String, move: LateMove): PendingIntent = PendingIntent.getBroadcast(
            context, "late:$id:${move.name}".hashCode(),
            Intent(context, OrreryLateReceiver::class.java).apply {
                action = ACTION
                putExtra(EXTRA_ID, id)
                putExtra(EXTRA_MOVE, move.name)
                putExtra(EXTRA_TITLE, title)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
