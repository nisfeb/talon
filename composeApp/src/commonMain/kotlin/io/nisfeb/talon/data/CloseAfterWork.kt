package io.nisfeb.talon.data

import io.nisfeb.talon.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Close a ship's [db] once [stopWork] has stopped, and waited for,
 * everything still using it. True when it closed.
 *
 * Room's close closes connections that are in use, and with the bundled
 * SQLite driver a query running at that moment is a native crash that
 * takes the process down, not an exception. Cancelling a coroutine does
 * not stop the query it is in, and the two-second delay the teardowns
 * used was no guarantee. A database whose work does not stop within
 * [waitMs] is left open: a leaked handle beats a crash.
 */
suspend fun closeAfterWork(db: AppDatabase, waitMs: Long = 10_000, stopWork: suspend () -> Unit): Boolean {
    val stopped = try {
        withTimeoutOrNull(waitMs) { stopWork(); true } == true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("Teardown", "stopping a ship's work failed", e)
        false
    }
    if (!stopped) {
        Log.w("Teardown", "a ship's work did not stop within ${waitMs}ms; its database is left open rather than closed under it")
        return false
    }
    runCatching { db.close() }.onFailure { Log.w("Teardown", "closing a ship's database failed", it) }
    return true
}
