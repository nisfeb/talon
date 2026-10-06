package io.nisfeb.talon.relay

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * An iPhone registers with its VoIP token and, once the owner allows
 * notifications, its alert token: "<voip>|<alert>". The one iPhone on
 * the relay (2026-10-06) had registered before the second came, so
 * every message to it was dropped. Its new endpoint goes by device id,
 * as caps do, and keeps its ships and caps.
 */
class EndpointUpdateTest {
    @Test
    fun `a device's endpoint is replaced by id, its caps kept`() {
        val path = Files.createTempFile("relay-endpoint-", ".db").toFile().also { it.delete() }.absolutePath
        val db = Db(path).also { it.migrate() }
        db.upsertDevice("dev-1", "voip123|", "ios")
        db.setCaps("dev-1", listOf("read"))
        assertTrue(db.setEndpoint("dev-1", "voip123|alert456"))
        assertEquals(Db.DeviceRow("voip123|alert456", "ios", setOf("read")), db.deviceFor("dev-1"))
        assertEquals("alert456", Push.iosAlertToken(db.deviceFor("dev-1")!!.pushEndpoint))
        assertFalse(db.setEndpoint("never-was", "voip|alert"), "an unknown device is not made")
        java.io.File(path).delete()
    }
}
