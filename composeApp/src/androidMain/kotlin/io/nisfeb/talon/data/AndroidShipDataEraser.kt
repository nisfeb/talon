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
        // The same name buildShipScoped passes to createAppDatabase.
        val db = "talon-$ship.db"
        val goneDb = app.deleteDatabase(db)

        // Per-ship preference files, named the way their own stores
        // name them.
        val menuSeen = "talon.menuseen." +
            ship.removePrefix("~").replace(Regex("[^a-z0-9-]"), "_")
        app.getSharedPreferences(menuSeen, Context.MODE_PRIVATE).edit().clear().commit()
        deletePrefsFile(menuSeen)

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
