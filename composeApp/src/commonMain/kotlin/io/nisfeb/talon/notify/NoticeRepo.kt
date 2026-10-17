package io.nisfeb.talon.notify

import io.nisfeb.talon.data.NoticeDao
import io.nisfeb.talon.data.NoticeEntity
import io.nisfeb.talon.util.nowMs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * The non-chat notifications shown in the Activity feed's non-chat half.
 * Everything that fires a local notification and is not a chat whom (an
 * Orrery proposal, a loop result, Armillary credit, an invite, a DM request)
 * records a notice here, next to the `notifier.notify(...)` that raises the
 * OS alert. Chat events are not recorded: they live in the ship's `%activity`
 * feed, which Activity reads for its Chat chip.
 *
 * Device-local. Read-state does not sync. The table is kept small: every
 * record prunes to at most [MAX_COUNT] notices and drops anything older than
 * [MAX_AGE_DAYS] days, whichever bound is tighter.
 */
class NoticeRepo(
    private val dao: NoticeDao,
    private val scope: CoroutineScope,
    private val now: () -> Long = { nowMs() },
) {
    val notices: Flow<List<NoticeEntity>> = dao.stream()
    val unreadCount: Flow<Int> = dao.unreadCount()
    val domains: Flow<List<String>> = dao.domains()

    /**
     * Record (or replace, by [id]) a notice, then prune. Fire-and-forget on
     * the repo's own scope so a notify call site stays a one-liner and
     * leaving a screen cannot cancel the write.
     */
    fun record(
        id: String,
        domain: String,
        kind: String,
        title: String,
        body: String,
        target: String,
    ) {
        scope.launch {
            // A notice is not worth the app: Android's scope has no handler,
            // and a write that fails there (the ship's database closing on a
            // switch) would take the process down with it.
            runCatching {
                dao.put(
                    NoticeEntity(
                        id = id,
                        domain = domain,
                        kind = kind,
                        title = title,
                        body = body,
                        createdMs = now(),
                        target = target,
                    ),
                )
                prune()
            }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
        }
    }

    suspend fun markRead(id: String) = dao.markRead(id)

    suspend fun markAllRead(domain: String? = null) = dao.markAllRead(domain)

    /**
     * An event handled elsewhere (an Orrery proposal answered on another
     * device): mark its notice read so the inbox agrees. Fire-and-forget, to
     * match [record], since callers are notification callbacks, not suspend.
     */
    fun dismiss(id: String) {
        scope.launch { runCatching { dao.markRead(id) }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it } }
    }

    private suspend fun prune() {
        dao.pruneOlderThan(now() - MAX_AGE_MS)
        dao.pruneBeyond(MAX_COUNT)
    }

    companion object {
        const val MAX_COUNT = 200
        const val MAX_AGE_DAYS = 30
        const val MAX_AGE_MS = MAX_AGE_DAYS.toLong() * 24 * 60 * 60 * 1000

        // Domains (the Activity chips).
        const val DOMAIN_ASSISTANT = "assistant"
        const val DOMAIN_ACCOUNT = "account"
        const val DOMAIN_CHAT = "chat"
    }
}
