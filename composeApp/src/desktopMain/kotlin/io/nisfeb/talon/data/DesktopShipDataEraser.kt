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

    private val pendingFile: File
        get() = File(io.nisfeb.talon.util.AppDirs.userData, "pending-erase")

    override fun erase(ship: String): Result<Unit> = runCatching {
        val db = shipDbFile(ship)
        // -wal and -shm hold committed pages. Left behind, they are
        // re-read into a database that was supposed to be gone.
        val files = listOf(db, File("${db.path}-wal"), File("${db.path}-shm"))
        val gone = files.count { it.exists() && it.delete() }
        // Any of the three that is still there is a failure, not
        // "erased (N files)": a surviving -wal resurrects rows the
        // delete was meant to take, and Windows says no while any of
        // them is open. Check each by name so the error says which.
        val survivors = files.filter { it.exists() }
        if (survivors.isNotEmpty()) {
            error("could not delete ${survivors.joinToString { it.name }}; is the database still open?")
        }
        // Asked of the store that writes it, not guessed at.
        io.nisfeb.talon.ui.DesktopMenuSeenStore.defaultFile(ship).delete()
        io.nisfeb.talon.mail.MailThreadFiles.erase(ship)
        if (pendingFile.run { exists() && readText().trim() == ship }) pendingFile.delete()
        Log.i(TAG, "erased $ship ($gone files)")
    }

    override fun markPending(ship: String) {
        runCatching { pendingFile.writeText(ship) }
    }

    override fun takePending(): String? {
        val ship = pendingFile.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotBlank() }
        // Taken means taken: the caller owns what happens next, and a
        // ship re-added since the marker was written must not be
        // erased by a stale replay. erase() re-marks on failure.
        if (ship != null) pendingFile.delete()
        return ship
    }
}
