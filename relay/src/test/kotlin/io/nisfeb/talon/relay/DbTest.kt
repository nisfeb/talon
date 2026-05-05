package io.nisfeb.talon.relay

import org.junit.After
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the per-user device + ship registry shape. Each test uses a
 * fresh tempfile sqlite so order doesn't matter and parallelization
 * later won't introduce cross-test bleed.
 */
class DbTest {

    private lateinit var dbFile: String
    private lateinit var db: Db

    @Before
    fun setUp() {
        val tmp = Files.createTempFile("relay-test-", ".db").toFile()
        tmp.delete()  // sqlite-jdbc opens its own file
        dbFile = tmp.absolutePath
        db = Db(dbFile).also { it.migrate() }
    }

    @After
    fun tearDown() {
        java.io.File(dbFile).delete()
    }

    @Test
    fun `migrate is idempotent`() {
        // Running migrate twice on the same file shouldn't throw.
        db.migrate()
        db.migrate()
    }

    @Test
    fun `upsertDevice then pushEndpointFor returns the endpoint`() {
        db.upsertDevice("dev-1", "https://ntfy.sh/abc", "unifiedpush")
        assertEquals("https://ntfy.sh/abc", db.pushEndpointFor("dev-1"))
    }

    @Test
    fun `upsertDevice replaces endpoint on conflict`() {
        // UnifiedPush distributor reset (or user switched
        // distributors) → device re-registers with a new endpoint.
        // Pushing to the stale URL just gets 410 Gone forever.
        db.upsertDevice("dev-1", "https://ntfy.sh/old", "unifiedpush")
        db.upsertDevice("dev-1", "https://ntfy.sh/new", "unifiedpush")
        assertEquals("https://ntfy.sh/new", db.pushEndpointFor("dev-1"))
    }

    @Test
    fun `pushEndpointFor unknown device returns null`() {
        assertNull(db.pushEndpointFor("never-existed"))
    }

    @Test
    fun `upsertShip then shipsForDevice returns the row`() {
        db.upsertDevice("dev-1", "fcm-abc", "android")
        val sealed = Crypto.Sealed(
            ciphertextB64 = "ct", saltB64 = "salt", nonceB64 = "nonce",
        )
        db.upsertShip("dev-1", "~sampel-palnet", "https://example.com", sealed)

        val rows = db.shipsForDevice("dev-1")
        assertEquals(1, rows.size)
        val r = rows[0]
        assertEquals("dev-1", r.deviceId)
        assertEquals("~sampel-palnet", r.patp)
        assertEquals("https://example.com", r.shipUrl)
        assertEquals("ct", r.ciphertextB64)
        assertEquals("salt", r.saltB64)
        assertEquals("nonce", r.nonceB64)
    }

    @Test
    fun `upsertShip replaces cookie on (device, patp) conflict`() {
        // The user re-registers (e.g. their +code rotated). Same
        // (device, patp) → row updated in place, not duplicated.
        db.upsertDevice("dev-1", "fcm-abc", "android")
        db.upsertShip(
            "dev-1", "~sampel-palnet", "https://a.com",
            Crypto.Sealed("ct1", "salt1", "nonce1"),
        )
        db.upsertShip(
            "dev-1", "~sampel-palnet", "https://b.com",
            Crypto.Sealed("ct2", "salt2", "nonce2"),
        )

        val rows = db.shipsForDevice("dev-1")
        assertEquals(1, rows.size)
        assertEquals("https://b.com", rows[0].shipUrl)
        assertEquals("ct2", rows[0].ciphertextB64)
    }

    @Test
    fun `same patp under different devices is allowed`() {
        // Two phones registered for the same ship. Both should get
        // pushes; the unique key is (device_id, patp), not patp.
        db.upsertDevice("dev-1", "fcm-1", "android")
        db.upsertDevice("dev-2", "fcm-2", "android")
        val sealed = Crypto.Sealed("ct", "salt", "nonce")
        db.upsertShip("dev-1", "~sampel", "https://x.com", sealed)
        db.upsertShip("dev-2", "~sampel", "https://x.com", sealed)

        assertEquals(1, db.shipsForDevice("dev-1").size)
        assertEquals(1, db.shipsForDevice("dev-2").size)
        assertEquals(2, db.allShips().size)
    }

    @Test
    fun `deleteDevice cascades to ships and last_event`() {
        db.upsertDevice("dev-1", "fcm-abc", "android")
        db.upsertShip(
            "dev-1", "~sampel", "https://x.com",
            Crypto.Sealed("ct", "salt", "nonce"),
        )
        val rowId = db.shipsForDevice("dev-1").first().rowId
        db.setLastEventId(rowId, "dev-1", 42L)

        db.deleteDevice("dev-1")

        assertTrue(db.shipsForDevice("dev-1").isEmpty())
        assertNull(db.pushEndpointFor("dev-1"))
        assertNull(db.lastEventId(rowId, "dev-1"))
    }

    @Test
    fun `lastEventId is null for never-pushed pair`() {
        db.upsertDevice("dev-1", "fcm-abc", "android")
        db.upsertShip(
            "dev-1", "~sampel", "https://x.com",
            Crypto.Sealed("ct", "salt", "nonce"),
        )
        val rowId = db.shipsForDevice("dev-1").first().rowId
        assertNull(db.lastEventId(rowId, "dev-1"))
    }

    @Test
    fun `setLastEventId then lastEventId round-trips`() {
        db.upsertDevice("dev-1", "fcm-abc", "android")
        db.upsertShip(
            "dev-1", "~sampel", "https://x.com",
            Crypto.Sealed("ct", "salt", "nonce"),
        )
        val rowId = db.shipsForDevice("dev-1").first().rowId
        db.setLastEventId(rowId, "dev-1", 100L)
        assertEquals(100L, db.lastEventId(rowId, "dev-1"))
    }

    @Test
    fun `setLastEventId overwrites the previous cursor on conflict`() {
        // Cursor advances; we never want to roll BACK accidentally,
        // but the impl trusts the caller. Pin that the upsert path
        // works (no INSERT-only path, no row-doubling).
        db.upsertDevice("dev-1", "fcm-abc", "android")
        db.upsertShip(
            "dev-1", "~sampel", "https://x.com",
            Crypto.Sealed("ct", "salt", "nonce"),
        )
        val rowId = db.shipsForDevice("dev-1").first().rowId
        db.setLastEventId(rowId, "dev-1", 50L)
        db.setLastEventId(rowId, "dev-1", 100L)
        assertEquals(100L, db.lastEventId(rowId, "dev-1"))
    }

    @Test
    fun `allShips spans devices`() {
        db.upsertDevice("dev-1", "fcm-1", "android")
        db.upsertDevice("dev-2", "fcm-2", "android")
        db.upsertShip(
            "dev-1", "~a", "https://1.com",
            Crypto.Sealed("ct", "salt", "nonce"),
        )
        db.upsertShip(
            "dev-2", "~b", "https://2.com",
            Crypto.Sealed("ct", "salt", "nonce"),
        )
        assertEquals(2, db.allShips().size)
    }
}
