package io.nisfeb.talon.ai

import io.nisfeb.talon.orrery.DecideSettings
import io.nisfeb.talon.orrery.under
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The profile stored and synced: the old fields kept derived from it for
 * older installs, and the rules that have cost keys before (a blank never
 * erases one, absence never wins) held for the profile too.
 */
class AiProfileSyncTest {
    private val base = AiSettings.Config(
        provider = AiSettings.Provider.OpenRouter, apiKey = "sk-or", model = "anthropic/claude-opus-4.8",
        privateBaseUrl = "http://127.0.0.1:1234/v1", privateApiKey = "", privateModel = "qwen3-8b",
        sttApiKey = "sk-whisper", catchMeUpEnabled = true, agentEnabled = true, askUrbitEnabled = true,
    )

    private fun legacy(c: AiSettings.Config) = listOf(
        c.provider, c.apiKey, c.model, c.baseUrl, c.privateBaseUrl, c.privateModel, c.frontierReadsMessages,
        c.catchMeUpEnabled, c.agentEnabled, c.sttApiKey,
    )

    @Test
    fun `the old fields a profile derives are the ones it was made from`() {
        for (reads in listOf(false, true)) for (catchUp in listOf(false, true)) {
            val c = base.copy(frontierReadsMessages = reads, catchMeUpEnabled = catchUp)
            assertEquals(legacy(c), legacy(migrateProfile(c).legacyInto(c)), "reads=$reads catchUp=$catchUp")
        }
        // A profile saved on this device is what every feature reads.
        val saved = migrateProfile(base).copy(jev = true)
        assertEquals(saved, base.copy(savedProfile = saved).profile())
    }

    @Test
    fun `a profile travels without keys or model lists`() {
        val p = migrateProfile(base).let { it.copy(providers = it.providers.map { pr -> pr.copy(models = listOf(ModelInfo("m1"))) }) }
        val sync = p.forSync()
        assertTrue(sync.providers.all { it.apiKey.isBlank() && it.models.isEmpty() })
        assertEquals(mapOf(MAIN_PROVIDER to "sk-or", SPEECH_PROVIDER to "sk-whisper"), p.keys())
        assertTrue(base.copy(apiKey = "", sttApiKey = "", privateBaseUrl = null, savedProfile = p).hasCredentials(), "profile keys are credentials")
    }

    private fun entry(vararg kv: Pair<String, Any>) = buildJsonObject {
        kv.forEach { (k, v) ->
            when (v) {
                is String -> put(k, v)
                is Boolean -> put(k, v)
                is JsonObject -> put(k, v)
                else -> error("unsupported")
            }
        }
    }

    @Test
    fun `a new install's profile arrives and keeps this device's keys and models`() {
        val mine = migrateProfile(base).let { it.copy(providers = it.providers.map { pr -> pr.copy(models = listOf(ModelInfo("m1"))) }) }
        val theirs = mine.copy(jev = true, features = mine.features + (AiFeature.CatchUp to FeatureSetting(false))).forSync()
        val e = entry("schemaVersion" to "2", "catchMeUpEnabled" to false, "profile" to Json.encodeToJsonElement(AiProfile.serializer(), theirs) as JsonObject)
        val got = profileAfterEntry(e, base.copy(savedProfile = mine), base)!!
        assertTrue(got.jev)
        assertFalse(got.isOn(AiFeature.CatchUp))
        assertEquals("sk-or", got.provider(MAIN_PROVIDER)!!.apiKey, "no key arrived, so this device's stays")
        assertEquals(listOf(ModelInfo("m1")), got.provider(MAIN_PROVIDER)!!.models)
    }

    @Test
    fun `an old install's write changes what its fields describe and nothing else`() {
        val mine = migrateProfile(base).copy(jev = true)
        val current = base.copy(savedProfile = mine)
        // Its preferences: catch-up off.
        val prefs = profileAfterEntry(entry("schemaVersion" to "2", "catchMeUpEnabled" to false), current, current.copy(catchMeUpEnabled = false))!!
        assertFalse(prefs.isOn(AiFeature.CatchUp))
        assertTrue(prefs.jev, "a switch it cannot see is left as it was")
        // Its credentials: a new key and model on the frontier provider.
        val creds = profileAfterEntry(
            entry("schemaVersion" to "2", "provider" to "OpenRouter", "apiKey" to "sk-new", "model" to "openai/gpt-5"),
            current, current.copy(apiKey = "sk-new", model = "openai/gpt-5"),
        )!!
        assertEquals("sk-new", creds.provider(MAIN_PROVIDER)!!.apiKey)
        assertEquals(ModelRef(MAIN_PROVIDER, "openai/gpt-5"), creds.defaultModel)
        assertEquals(mine.providers.map { it.id }, creds.providers.map { it.id })
    }

    @Test
    fun `provider keys arrive only where this device syncs keys`() {
        val mine = migrateProfile(base)
        val keys = buildJsonObject { put(MAIN_PROVIDER, "sk-other"); put(SPEECH_PROVIDER, "") }
        val e = entry("schemaVersion" to "2", "providerKeys" to keys)
        val on = profileAfterEntry(e, base.copy(savedProfile = mine, syncEnabled = true), base)!!
        assertEquals("sk-other", on.provider(MAIN_PROVIDER)!!.apiKey)
        assertEquals("sk-whisper", on.provider(SPEECH_PROVIDER)!!.apiKey, "a blank never erases a key")
        val off = profileAfterEntry(e, base.copy(savedProfile = mine, syncEnabled = false), base)!!
        assertEquals("sk-or", off.provider(MAIN_PROVIDER)!!.apiKey)
        assertNull(profileAfterEntry(e, base, base), "no profile saved, none made")
    }

    @Test
    fun `arriving settings never drop this device's profile or its keys`() {
        val mine = migrateProfile(base)
        val local = base.copy(savedProfile = mine)
        assertEquals(mine, base.copy(savedProfile = null).keepingCredentials(local).savedProfile, "an old install's config keeps ours")
        val blankKeys = mine.forSync().copy(jev = true)
        val kept = base.copy(savedProfile = blankKeys).keepingCredentials(local).savedProfile!!
        assertTrue(kept.jev)
        assertEquals(mine.keys(), kept.keys())
    }

    @Test
    fun `once a profile is saved its switches decide jev and the brief, before it the old ones do`() {
        val ds = DecideSettings(on = true, gate = false, relevance = false)
        assertEquals(ds, ds.under(base))
        val jevOn = base.copy(savedProfile = migrateProfile(base).copy(jev = true))
        assertEquals(ds.copy(gate = true, relevance = true), ds.under(jevOn), "one switch, all three")
        assertFalse(ds.under(base.copy(savedProfile = migrateProfile(base))).on)
        assertTrue(base.featureOn(AiFeature.OrreryBrief, before = true))
        assertFalse(jevOn.featureOn(AiFeature.OrreryBrief, before = true))
    }

    @Test
    fun `the ship's generator is offered what the ship can reach`() {
        fun server(url: String) = AiProvider("s", ProviderKind.OpenAiCompatible, "s", url).shipBase()
        assertNull(server("http://localhost:1234/v1"))
        assertNull(server("http://127.0.0.1:1234/v1"))
        assertEquals("http://192.168.1.5:1234/v1", server("http://192.168.1.5:1234/v1/chat/completions"))
        assertEquals("https://openrouter.ai/api/v1", AiProvider("o", ProviderKind.OpenRouter, "o").shipBase())
        assertNull(AiProvider("a", ProviderKind.Anthropic, "a").shipBase())
    }
}
