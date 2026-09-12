package io.nisfeb.talon.data

import io.nisfeb.talon.util.Log
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

private const val TAG = "ShipErase"

/**
 * iOS's copy: the database and its write-ahead siblings in Documents,
 * named through the same sanitiser that created them.
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
class IosShipDataEraser : ShipDataEraser {

    override fun erase(ship: String): Result<Unit> = runCatching {
        val dir = NSSearchPathForDirectoriesInDomains(
            NSDocumentDirectory, NSUserDomainMask, true,
        ).first() as String
        val base = "$dir/talon-${sanitizeShipKey(ship)}.db"
        val fm = NSFileManager.defaultManager
        var gone = 0
        // -wal and -shm hold committed pages; left behind they are read
        // back into a database that was supposed to be gone.
        for (path in listOf(base, "$base-wal", "$base-shm")) {
            if (fm.fileExistsAtPath(path) && fm.removeItemAtPath(path, null)) gone++
        }
        Log.i(TAG, "erased $ship ($gone files)")
    }
}
