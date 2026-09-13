package io.nisfeb.talon.data

import android.content.Context
import io.nisfeb.talon.util.Log

private const val TAG = "ShipErase"

/**
 * Android's copy: the Room database and the per-ship preference files.
 *
 * `deleteDatabase` takes the journal and shared-memory siblings with
 * it, which a plain file delete would leave behind to be re-opened as
 * a half-empty database.
 */
class AndroidShipDataEraser(context: Context) : ShipDataEraser {
    private val app = context.applicationContext

    override fun erase(ship: String): Result<Unit> = runCatching {
        val goneDb = app.deleteDatabase(shipDbName(ship))

        // Per-ship preference files, named the way their own stores
        // name them.
        val key = io.nisfeb.talon.ui.prefsKey(ship)
        for (file in listOf("talon.menuseen.$key", "talon.drafts.$key")) {
            app.getSharedPreferences(file, Context.MODE_PRIVATE).edit().clear().commit()
            deletePrefsFile(file)
        }
        // Rows keyed by ship inside files shared across ships. Asked of
        // the stores that write them rather than guessed at: drafts are
        // private message bodies, and the last-open chat and profile
        // nickname would otherwise greet the ship on its way back in.
        io.nisfeb.talon.notify.AndroidLastOpenChatStore(app).clear(ship)
        io.nisfeb.talon.ui.ShipProfileStore(app).setNickname(ship, null)
        // The relay device id is dropped by forgetShip, which also
        // tells the relay; this only ever runs after that.

        Log.i(TAG, "erased $ship (db=$goneDb)")
    }

    /**
     * Clearing a SharedPreferences leaves an empty XML file behind.
     * Android 24+ has deleteSharedPreferences; below that the file is
     * removed directly, which is safe once it has been cleared.
     */
    private fun deletePrefsFile(name: String) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            app.deleteSharedPreferences(name)
        } else {
            java.io.File(app.applicationInfo.dataDir, "shared_prefs/$name.xml").delete()
        }
    }
}

/** The one name both the builder and the eraser use for a ship's database. */
internal fun shipDbName(ship: String) = "talon-$ship.db"
