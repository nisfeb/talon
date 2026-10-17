package io.nisfeb.talon.urbit

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.ai.AiFeature
import io.nisfeb.talon.ai.AiProvider
import io.nisfeb.talon.ai.ProviderKind
import io.nisfeb.talon.ai.paidSettings
import io.nisfeb.talon.ai.profile
import io.nisfeb.talon.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The AI settings on %settings under Armillary-only (schemaVersion 3):
 * the switches and the instructions travel, no key ever does, an older
 * build's switches are not taken, and the keys an older build left on
 * the ship are taken off it.
 */
class AiSettingsSyncTest {
    private val db: AppDatabase = createTempDirectory(prefix = "talon-aisync-").toFile().let { dir ->
        Room.databaseBuilder<AppDatabase>(File(dir, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
    }
    private val ship = FakeShip("~zod")

    @AfterTest
    fun close() = db.close()

    private fun live(body: suspend CoroutineScope.() -> Unit) = runBlocking<Unit> {
        val events = ship.channel.events().launchIn(this)
        try { body() } finally { events.cancel() }
    }

    /**
     * The config entry a 1.8.29 phone wrote on ricsul (2026-10-16, from
     * its log), keys made up: switches, keys, an own OpenAI provider for
     * transcription, and catch-up and the assistant here turned off.
     */
    private val olderEntry = """{"schemaVersion":2,"catchMeUpEnabled":false,"smartFeaturesEnabled":true,"askUrbitEnabled":false,
        "agentEnabled":false,"urbitKnowledgePrompt":"","assistantPrompt":"Be brief.","loopPrompt":"",
        "switches":{"CatchUp":false,"Assistant":false,"orrery":true},"provider":"OpenRouter","model":"anthropic/claude-opus-5.5",
        "apiKey":"sk-or-old","braveApiKey":"brave-old","sttApiKey":"sk-whisper-old","providerKeys":{"pmvb2q87c":"sk-oa-old"},
        "profile":{"providers":[{"id":"device","kind":"ThisDevice","label":"On this device"},{"id":"pmvb2q87c","kind":"OpenAi","label":"OpenAI"}],
        "defaultModel":{"provider":"armillary","model":""},"features":{"CatchUp":{"on":true},"Assistant":{"on":true},
        "Transcription":{"model":{"provider":"pmvb2q87c","model":"whisper-1"}}},"orrery":true},
        "frontierReadsMessages":false,"revokedKeys":{"5e83d4ebca97e9c9":{"at":1792161670976,"revoked":false}}}"""

    private val olderKeys = """{"schemaVersion":2,"provider":"OpenRouter","apiKey":"sk-or-old","sttApiKey":"sk-whisper-old","providerKeys":{"pmvb2q87c":"sk-oa-old"}}"""

    private fun bucket(vararg entries: Pair<String, String>) = JsonObject(entries.associate { (k, v) -> k to Json.parseToJsonElement(v) })

    @Test
    fun `an older build's entry gives its instructions, and nothing of its keys or switches`() = runBlocking {
        val here = FakeAiSettings(paidSettings())
        SettingsSyncImpl(db = db, aiSettings = here).applyBucket(SettingsSyncImpl.BUCKET_AI_SETTINGS, bucket("config" to olderEntry, "credentials" to olderKeys))
        val c = here.state.value
        assertEquals("Be brief.", c.assistantPrompt)
        assertTrue(c.profile().isOn(AiFeature.CatchUp), "its off was most likely no key yet")
        assertTrue(c.assistantOn())
        assertEquals(paidSettings().profile().providers, c.profile().providers, "no provider arrives")
        assertEquals(listOf("", "", ""), listOf(c.apiKey, c.sttApiKey, c.braveApiKey))
        assertNull(c.profile().orrery, "nor its Orrery switch")
    }

    @Test
    fun `this build's entry gives its switches and toggles`() = runBlocking {
        val here = FakeAiSettings(paidSettings())
        val entry = """{"schemaVersion":3,"catchMeUpEnabled":false,"smartFeaturesEnabled":true,"askUrbitEnabled":false,"agentEnabled":false,
            "urbitKnowledgePrompt":"","assistantPrompt":"","loopPrompt":"Daily.","switches":{"CatchUp":false,"Assistant":false,"orrery":true}}"""
        SettingsSyncImpl(db = db, aiSettings = here).applyBucket(SettingsSyncImpl.BUCKET_AI_SETTINGS, bucket("config" to entry))
        val c = here.state.value
        assertFalse(c.profile().isOn(AiFeature.CatchUp))
        assertFalse(c.assistantOn())
        assertEquals(true, c.profile().orrery)
        assertEquals("Daily.", c.loopPrompt)
        assertEquals(paidSettings().profile().armillary(), c.profile().armillary(), "this device's Armillary row stays")
    }

    @Test
    fun `what goes up is the switches and the instructions, never a key`() = live {
        // Keys and an own provider left from before this build.
        val leftover = paidSettings().let { c ->
            c.copy(
                apiKey = "sk-or-old", sttApiKey = "sk-whisper-old", braveApiKey = "brave-old", assistantPrompt = "Be brief.",
                savedProfile = c.profile().copy(providers = c.profile().providers + AiProvider("or", ProviderKind.OpenRouter, "OpenRouter", apiKey = "sk-own")),
            )
        }
        SettingsSyncImpl(db = db, aiSettings = FakeAiSettings(leftover)).apply { attach(ship.channel) }.pushAiSettings()
        val pokes = ship.pokesTo("settings").map { it.json.jsonObject }
        assertEquals(1, pokes.size, pokes.toString())
        val put = pokes.single()["put-entry"]!!.jsonObject
        assertEquals("ai-settings", put["bucket-key"]!!.jsonPrimitive.content)
        assertEquals("config", put["entry-key"]!!.jsonPrimitive.content)
        val sent = Json.parseToJsonElement(put["value"]!!.jsonPrimitive.content).jsonObject
        assertEquals(
            buildJsonObject {
                put("schemaVersion", JsonPrimitive(3))
                put("catchMeUpEnabled", JsonPrimitive(true))
                put("smartFeaturesEnabled", JsonPrimitive(true))
                put("askUrbitEnabled", JsonPrimitive(true))
                put("agentEnabled", JsonPrimitive(true))
                put("urbitKnowledgePrompt", JsonPrimitive(""))
                put("assistantPrompt", JsonPrimitive("Be brief."))
                put("loopPrompt", JsonPrimitive(""))
                put("switches", buildJsonObject { put("CatchUp", JsonPrimitive(true)); put("Assistant", JsonPrimitive(true)) })
            },
            sent,
        )
        assertTrue(ship.pokesTo("settings").none { p -> listOf("sk-", "brave-old", "lease").any { it in p.json.toString() } })
    }

    @Test
    fun `a ship with an older entry is brought up to date once, and an older build's keys are taken off it`() = live {
        ship.scries["settings/desk/talon"] = buildJsonObject {
            put("desk", buildJsonObject {
                put("ai-settings", buildJsonObject {
                    put("config", JsonPrimitive(olderEntry))
                    put("credentials", JsonPrimitive(olderKeys))
                })
            })
        }.toString()
        val here = FakeAiSettings(paidSettings())
        SettingsSyncImpl(db = db, aiSettings = here).apply { attach(ship.channel) }.bootstrap()
        val sent = ship.pokesTo("settings").map { it.json.jsonObject }.filter { it.toString().contains("ai-settings") }
        val upgraded = sent.mapNotNull { it["put-entry"]?.jsonObject }.single { it["entry-key"]!!.jsonPrimitive.content == "config" }
        assertEquals(3, Json.parseToJsonElement(upgraded["value"]!!.jsonPrimitive.content).jsonObject["schemaVersion"]!!.jsonPrimitive.content.toInt())
        val gone = sent.mapNotNull { it["del-entry"]?.jsonObject }.single()
        assertEquals("credentials", gone["entry-key"]!!.jsonPrimitive.content)
        assertTrue(sent.none { "sk-" in it.toString() }, "nothing of the keys goes back up")

        // Once the ship has this build's entry and no keys: nothing more to do.
        ship.scries["settings/desk/talon"] = buildJsonObject {
            put("desk", buildJsonObject {
                put("ai-settings", buildJsonObject { put("config", upgraded["value"]!!) })
            })
        }.toString()
        val before = ship.pokesTo("settings").size
        SettingsSyncImpl(db = db, aiSettings = here).apply { attach(ship.channel) }.bootstrap()
        assertTrue(ship.pokesTo("settings").drop(before).none { "ai-settings" in it.json.toString() })
    }

    /**
     * What a 1.8.29 device reads from this build's entry, by its own
     * applyAiEntry (master at 4c4d0125): keys, the provider and the
     * profile come only from fields this entry never carries, so its keys
     * and its profile stay as they are.
     */
    @Test
    fun `an older build reading this build's entry finds no key, provider or profile to take`() = live {
        SettingsSyncImpl(db = db, aiSettings = FakeAiSettings(paidSettings())).apply { attach(ship.channel) }.pushAiSettings()
        val value = ship.pokesTo("settings").single().json.jsonObject["put-entry"]!!.jsonObject["value"]!!.jsonPrimitive.content
        val sent = Json.parseToJsonElement(value).jsonObject
        listOf("provider", "apiKey", "braveApiKey", "sttApiKey", "sttApiKeyRemovedAtMs", "providerKeys", "profile", "privateApiKey", "privateBaseUrl", "model", "baseUrl")
            .forEach { assertFalse(it in sent, "$it would move an older build's keys") }
        // Its switches carry only names it knows.
        assertTrue(sent["switches"]!!.jsonObject.keys.all { it in setOf("CatchUp", "Assistant", "jev", "orrery") })
    }

    @Test
    fun `this build's entry turns switches back on too, and an older one with no instructions keeps ours`() = runBlocking {
        val off = paidSettings(assistant = false).let { c ->
            c.copy(catchMeUpEnabled = false, assistantPrompt = "Mine.", savedProfile = c.profile().copy(features = c.profile().features + (AiFeature.CatchUp to io.nisfeb.talon.ai.FeatureSetting(false))))
        }
        val here = FakeAiSettings(off)
        val sync = SettingsSyncImpl(db = db, aiSettings = here)
        sync.applyBucket(SettingsSyncImpl.BUCKET_AI_SETTINGS, bucket("config" to """{"schemaVersion":2}"""))
        assertEquals("Mine.", here.state.value.assistantPrompt, "absent is not empty")
        val on = """{"schemaVersion":3,"catchMeUpEnabled":true,"agentEnabled":true,"askUrbitEnabled":true,"switches":{"CatchUp":true,"Assistant":true}}"""
        sync.applyBucket(SettingsSyncImpl.BUCKET_AI_SETTINGS, bucket("config" to on))
        val c = here.state.value
        assertTrue(c.catchMeUpEnabled && c.assistantOn() && c.profile().isOn(AiFeature.CatchUp) && c.profile().isOn(AiFeature.Assistant))
        assertEquals("Mine.", c.assistantPrompt)
    }

    @Test
    fun `a ship that refuses to let the old keys go still finishes starting up`() = live {
        ship.refuse = { p -> if ("del-entry" in p.json.toString()) "no" else null }
        ship.scries["settings/desk/talon"] = buildJsonObject {
            put("desk", buildJsonObject {
                put("ai-settings", buildJsonObject {
                    put("config", JsonPrimitive(olderEntry))
                    put("credentials", JsonPrimitive(olderKeys))
                })
            })
        }.toString()
        SettingsSyncImpl(db = db, aiSettings = FakeAiSettings(paidSettings())).apply { attach(ship.channel) }.bootstrap()
        assertTrue(ship.subscribed.any { it.startsWith("settings") }, "it went on to watch for changes: ${ship.subscribed}")
    }
}
