package io.nisfeb.talon.urbit

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.ai.AiSettings
import io.nisfeb.talon.ai.withProfile
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.AssistantConversationEntity
import io.nisfeb.talon.data.AssistantHistoryEntity
import io.nisfeb.talon.data.LoopEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What this device puts on %settings: AI credentials only when the user
 * syncs them and never as blanks that would wipe a peer's; assistant
 * history and loops; and the lease that lets one device fire a loop.
 */
class SettingsSyncPushTest {
    private val db: AppDatabase = createTempDirectory(prefix = "talon-push-").toFile().let { dir ->
        Room.databaseBuilder<AppDatabase>(File(dir, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
    }
    private val ship = FakeShip("~zod")

    @AfterTest
    fun close() = db.close()

    private fun sync(cfg: AiSettings.Config = config()) =
        SettingsSyncImpl(db = db, aiSettings = FakeAiSettings(cfg)).apply { attach(ship.channel) }

    private fun config(sync: Boolean = true, key: String = "", stt: String = "", sttRemovedAt: Long = 0, device: String = "dev-a") =
        AiSettings.Config(
            provider = AiSettings.Provider.OpenAi, apiKey = key, model = "gpt-x", syncEnabled = sync,
            sttApiKey = stt, sttApiKeyRemovedAtMs = sttRemovedAt, deviceId = device,
        )

    private fun live(body: suspend CoroutineScope.() -> Unit) = runBlocking<Unit> {
        val events = ship.channel.events().launchIn(this)
        try { body() } finally { events.cancel() }
    }

    /** The last value put at [bucket]/[entry], as the ship stores it. */
    private fun put(bucket: String, entry: String): JsonObject? = ship.pokesTo("settings").map { it.json.jsonObject }
        .lastOrNull { p -> p["put-entry"]?.jsonObject?.let { it["bucket-key"]?.jsonPrimitive?.content == bucket && it["entry-key"]?.jsonPrimitive?.content == entry } == true }
        ?.let { Json.parseToJsonElement(it["put-entry"]!!.jsonObject["value"]!!.jsonPrimitive.content).jsonObject }

    private fun deleted(): List<String> = ship.pokesTo("settings").map { it.json.toString() }.filter { "del-" in it }

    // ─── AI credentials ────────────────────────────────────────────

    @Test
    fun `with sync off, only preferences go up and no key leaves the device`() = live {
        sync(config(sync = false, key = "sk-secret")).pushAiSettings()
        assertNull(put("ai-settings", "credentials"))
        val prefs = put("ai-settings", "config")!!
        assertTrue("catchMeUpEnabled" in prefs && "apiKey" !in prefs, prefs.toString())
        assertTrue(ship.pokesTo("settings").none { "sk-secret" in it.json.toString() })
    }

    @Test
    fun `with sync on, the key goes in its own entry and in the preferences for older builds`() = live {
        sync(config(key = "sk-secret")).pushAiSettings()
        assertEquals("sk-secret", put("ai-settings", "credentials")!!["apiKey"]!!.jsonPrimitive.content)
        assertEquals("sk-secret", put("ai-settings", "config")!!["apiKey"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a device with no key says nothing of keys, so it cannot blank another's`() = live {
        sync(config()).pushAiSettings()
        assertNull(put("ai-settings", "credentials"))
        assertFalse("apiKey" in put("ai-settings", "config")!!)
    }

    @Test
    fun `taking out the last provider reaches the ship, and a profile that only arrived says nothing`() = live {
        val local = io.nisfeb.talon.ai.AiProvider("local", io.nisfeb.talon.ai.ProviderKind.OpenAiCompatible, "Local", baseUrl = "http://127.0.0.1:1234/v1")
        val had = config().copy(savedProfile = io.nisfeb.talon.ai.AiProfile(providers = listOf(local)))
        // Arrived, not saved here: no key and no stamp, so nothing to say.
        sync(had).pushAiSettings()
        assertNull(put("ai-settings", "credentials"), "an unsaved profile never overwrites the ship's")

        // The owner takes it out here: the empty profile goes, so it does
        // not come back from the ship on the next connect.
        val emptied = had.withProfile(io.nisfeb.talon.ai.AiProfile(), now = 5_000)
        sync(emptied).pushAiSettings()
        val creds = assertNotNull(put("ai-settings", "credentials"))
        val profile = creds["profile"]!!.jsonObject
        assertTrue(profile["providers"]?.jsonArray.isNullOrEmpty(), "no providers: $profile")
        assertFalse("savedHereAtMs" in profile, "the stamp is this device's own")
    }

    @Test
    fun `every credential this device holds goes up, and none it lacks`() = live {
        val full = config(key = "sk-secret").copy(
            baseUrl = "https://api.example", braveApiKey = "brv-1",
            privateBaseUrl = "http://box:1234/v1", privateModel = "qwen3", privateApiKey = "prv-1",
            revokedKeys = mapOf(io.nisfeb.talon.ai.keyPrint("sk-old") to io.nisfeb.talon.ai.KeyMark(at = 5)),
        )
        sync(full).pushAiSettings()
        val creds = put("ai-settings", "credentials")!!
        for (k in listOf("model", "baseUrl", "braveApiKey", "privateBaseUrl", "privateModel", "privateApiKey", "revokedKeys")) {
            assertTrue(k in creds, "$k goes up: $creds")
        }
        assertEquals("brv-1" to "prv-1", creds["braveApiKey"]!!.jsonPrimitive.content to creds["privateApiKey"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a credential this device lacks is not sent, blank or zero`() = live {
        sync(config(key = "sk-secret").copy(model = null)).pushAiSettings()
        val creds = put("ai-settings", "credentials")!!
        for (k in listOf("model", "baseUrl", "braveApiKey", "privateBaseUrl", "privateModel", "privateApiKey", "revokedKeys", "sttApiKey", "sttApiKeyRemovedAtMs")) {
            assertFalse(k in creds, "$k would blank a peer's: $creds")
        }
    }

    @Test
    fun `a removed transcription key travels as a removal, not as an empty key`() = live {
        sync(config(key = "sk-secret", sttRemovedAt = 1_234)).pushAiSettings()
        val creds = put("ai-settings", "credentials")!!
        assertFalse("sttApiKey" in creds)
        assertEquals("1234", creds["sttApiKeyRemovedAtMs"]!!.jsonPrimitive.content)
    }

    @Test
    fun `clearing the ship's AI settings drops the whole bucket`() = live {
        sync().clearAiSettingsOnShip()
        assertTrue(deleted().single().let { "del-bucket" in it && "ai-settings" in it })
    }

    // ─── assistant history and loops ───────────────────────────────

    @Test
    fun `an assistant turn goes up with its conversation, and one without an id does not`() = live {
        val s = sync()
        val conv = AssistantConversationEntity(gid = "c1", title = "Trip", createdAt = 1, updatedAt = 2, centroid = ByteArray(0), dim = 0, turnCount = 1)
        val turn = AssistantHistoryEntity(gid = "t1", mode = "Assistant", question = "Where?", answer = "Lisbon", createdAt = 3)
        s.pushAssistantTurn(conv.copy(gid = ""), turn)
        assertTrue(ship.pokesTo("settings").isEmpty())
        s.pushAssistantTurn(conv, turn)
        assertEquals("Trip", put("assistant-conversations", "c1")!!["title"]!!.jsonPrimitive.content)
        val t = put("assistant-turns", "t1")!!
        assertEquals("c1" to "Lisbon", t["convGid"]!!.jsonPrimitive.content to t["answer"]!!.jsonPrimitive.content)
    }

    @Test
    fun `clearing assistant history drops both of its buckets`() = live {
        sync().clearAssistantHistoryOnShip()
        assertEquals(2, deleted().count { "del-bucket" in it })
    }

    @Test
    fun `a loop goes up without this device's write grant, and deleting it drops its lease`() = live {
        val s = sync()
        s.pushLoop(LoopEntity(gid = "l1", name = "Digest", prompt = "Sum up", intervalMinutes = 60, writesAuthorized = true, createdAt = 1, updatedAt = 2))
        val loop = put("loops", "l1")!!
        assertTrue("Sum up" in loop.toString() && "writesAuthorized" !in loop, loop.toString())
        s.deleteLoop("l1")
        assertTrue(deleted().any { "loops" in it } && deleted().any { "automation-claims" in it }, deleted().toString())
    }

    // ─── the lease on a loop's fire ────────────────────────────────

    private fun leaseHeld(by: String, at: Long) {
        ship.scries["settings/desk/talon"] = """{"desk":{"automation-claims":{"l1":{"holder":"$by","claimedAt":$at}}}}"""
    }

    /** Whoever stakes a claim, [winner] is holding it once the settle is over. */
    private fun contestWonBy(winner: String) {
        ship.refuse = { p ->
            if ("automation-claims" in p.json.toString()) leaseHeld(winner, System.currentTimeMillis())
            null
        }
    }

    @Test
    fun `an unclaimed loop is claimed, and fired only if the claim holds`() = live {
        contestWonBy("dev-a")
        assertTrue(sync().claimKey("l1", staleMs = 60_000, settleMs = 10))
        assertEquals("dev-a", put("automation-claims", "l1")!!["holder"]!!.jsonPrimitive.content)
    }

    @Test
    fun `losing the contest to another device means not firing`() = live {
        contestWonBy("dev-b")
        assertFalse(sync().claimKey("l1", staleMs = 60_000, settleMs = 10))
    }

    @Test
    fun `another device's fresh claim is left alone`() = live {
        leaseHeld("dev-b", System.currentTimeMillis())
        assertFalse(sync().claimKey("l1", staleMs = 60_000, settleMs = 10))
        assertTrue(ship.pokesTo("settings").isEmpty(), "no counter-claim")
    }

    @Test
    fun `a claim gone stale is taken over, and our own is renewed at once`() = live {
        leaseHeld("dev-b", System.currentTimeMillis() - 120_000)
        contestWonBy("dev-a")
        assertTrue(sync().claimKey("l1", staleMs = 60_000, settleMs = 10))
        leaseHeld("dev-a", System.currentTimeMillis() - 1_000)
        ship.refuse = { null }
        val took = kotlin.system.measureTimeMillis { assertTrue(sync().claimKey("l1", staleMs = 60_000, settleMs = 10_000)) }
        assertTrue(took < 5_000, "our own lease is renewed without the settle wait: $took ms")
    }

    @Test
    fun `with no device id, nothing is claimed`() = live {
        assertFalse(sync(config(device = "")).claimKey("l1", staleMs = 60_000, settleMs = 10))
    }
}
