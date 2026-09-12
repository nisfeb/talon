package io.nisfeb.talon.data

import io.nisfeb.talon.util.AppDirs
import io.nisfeb.talon.util.Log
import java.io.File

private const val TAG = "ShipErase"

/**
 * Desktop's copy: the database and its write-ahead siblings, plus the
 * per-ship JSON beside them.
 *
 * Named through [sanitizeShipKey], which is the function that named
 * them in the first place.
 */
class DesktopShipDataEraser : ShipDataEraser {

    override fun erase(ship: String): Result<Unit> = runCatching {
        val dir = AppDirs.userData
        val key = sanitizeShipKey(ship)
        val db = File(dir, "talon-port-$key.db")
        // -wal and -shm hold committed pages. Left behind, they are
        // re-read into a database that was supposed to be gone.
        val gone = listOf(db, File("${db.path}-wal"), File("${db.path}-shm"))
            .count { it.exists() && it.delete() }
        // Asked of the store that writes it, not guessed at.
        io.nisfeb.talon.ui.DesktopMenuSeenStore.defaultFile(ship).delete()
        Log.i(TAG, "erased $ship ($gone files)")
    }
}
