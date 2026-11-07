package io.nisfeb.talon.urbit

import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.closeAfterWork
import kotlinx.coroutines.runBlocking

/**
 * Stops [repos], waits for their work, and only then closes [db], as the
 * app's own teardown does ([closeAfterWork]). A repo left running kept a
 * drain retrying on its backoff, and a query of it on the closed database
 * was a native crash that took the whole test JVM down (CI, 2026-11-07).
 */
internal fun closeAfter(db: AppDatabase, vararg repos: TlonChatRepo) {
    runBlocking { closeAfterWork(db) { repos.forEach { it.stopAndJoinForTest() } } }
}
