package io.nisfeb.talon.relay

import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.UUID

/**
 * SQLite-backed device + ship registry. One file, dead simple:
 * the relay's expected fan-out (single-tenant friendly, ~thousands
 * of users per box) doesn't need anything fancier.
 *
 * Tables:
 *   devices    — one row per registered device.
 *   ships      — encrypted cookie + url, joined to a device.
 *   last_event — per-(ship, device) cursor, used by the SSE consumer
 *                to dedup pushes across relay restarts.
 *
 * Schema lives in the [SCHEMA] constant; bumping requires an
 * idempotent ALTER TABLE block in [migrate].
 */
class Db(private val path: String) {

    init {
        // sqlite-jdbc registers itself; explicit Class.forName isn't
        // strictly needed but pins the driver if some agent strips it.
        Class.forName("org.sqlite.JDBC")
    }

    fun connect(): Connection =
        DriverManager.getConnection("jdbc:sqlite:$path")

    fun migrate() {
        connect().use { c ->
            c.createStatement().use { s ->
                s.executeUpdate(SCHEMA)
            }
        }
    }

    // ───────── devices ─────────

    /**
     * Upsert a device by its [deviceId]. Returns the FCM token
     * stored under that id — used by the device to detect token
     * rotations between registrations.
     */
    fun upsertDevice(deviceId: String, fcmToken: String, platform: String) {
        connect().use { c ->
            c.prepareStatement(
                """
                INSERT INTO devices (id, fcm_token, platform, created_at)
                VALUES (?, ?, ?, strftime('%s', 'now') * 1000)
                ON CONFLICT(id) DO UPDATE SET
                  fcm_token = excluded.fcm_token,
                  platform = excluded.platform
                """,
            ).use { ps ->
                ps.setString(1, deviceId)
                ps.setString(2, fcmToken)
                ps.setString(3, platform)
                ps.executeUpdate()
            }
        }
    }

    fun deleteDevice(deviceId: String) {
        connect().use { c ->
            c.prepareStatement("DELETE FROM ships WHERE device_id = ?").use {
                it.setString(1, deviceId); it.executeUpdate()
            }
            c.prepareStatement("DELETE FROM last_event WHERE device_id = ?").use {
                it.setString(1, deviceId); it.executeUpdate()
            }
            c.prepareStatement("DELETE FROM devices WHERE id = ?").use {
                it.setString(1, deviceId); it.executeUpdate()
            }
        }
    }

    fun fcmTokenFor(deviceId: String): String? = connect().use { c ->
        c.prepareStatement("SELECT fcm_token FROM devices WHERE id = ?").use { ps ->
            ps.setString(1, deviceId)
            ps.executeQuery().use { rs ->
                if (rs.next()) rs.getString("fcm_token") else null
            }
        }
    }

    // ───────── ships ─────────

    data class ShipRow(
        val rowId: Long,
        val deviceId: String,
        val patp: String,
        val shipUrl: String,
        val ciphertextB64: String,
        val saltB64: String,
        val nonceB64: String,
    )

    fun upsertShip(
        deviceId: String,
        patp: String,
        shipUrl: String,
        sealed: Crypto.Sealed,
    ) {
        connect().use { c ->
            c.prepareStatement(
                """
                INSERT INTO ships (device_id, patp, ship_url, ciphertext, salt, nonce, registered_at)
                VALUES (?, ?, ?, ?, ?, ?, strftime('%s', 'now') * 1000)
                ON CONFLICT(device_id, patp) DO UPDATE SET
                  ship_url = excluded.ship_url,
                  ciphertext = excluded.ciphertext,
                  salt = excluded.salt,
                  nonce = excluded.nonce
                """,
            ).use { ps ->
                ps.setString(1, deviceId)
                ps.setString(2, patp)
                ps.setString(3, shipUrl)
                ps.setString(4, sealed.ciphertextB64)
                ps.setString(5, sealed.saltB64)
                ps.setString(6, sealed.nonceB64)
                ps.executeUpdate()
            }
        }
    }

    fun shipsForDevice(deviceId: String): List<ShipRow> = connect().use { c ->
        c.prepareStatement(
            "SELECT id, device_id, patp, ship_url, ciphertext, salt, nonce " +
                "FROM ships WHERE device_id = ?",
        ).use { ps ->
            ps.setString(1, deviceId)
            ps.executeQuery().use { rs -> readShipRows(rs) }
        }
    }

    fun allShips(): List<ShipRow> = connect().use { c ->
        c.createStatement().use { s ->
            s.executeQuery(
                "SELECT id, device_id, patp, ship_url, ciphertext, salt, nonce FROM ships",
            ).use { rs -> readShipRows(rs) }
        }
    }

    private fun readShipRows(rs: ResultSet): List<ShipRow> {
        val out = mutableListOf<ShipRow>()
        while (rs.next()) {
            out += ShipRow(
                rowId = rs.getLong("id"),
                deviceId = rs.getString("device_id"),
                patp = rs.getString("patp"),
                shipUrl = rs.getString("ship_url"),
                ciphertextB64 = rs.getString("ciphertext"),
                saltB64 = rs.getString("salt"),
                nonceB64 = rs.getString("nonce"),
            )
        }
        return out
    }

    // ───────── last-event cursor ─────────

    /** Last `event-id` we successfully pushed for this (ship, device).
     *  null when we've never pushed for the pair — the SSE consumer
     *  starts from "now" in that case to avoid spamming a fresh
     *  registration with backlog. */
    fun lastEventId(shipRowId: Long, deviceId: String): Long? = connect().use { c ->
        c.prepareStatement(
            "SELECT event_id FROM last_event WHERE ship_id = ? AND device_id = ?",
        ).use { ps ->
            ps.setLong(1, shipRowId)
            ps.setString(2, deviceId)
            ps.executeQuery().use { rs ->
                if (rs.next()) rs.getLong("event_id") else null
            }
        }
    }

    fun setLastEventId(shipRowId: Long, deviceId: String, eventId: Long) {
        connect().use { c ->
            c.prepareStatement(
                """
                INSERT INTO last_event (ship_id, device_id, event_id, updated_at)
                VALUES (?, ?, ?, strftime('%s', 'now') * 1000)
                ON CONFLICT(ship_id, device_id) DO UPDATE SET
                  event_id = excluded.event_id,
                  updated_at = excluded.updated_at
                """,
            ).use { ps ->
                ps.setLong(1, shipRowId)
                ps.setString(2, deviceId)
                ps.setLong(3, eventId)
                ps.executeUpdate()
            }
        }
    }

    private companion object {
        const val SCHEMA = """
            CREATE TABLE IF NOT EXISTS devices (
                id TEXT PRIMARY KEY,
                fcm_token TEXT NOT NULL,
                platform TEXT NOT NULL,
                created_at INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS ships (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device_id TEXT NOT NULL,
                patp TEXT NOT NULL,
                ship_url TEXT NOT NULL,
                ciphertext TEXT NOT NULL,
                salt TEXT NOT NULL,
                nonce TEXT NOT NULL,
                registered_at INTEGER NOT NULL,
                UNIQUE(device_id, patp),
                FOREIGN KEY(device_id) REFERENCES devices(id)
            );
            CREATE INDEX IF NOT EXISTS ships_by_url ON ships(ship_url);
            CREATE TABLE IF NOT EXISTS last_event (
                ship_id INTEGER NOT NULL,
                device_id TEXT NOT NULL,
                event_id INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY(ship_id, device_id),
                FOREIGN KEY(ship_id) REFERENCES ships(id),
                FOREIGN KEY(device_id) REFERENCES devices(id)
            );
        """
    }
}

internal fun newDeviceId(): String = UUID.randomUUID().toString()
