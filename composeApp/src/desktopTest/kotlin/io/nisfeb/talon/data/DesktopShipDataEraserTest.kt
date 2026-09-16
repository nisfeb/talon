package io.nisfeb.talon.data

import io.nisfeb.talon.util.AppDirs
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Erasing a ship has to find the files that ship actually wrote.
 *
 * The names come from the same sanitiser the database factory uses; a
 * second copy of that rule, drifted by a character, would delete
 * nothing and report success — which is the one failure nobody would
 * notice until they wondered why the disk never got smaller.
 */
class DesktopShipDataEraserTest {

    private fun ship(name: String) = File(AppDirs.userData, "talon-port-${sanitizeShipKey(name)}.db")

    @Test
    fun `it deletes the database and its write-ahead siblings`() {
        // Left behind, -wal and -shm are read back into a database
        // that was supposed to be gone.
        val db = ship("~test-eraser-one")
        val wal = File("${db.path}-wal")
        val shm = File("${db.path}-shm")
        try {
            for (f in listOf(db, wal, shm)) f.writeText("x")
            DesktopShipDataEraser().erase("~test-eraser-one").getOrThrow()
            for (f in listOf(db, wal, shm)) assertFalse(f.exists(), "${f.name} survived")
        } finally {
            for (f in listOf(db, wal, shm)) f.delete()
        }
    }

    @Test
    fun `it leaves other ships alone`() {
        val mine = ship("~test-eraser-two")
        val theirs = ship("~test-eraser-three")
        try {
            mine.writeText("x")
            theirs.writeText("x")
            DesktopShipDataEraser().erase("~test-eraser-two").getOrThrow()
            assertFalse(mine.exists())
            assertTrue(theirs.exists(), "it took a ship it was not asked about")
        } finally {
            mine.delete(); theirs.delete()
        }
    }

    @Test
    fun `erasing a ship that stored nothing succeeds quietly`() {
        // Somebody can sign out of a ship that never finished syncing.
        assertTrue(DesktopShipDataEraser().erase("~test-eraser-absent").isSuccess)
    }

    @Test
    fun `the noop eraser is a no-op, not a failure`() {
        assertTrue(ShipDataEraser.Noop.erase("~anything").isSuccess)
    }
}
