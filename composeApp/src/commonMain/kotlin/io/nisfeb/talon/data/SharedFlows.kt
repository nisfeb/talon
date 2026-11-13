package io.nisfeb.talon.data

import io.nisfeb.talon.util.ConcurrentMap
import io.nisfeb.talon.util.OneSlot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn

/** Each database's shared flows, in a scope of their own, so its teardown can stop them. */
private val scopes = ConcurrentMap<AppDatabase, CoroutineScope>()

/**
 * Where [db]'s shared flows run; each stops five seconds after its last
 * collector leaves. With a handler: a query still in those five seconds
 * when its database closes failed "Connection pool is closed" with no
 * one to catch it, which on Android ends the process, and in tests
 * failed the next test to start.
 */
internal fun sharingFor(db: AppDatabase): CoroutineScope = scopes.getOrPut(db) {
    CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            kotlinx.coroutines.CoroutineExceptionHandler { _, e -> io.nisfeb.talon.util.Log.w("SharedFlows", "a shared flow stopped: ${e.message}") },
    )
}

/**
 * Stops [db]'s shared flows and waits for them, before it closes
 * ([closeAfterWork]). Their five seconds outlived the two a ship switch
 * waited, and a query of theirs running into the close was a native
 * crash, not an exception (CI, 2026-11-07).
 */
internal suspend fun stopSharing(db: AppDatabase) {
    scopes.remove(db)?.coroutineContext?.get(Job)?.cancelAndJoin()
}

private val latest = OneSlot<AppDatabase, Flow<List<MessageEntity>>> { db ->
    // A DM request is not a conversation until it is accepted. The ship
    // sends a stranger's messages anyway, and they showed as an ordinary
    // chat beside the request, in shortcuts and the briefing too
    // (2026-11-08); they wait under Requests instead.
    combine(db.messages().conversationLatest(), db.dmInvites().stream()) { rows, invites ->
        if (invites.isEmpty()) rows
        else invites.mapTo(HashSet()) { it.ship }.let { pending -> rows.filter { it.whom !in pending } }
    }
        .stateIn(sharingFor(db), SharingStarted.WhileSubscribed(5_000), null)
        // Never the placeholder: the notifier seeds on its first list.
        .filterNotNull()
}

/**
 * The newest top-level message of every conversation, one query for every
 * collector: the home list, the chat list, the party lines, the share
 * sheet, the shortcuts and the notifier each ran it on every write.
 */
fun AppDatabase.latestPerConversation(): Flow<List<MessageEntity>> = latest.of(this)
