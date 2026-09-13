package io.nisfeb.talon.data

import io.nisfeb.talon.util.Log
import java.io.File

private const val TAG = "ShipErase"

/**
 * Desktop's copy: the database and its write-ahead siblings, plus the
 * per-ship JSON beside them.
 *
 * Named through [shipDbFile], which is the function that named them
 * in the first place.
 */
class DesktopShipDataEraser : ShipDataEraser {

    override fun erase(ship: String): Result<Unit> = runCatching {
        val db = shipDbFile(ship)
        // -wal and -shm hold committed pages. Left behind, they are
        // re-read into a database that was supposed to be gone.
        val files = listOf(db, File("${db.path}-wal"), File("${db.path}-shm"))
        val gone = files.count { it.exists() && it.delete() }
        // A database that is there and will not go is a failure, not
        // "erased (0 files)". Windows says no while the file is open.
        if (db.exists()) error("could not delete ${db.name}; is it still open?")
        // Asked of the store that writes it, not guessed at.
        io.nisfeb.talon.ui.DesktopMenuSeenStore.defaultFile(ship).delete()
        Log.i(TAG, "erased $ship ($gone files)")
    }
}
