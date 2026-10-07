package io.nisfeb.talon.relay

import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A read push goes only to an app that said it understands one: before
 * 1.8.1-rc24, Talon on Android shows any push it does not know as a new
 * message, so every read elsewhere would have rung the phone. The app
 * says so at /devices/{id}/caps after registering, and registering
 * again clears it; a device kept from before has said nothing.
 */
class ReadCapsTest {
    private fun tempDb(): String = Files.createTempFile("relay-caps-", ".db").toFile().also { it.delete() }.absolutePath

    @Test
    fun `a device's caps are set by id, and re-registering clears them`() {
        val path = tempDb()
        val db = Db(path).also { it.migrate() }
        db.upsertDevice("dev-1", "https://ntfy.test/a", "unifiedpush")
        assertEquals(emptySet(), db.deviceFor("dev-1")?.caps, "a new device has said nothing")
        assertTrue(db.setCaps("dev-1", listOf("read", " ", "read")))
        assertEquals(setOf("read"), db.deviceFor("dev-1")?.caps)
        db.upsertDevice("dev-1", "https://ntfy.test/a", "unifiedpush")
        assertEquals(emptySet(), db.deviceFor("dev-1")?.caps, "an older app re-registering says nothing")
        assertFalse(db.setCaps("never-was", listOf("read")))
        java.io.File(path).delete()
    }

    @Test
    fun `a relay database from before caps gains the column, its devices keep everything else`() {
        val path = tempDb()
        DriverManager.getConnection("jdbc:sqlite:$path").use { c ->
            c.createStatement().use { s ->
                s.executeUpdate("CREATE TABLE devices (id TEXT PRIMARY KEY, push_endpoint TEXT NOT NULL, platform TEXT NOT NULL, created_at INTEGER NOT NULL)")
                s.executeUpdate("INSERT INTO devices VALUES ('dev-old', 'https://ntfy.test/old', 'unifiedpush', 1)")
            }
        }
        val db = Db(path).also { it.migrate(); it.migrate() }
        assertEquals(Db.DeviceRow("https://ntfy.test/old", "unifiedpush", emptySet()), db.deviceFor("dev-old"))
        assertTrue(db.setCaps("dev-old", listOf("read")))
        assertEquals(setOf("read"), db.deviceFor("dev-old")?.caps)
        java.io.File(path).delete()
    }

    @Test
    fun `only a device that declared read is sent one`() {
        val read = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"read":{"source":{"dm":{"ship":"~bus"}},"activity":{"count":0,"notify-count":0,"notify":false,"unread":null}}}""",
        ) as kotlinx.serialization.json.JsonObject
        assertEquals("~bus", readPushWhom(read, setOf("read")))
        assertEquals(null, readPushWhom(read, emptySet()), "an app that said nothing")
        assertEquals(null, readPushWhom(read, setOf("ring")))
    }

    // Ktor's own JSON refuses a field it does not know, which would have
    // turned a newer app's registration away. The relay's does not.
    @Test
    fun `a registration carrying fields the relay does not know still parses`() {
        val req = RelayJson.decodeFromString<RegisterRequest>(
            """{"platform":"unifiedpush","pushEndpoint":"https://ntfy.test/a","deviceId":"","shipUrl":"https://ship.test",
                "patp":"~zod","code":"lidlut-tabwed-pillex-ridrup","caps":["read"],"someday":{"x":1}}""",
        )
        assertEquals("~zod", req.patp)
        assertEquals(listOf("read"), RelayJson.decodeFromString<CapsRequest>("""{"caps":["read"]}""").caps)
    }
}
