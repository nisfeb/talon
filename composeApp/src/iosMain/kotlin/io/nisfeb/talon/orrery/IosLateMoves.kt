package io.nisfeb.talon.orrery

import io.nisfeb.talon.urbit.UrbitSession
import io.nisfeb.talon.urbit.createSessionStore
import io.nisfeb.talon.util.createAppHttpClient

/**
 * Done and Snooze 1 h on orrery's nag about a late action, tapped on the
 * notification (CallPush.swift): as the owner of the ship the nag came
 * from, with the login kept on this phone. The app may not be running,
 * so nothing here waits on its screens. The decision is [lateMove]'s, as
 * on Android (OrreryLateReceiver). The buttons ask for Face ID first, so
 * the kept login can be read on a locked phone.
 *
 * What failed, in words, or null when it went through.
 */
internal object IosLateMoves {
    suspend fun run(tag: String, move: String, ship: String?): String? {
        val id = actionIdOfTag(tag) ?: return OPEN_TALON
        val m = LateMove.entries.firstOrNull { it.name == move } ?: return OPEN_TALON
        val http = createAppHttpClient()
        return try {
            val session = UrbitSession(http, createSessionStore())
            session.tryRestore(ship?.takeIf { it.isNotBlank() }) ?: return "Sign in to Talon to answer this one."
            val url = session.baseUrl ?: return "Sign in to Talon to answer this one."
            OrreryApi(session.http, session.http, url).lateMove(id, m)
        } finally {
            http.close()
        }
    }

    private const val OPEN_TALON = "Open Talon to answer this one."
}
