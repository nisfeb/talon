package io.nisfeb.talon.data

import io.nisfeb.talon.util.OneSlot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn

/**
 * Where the shared flows run; each stops five seconds after its last
 * collector leaves. With a handler: a query still in those five seconds
 * when its database closes (a ship switch closes the old one after two)
 * failed "Connection pool is closed" with no one to catch it, which on
 * Android ends the process, and in tests failed the next test to start.
 */
internal val sharing = CoroutineScope(
    SupervisorJob() + Dispatchers.Default +
        kotlinx.coroutines.CoroutineExceptionHandler { _, e -> io.nisfeb.talon.util.Log.w("SharedFlows", "a shared flow stopped: ${e.message}") },
)

private val latest = OneSlot<AppDatabase, Flow<List<MessageEntity>>> { db ->
    db.messages().conversationLatest()
        .stateIn(sharing, SharingStarted.WhileSubscribed(5_000), null)
        // Never the placeholder: the notifier seeds on its first list.
        .filterNotNull()
}

/**
 * The newest top-level message of every conversation, one query for every
 * collector: the home list, the chat list, the party lines, the share
 * sheet, the shortcuts and the notifier each ran it on every write.
 */
fun AppDatabase.latestPerConversation(): Flow<List<MessageEntity>> = latest.of(this)
