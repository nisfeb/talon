package io.nisfeb.talon.data

import io.nisfeb.talon.ui.IosDraftStore
import io.nisfeb.talon.util.IosFiles
import io.nisfeb.talon.util.Log
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

private const val TAG = "ShipErase"
private const val PENDING_FILE = "pending-erase"

/**
 * iOS's copy: the database and its write-ahead siblings, named through
 * the same sanitiser that created them, plus the ship's drafts file.
 * The db lives in Application Support now; builds before the move left
 * it in Documents, so both are searched rather than reporting success
 * over a surviving copy.
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
class IosShipDataEraser : ShipDataEraser {

    override fun erase(ship: String): Result<Unit> = runCatching {
        val name = "talon-${sanitizeShipKey(ship)}.db"
        val fm = NSFileManager.defaultManager
        val dirs = listOf(
            appSupportDir(),
            NSSearchPathForDirectoriesInDomains(
                NSDocumentDirectory, NSUserDomainMask, true,
            ).first() as String,
        ).distinct()
        var gone = 0
        for (dir in dirs) {
            val base = "$dir/$name"
            // -wal and -shm hold committed pages; left behind they are
            // read back into a database that was supposed to be gone.
            for (path in listOf(base, "$base-wal", "$base-shm")) {
                if (fm.fileExistsAtPath(path) && fm.removeItemAtPath(path, null)) gone++
            }
            check(!fm.fileExistsAtPath(base)) { "database survived deletion: $base" }
        }
        IosFiles.delete(IosDraftStore.fileFor(ship))
        io.nisfeb.talon.mail.MailThreadFiles.erase(ship)
        if (IosFiles.read(PENDING_FILE)?.trim() == ship) IosFiles.delete(PENDING_FILE)
        Log.i(TAG, "erased $ship ($gone files)")
    }

    override fun markPending(ship: String) {
        runCatching { IosFiles.write(PENDING_FILE, ship) }
    }

    override fun takePending(): String? {
        val ship = IosFiles.read(PENDING_FILE)?.trim()?.takeIf { it.isNotBlank() }
        // Taken means taken; see the desktop copy for why.
        if (ship != null) IosFiles.delete(PENDING_FILE)
        return ship
    }
}
