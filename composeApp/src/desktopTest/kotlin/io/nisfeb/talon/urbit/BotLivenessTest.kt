package io.nisfeb.talon.urbit

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.ui.ContactMap
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tlon 12.3.0: a bot's ship publishes whether its gateway is up in the
 * `bot-liveness` contact field (docs/bot-liveness.md; desk/app/steward.hoon
 * gateway module), `{"type":"text","value":"{\"v\":1,\"state\":\"offline\"}"}`.
 */
class BotLivenessTest {
    private fun fields(json: String) = Json.parseToJsonElement(json) as JsonObject
    private fun claim(text: String) = """{"bot-liveness":{"type":"text","value":${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive(text))}}}"""
    private val bot = "~pinser-botter-sampel"

    @Test
    fun `a claim is read as Tlon reads it`() {
        assertEquals(true, botLivenessOf(bot, fields(claim("""{"v":1,"state":"online"}"""))))
        assertEquals(false, botLivenessOf(bot, fields(claim("""{"v":1,"state":"offline","since":3}"""))), "unknown fields are ignored")
        assertNull(botLivenessOf(bot, fields(claim("""{"v":2,"state":"online"}"""))), "another version")
        assertNull(botLivenessOf(bot, fields(claim("""{"v":1,"state":"asleep"}"""))))
        assertNull(botLivenessOf(bot, fields(claim("""{"v":1,"state":"online","pad":"${"x".repeat(120)}"}"""))), "over 128 bytes")
        assertNull(botLivenessOf(bot, fields(claim("not json"))))
        assertNull(botLivenessOf(bot, fields("""{"nickname":{"type":"text","value":"B"}}""")), "no claim, unknown")
    }

    @Test
    fun `only a bot's claim counts`() {
        val offline = claim("""{"v":1,"state":"offline"}""")
        assertNull(botLivenessOf("~sampel-palnet", fields(offline)), "a person cannot wear it")
        val withInfo = fields(offline.dropLast(1) + ""","bot-info":{"type":"text","value":"{}"}}""")
        assertEquals(false, botLivenessOf("~sampel-palnet", withInfo), "a ship claiming bot-info can")
    }

    @Test
    fun `a contact update from the ship shows an offline bot in its messages' byline`() = runBlocking<Unit> {
        val dir = createTempDirectory(prefix = "talon-bot-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(dir, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        try {
            val repo = TlonChatRepo(db).apply { attachForTest(FakeShip().channel, "~zod") }
            val who = "~pinser-botter-ripdel"
            repo.applyEvent(Json.parseToJsonElement(
                """{"id":1,"response":"diff","json":{"peer":{"who":"$who","contact":${claim("""{"v":1,"state":"offline"}""").dropLast(1)},"nickname":{"type":"text","value":"Helper"}}}}}""",
            ))
            assertEquals(false, BotLiveness.online.value[who])
            val map = ContactMap(botOnline = BotLiveness.online.value)
            assertEquals("Helper · Bot · Offline · 9:41", map.byline(who, "Helper", "9:41"))
            assertEquals("Mittens · 9:41", map.byline("~mitlyn-ditrel", "Mittens", "9:41"), "a person's byline is as it was")
            // Back online: the next update says so, and the byline is plain again.
            repo.applyEvent(Json.parseToJsonElement(
                """{"id":2,"response":"diff","json":{"peer":{"who":"$who","contact":${claim("""{"v":1,"state":"online"}""")}}}}""",
            ))
            assertEquals(true, BotLiveness.online.value[who])
            assertEquals("Helper · 9:41", ContactMap(botOnline = BotLiveness.online.value).byline(who, "Helper", "9:41"))
        } finally {
            db.close()
            dir.deleteRecursively()
        }
    }
}
