package io.nisfeb.talon.notify

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * When each chat's read push arrived, by ship: a message notification
 * waiting to be posted (Android names its sender from the database
 * first, up to 5 s) is not posted for a chat read since the message
 * came. A read used to cancel what was showing and nothing else, so a
 * DM read on desktop 3 s after it arrived still notified on the phone
 * (sneagan's report, 2026-10-07).
 */
object ReadPushes {
    private val readAt = MutableStateFlow<Map<String, Long>>(emptyMap())

    private fun key(ship: String?, whom: String) = "${ship.orEmpty()}|$whom"

    fun read(ship: String?, whom: String, atMs: Long) = readAt.update { m ->
        // Kept ten minutes: longer than any notification waits to post.
        (m + (key(ship, whom) to atMs)).filterValues { atMs - it < KEEP_MS }
    }

    /** Read since [sinceMs], the moment the message's push arrived. */
    fun readSince(ship: String?, whom: String, sinceMs: Long): Boolean =
        (readAt.value[key(ship, whom)] ?: return false) >= sinceMs

    private const val KEEP_MS = 10 * 60 * 1000L
}
