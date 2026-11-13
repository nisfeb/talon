package io.nisfeb.talon.urbit

import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.closeAfterWork
import io.nisfeb.talon.data.stopSharing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private val closing = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Stops [repos] and [db]'s shared flows and waits for them, so none of it
 * runs into the next test, then closes [db] two seconds later, as the
 * app's own teardown does (closeAfterWork, after App.kt's two seconds).
 *
 * A repo left running kept a drain retrying on its backoff, and a query
 * of it on the closed database was a native crash that took the whole
 * test JVM down. A screen's flows are cancelled with its composition,
 * but a query of theirs already running on Room's thread is waited for
 * by nothing, and a close at once crashed under it too (CI, 2026-11-07).
 */
internal fun closeAfter(db: AppDatabase, vararg repos: TlonChatRepo) {
    runBlocking {
        repos.forEach { it.stopAndJoinForTest() }
        stopSharing(db)
    }
    closing.launch {
        delay(2_000)
        closeAfterWork(db) {}
    }
}
