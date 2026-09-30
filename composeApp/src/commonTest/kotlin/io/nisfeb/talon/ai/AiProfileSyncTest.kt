package io.nisfeb.talon.ai

import io.nisfeb.talon.orrery.DecideSettings
import io.nisfeb.talon.orrery.under
import kotlinx.serialization.Serializable
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

    @Test
    fun `what one device read about itself stays on it`() {
        val p = migrateProfile(base).let {
            it.copy(providers = it.providers.map { pr -> pr.copy(models = listOf(ModelInfo("m1")), offersJev = true) })
        }
        // The Jev flag is read off the model list, so it travels with it:
        // sent alone it told a device that had fetched a list that its own
        // provider no longer offers Jev.
        assertTrue(p.forSync().providers.none { it.offersJev }, "the flag stays with the list it was read from")
        val here = p.keepingLocal(p)
        assertTrue(here.providers.all { it.offersJev }, "and the device that has the list keeps its own answer")

        // Transcription is migrated from what this device can do, so a
        // phone with no speech key must not turn it off on the computer.
        assertFalse(AiFeature.Transcription.name in p.switches().keys, "transcription is the device's own")
        val off = p.withSwitches(buildJsonObject { put(AiFeature.Transcription.name, false) })
        assertEquals(p.isOn(AiFeature.Transcription), off.isOn(AiFeature.Transcription))
    }

    @Test
    fun `a key lands only on a provider of the same kind`() {
        val mine = migrateProfile(base.copy(provider = AiSettings.Provider.Anthropic, apiKey = "sk-ant"))
        val theirs = migrateProfile(base) // the same id, OpenRouter's kind
        assertEquals(
            "sk-ant",
            mine.withKeys(mapOf(MAIN_PROVIDER to "sk-or"), from = theirs).provider(MAIN_PROVIDER)?.apiKey,
            "an OpenRouter key does not land on an Anthropic provider that shares the id",
        )
        assertEquals("sk-or", mine.withKeys(mapOf(MAIN_PROVIDER to "sk-or"), from = mine).provider(MAIN_PROVIDER)?.apiKey)
    }

    @Test
    fun `an old install's write cannot put back a provider that was deleted`() {
        val kept = migrateProfile(base).let { p ->
            p.copy(providers = p.providers.filterNot { it.id == MAIN_PROVIDER })
        }
        assertNull(kept.withLegacy(base).provider(MAIN_PROVIDER), "the one they deleted stays deleted")
        // With nothing here at all, the old fields are still all there is.
        val empty = kept.copy(providers = emptyList(), defaultModel = null)
        assertEquals("sk-or", empty.withLegacy(base).provider(MAIN_PROVIDER)?.apiKey)
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
        // Its switches as switches, which is how every install that sends
        // a profile sends them: the config entry beside the credentials.
        val e = entry(
            "schemaVersion" to "2", "apiKey" to "sk-or",
            "profile" to Json.encodeToJsonElement(AiProfile.serializer(), theirs) as JsonObject,
            "switches" to theirs.switches(),
        )
        val got = profileAfterEntry(e, base.copy(savedProfile = mine), base)!!
        assertEquals(true, got.jev)
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
        assertEquals(true, prefs.jev, "a switch it cannot see is left as it was")
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
        assertEquals(true, kept.jev)
        assertEquals(mine.keys(), kept.keys())
    }

    @Test
    fun `once a profile is saved its switch decides jev, before it the old ones do`() {
        val ds = DecideSettings(on = true, gate = false, relevance = false)
        assertEquals(ds, ds.under(base))
        val jevOn = base.copy(savedProfile = migrateProfile(base).copy(jev = true))
        assertEquals(ds.copy(gate = true, relevance = true), ds.under(jevOn), "one switch, all three")
        assertEquals(ds, ds.under(base.copy(savedProfile = migrateProfile(base))), "never flipped: this install's own")
        assertFalse(ds.under(base.copy(savedProfile = migrateProfile(base).copy(jev = false))).on)
    }

    // ── Armillary ──────────────────────────────────────────────────
    //
    // The row is per ship and per device: each device asks its own ship
    // for its own key. It must never travel, and an older build that
    // cannot decode the kind must never meet it, because decoding a
    // profile with an unknown enum value drops the whole profile.

    private fun armillary() = AiProvider(
        ARMILLARY_PROVIDER, ProviderKind.Armillary, "Armillary",
        baseUrl = "https://wex.example/apps/armillary/v1", apiKey = "k1.secret",
        models = listOf(ModelInfo("stub/alpha", "stub/alpha", zdr = true), ModelInfo("stub/beta")),
    )

    @Serializable
    private enum class OldKind { OpenRouter, Anthropic, OpenAi, OpenAiCompatible, ThisDevice }

    @Serializable
    private data class OldProvider(val id: String, val kind: OldKind, val label: String)

    @Serializable
    private data class OldProfile(val providers: List<OldProvider> = emptyList())

    @Test
    fun `an armillary row never travels, so an older build can still read the blob`() {
        val mine = migrateProfile(base).let { it.copy(providers = it.providers + armillary()) }
        val sync = mine.forSync()
        assertTrue(sync.providers.none { it.kind == ProviderKind.Armillary }, "nothing armillary goes up")
        // The proof that matters: a build whose ProviderKind predates
        // the value decodes the blob rather than dropping the profile.
        val blob = Json.encodeToString(AiProfile.serializer(), sync)
        val old = Json { ignoreUnknownKeys = true }.decodeFromString(OldProfile.serializer(), blob)
        assertEquals(sync.providers.map { it.id }, old.providers.map { it.id })
        // And this device's own row survives a profile arriving without one.
        val kept = sync.copy(jev = true).keepingLocal(mine)
        assertEquals("k1.secret", kept.provider(ARMILLARY_PROVIDER)!!.apiKey)
        assertEquals(mine.provider(ARMILLARY_PROVIDER)!!.models, kept.provider(ARMILLARY_PROVIDER)!!.models)
    }

    @Test
    fun `an armillary ref with no model resolves to the first the ship listed`() {
        val p = AiProfile(
            providers = listOf(armillary()),
            defaultModel = ModelRef(ARMILLARY_PROVIDER, ""),
            features = mapOf(AiFeature.CatchUp to FeatureSetting(true)),
        )
        val r = p.resolve(AiFeature.CatchUp)!!
        assertEquals("stub/alpha", r.model, "never blank: the OpenAI-shaped client refuses a call with no model")
        // Which is what the clients are handed, along with the ask to report the cost.
        val cfg = base.copy(savedProfile = p).forFeature(AiFeature.CatchUp)
        assertEquals(AiSettings.Provider.Custom, cfg.provider)
        assertEquals("stub/alpha", cfg.model)
        assertEquals("https://wex.example/apps/armillary/v1", cfg.baseUrl)
        assertEquals("k1.secret", cfg.apiKey)
        assertTrue(cfg.usageInclude)
        assertTrue(base.forFeature(AiFeature.CatchUp).usageInclude, "OpenRouter, as it always did")
        val anthropic = base.copy(provider = AiSettings.Provider.Anthropic, apiKey = "sk-ant")
        assertFalse(anthropic.forFeature(AiFeature.CatchUp).usageInclude, "nobody else is asked to report a cost")
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

    private fun profileEntry(p: AiProfile) = entry("schemaVersion" to "2", "profile" to Json.encodeToJsonElement(AiProfile.serializer(), p.forSync()) as JsonObject)

    @Test
    fun `a device that saved nothing yet keeps its own keys when a profile arrives, for providers of the same kind`() {
        val desktop = migrateProfile(base) // OpenRouter main, the private server, this device, OpenAI speech
        val phone = base.copy(provider = AiSettings.Provider.Anthropic, apiKey = "sk-ant")
        val got = profileAfterEntry(profileEntry(desktop), phone, phone)!!
        assertEquals("", got.provider(MAIN_PROVIDER)!!.apiKey, "an Anthropic key is not put on an OpenRouter provider")
        assertEquals("sk-whisper", got.provider(SPEECH_PROVIDER)!!.apiKey)
        assertEquals("sk-or", profileAfterEntry(profileEntry(desktop), base, base)!!.provider(MAIN_PROVIDER)!!.apiKey)
        // The same rule where the store applies what arrived.
        val applied = base.copy(savedProfile = desktop.forSync()).keepingCredentials(base).savedProfile!!
        assertEquals("sk-or", applied.provider(MAIN_PROVIDER)!!.apiKey)
    }

    @Test
    fun `switches travel whatever the key sync says, providers and models only with it`() {
        val theirs = migrateProfile(base).let { it.copy(jev = true, features = it.features + (AiFeature.CatchUp to FeatureSetting(false))) }
        val offHere = base.copy(syncEnabled = false)
        assertNull(profileAfterEntry(profileEntry(theirs), offHere, offHere), "no key sync, no providers from elsewhere")
        // Switches arriving where no profile is saved make one from this device's own settings.
        val phone = base.copy(provider = AiSettings.Provider.Anthropic, apiKey = "sk-ant", syncEnabled = false)
        val got = profileAfterEntry(entry("schemaVersion" to "2", "switches" to theirs.switches()), phone, phone)!!
        assertEquals(true, got.jev)
        assertFalse(got.isOn(AiFeature.CatchUp))
        assertEquals(ProviderKind.Anthropic, got.provider(MAIN_PROVIDER)!!.kind)
        assertEquals("sk-ant", got.provider(MAIN_PROVIDER)!!.apiKey)
        // A switch never flipped does not travel, so it cannot turn another install's off.
        assertFalse(migrateProfile(base).switches().containsKey("jev"))
    }

    // Orrery's switch is the privacy promise: off on one device is off on
    // all of them, and nothing that did not mean to can turn it back on.
    @Test
    fun `orrery's switch reaches every device, and only a switch moves it`() {
        val on = migrateProfile(base).copy(orrery = true)
        val here = base.copy(savedProfile = on)
        // Turned off on another device: it arrives as a switch.
        val off = profileAfterEntry(entry("schemaVersion" to "2", "switches" to on.copy(orrery = false).switches()), here, here)!!
        assertEquals(false, off.orrery)
        assertFalse(base.copy(savedProfile = off).orreryOn())
        // A credentials entry from a device that had it on, written before
        // the switch: the profile inside is older than the switch here.
        val stale = entry("schemaVersion" to "2", "apiKey" to "sk-or", "profile" to Json.encodeToJsonElement(AiProfile.serializer(), on.forSync()) as JsonObject)
        assertEquals(false, profileAfterEntry(stale, base.copy(savedProfile = off), base)!!.orrery, "still off")
        // Never set is not off: it does not travel, so a device that never
        // touched it cannot turn another's off.
        assertFalse(migrateProfile(base).switches().containsKey("orrery"))
        assertEquals(true, profileAfterEntry(entry("schemaVersion" to "2", "switches" to migrateProfile(base).switches()), here, here)!!.orrery)
    }

    @Test
    fun `orrery is on only when turned on`() {
        assertFalse(base.orreryOn(), "never set")
        assertFalse(base.copy(savedProfile = migrateProfile(base).copy(orrery = false)).orreryOn())
        assertTrue(base.copy(savedProfile = migrateProfile(base).copy(orrery = true)).orreryOn())
    }

    // Only a device with keys rewrites the credentials entry, so the
    // switches inside its profile can be older than the ones in config,
    // which every device writes. Catch-up turned off on a phone came back
    // on everywhere on the next connect.
    @Test
    fun `an older credentials entry does not turn switches back`() {
        val here = migrateProfile(base).let { it.copy(jev = false, features = it.features + (AiFeature.CatchUp to FeatureSetting(false))) }
        val stale = migrateProfile(base).copy(jev = true).forSync()
        val e = entry("schemaVersion" to "2", "apiKey" to "sk-or", "profile" to Json.encodeToJsonElement(AiProfile.serializer(), stale) as JsonObject)
        val got = profileAfterEntry(e, base.copy(savedProfile = here), base)!!
        assertFalse(got.isOn(AiFeature.CatchUp), "turned off here, and still off")
        assertEquals(false, got.jev)
    }

    // A provider taken out because its key leaked. The key also sat in
    // the old fields, which every feature fell back to and every device
    // pushed, so taking the provider out stopped nothing.
    @Test
    fun `a removed provider's key goes, and says so`() {
        // Only the main key, so nothing else can be why there is
        // something to push.
        val only = AiSettings.Config(provider = AiSettings.Provider.OpenRouter, apiKey = "sk-or", model = "m")
        val saved = migrateProfile(only)
        val had = only.copy(savedProfile = saved)
        val without = saved.copy(providers = saved.providers.filter { it.id != MAIN_PROVIDER }, defaultModel = null)
        val after = had.withProfile(without, now = 1_000L)
        assertEquals("", after.apiKey, "not kept in the old fields")
        assertTrue(after.isRevoked("sk-or"), "and marked, so the peers hear of it")
        assertFalse(after.revokedKeys.keys.any { "sk-or" in it }, "by a fingerprint, never the key")
        assertTrue(after.hasCredentials(), "the mark is a thing to push, though no key is left")
        assertFalse(only.copy(apiKey = "").hasCredentials(), "where nothing was, there is nothing")
        // Nothing resolves now, so no feature is handed a key at all.
        assertEquals("", after.forFeature(AiFeature.CatchUp).apiKey)
        // A key the profile never held is not a removal: it stays.
        val kept = only.copy(savedProfile = without).withProfile(without, now = 2_000L)
        assertEquals("sk-or", kept.apiKey)
        assertTrue(kept.revokedKeys.isEmpty())
    }

    // A blank never replaces a key, so a removal reached no other device
    // and the first one still holding the key brought it back to all.
    @Test
    fun `a removed key is refused from wherever it comes back`() {
        val saved = migrateProfile(base)
        val here = base.copy(savedProfile = saved)
        val removedHere = here.withProfile(saved.copy(providers = saved.providers.filter { it.id != MAIN_PROVIDER }, defaultModel = null), now = 1_000L)
        // A peer that has not heard of it pushes the key again.
        val peer = here
        assertEquals("", peer.keepingCredentials(removedHere).apiKey, "refused on arrival")
        // And the peer, told, lets go of it in every field.
        val peerAfter = removedHere.keepingCredentials(peer)
        assertEquals("", peerAfter.apiKey)
        assertTrue(peerAfter.savedProfile!!.keys().values.none { it == "sk-or" })
        // Typed back in on purpose, later: it stays, here and on the peer.
        val back = removedHere.withProfile(saved, now = 2_000L)
        assertEquals("sk-or", back.apiKey)
        assertFalse(back.isRevoked("sk-or"))
        assertEquals("sk-or", back.keepingCredentials(peerAfter).apiKey, "the later word wins")
    }

    // Clearing a key without taking the provider out never reached the
    // peers; the transcription and private keys were never cleared.
    @Test
    fun `a cleared key and a removed speech provider go too`() {
        val saved = migrateProfile(base.copy(privateApiKey = "sk-private"))
        val here = base.copy(privateApiKey = "sk-private", savedProfile = saved)
        val cleared = here.withProfile(saved.copy(providers = saved.providers.map { if (it.id == MAIN_PROVIDER) it.copy(apiKey = "") else it }), now = 1_000L)
        assertTrue(cleared.isRevoked("sk-or"))
        assertEquals("", cleared.apiKey)
        val noSpeech = here.withProfile(saved.copy(providers = saved.providers.filter { it.id != SPEECH_PROVIDER && it.id != PRIVATE_PROVIDER }), now = 3_000L)
        assertEquals("", noSpeech.sttApiKey)
        assertEquals(3_000L, noSpeech.sttApiKeyRemovedAtMs, "stamped as older installs read it")
        assertEquals("", noSpeech.privateApiKey)
    }

    // Armillary's key is minted for one device. Made the default, it was
    // copied into the old fields, which travel to the ship and every peer.
    @Test
    fun `an Armillary default keeps its key off the old fields`() {
        val minted = AiProvider(ARMILLARY_PROVIDER, ProviderKind.Armillary, "Armillary", baseUrl = "https://openrouter.ai/api/v1", apiKey = "sk-minted")
        val p = migrateProfile(base).let { it.copy(providers = it.providers + minted, defaultModel = ModelRef(ARMILLARY_PROVIDER, "")) }
        // As a build before this left it: the minted key in the main field.
        val after = base.copy(apiKey = "sk-minted", savedProfile = p).withProfile(p, now = 1_000L)
        assertEquals("", after.apiKey, "taken back out where an older build put it")
        assertFalse(after.isRevoked("sk-minted"), "and not revoked: it still works here")
        assertTrue("sk-minted" !in p.keys().values)
    }

    // Transcription is this device's own, but the whole profile arriving
    // carried it, so a phone with no speech model turned it off here.
    @Test
    fun `transcription stays as this device has it`() {
        val here = migrateProfile(base)
        assertTrue(here.isOn(AiFeature.Transcription))
        val phone = here.copy(features = here.features + (AiFeature.Transcription to FeatureSetting(false))).forSync()
        val profile = Json.encodeToJsonElement(AiProfile.serializer(), phone) as JsonObject
        for (e in listOf(
            entry("schemaVersion" to "2", "apiKey" to "sk-or", "profile" to profile),
            entry("schemaVersion" to "2", "apiKey" to "sk-or", "profile" to profile, "switches" to here.switches()),
        )) {
            assertTrue(profileAfterEntry(e, base.copy(savedProfile = here), base)!!.isOn(AiFeature.Transcription))
        }
    }

    // Typed on a device the revocation had not reached, a key had no mark
    // of its own, and the older revocation took it out when it arrived.
    @Test
    fun `a key typed later wins, even where the revocation had not arrived`() {
        val saved = migrateProfile(base)
        val noMain = saved.copy(providers = saved.providers.filter { it.id != MAIN_PROVIDER }, defaultModel = null)
        val phone = base.copy(savedProfile = saved).withProfile(noMain, now = 1_000L)
        // The desktop never heard; it had the provider out already, and the owner puts it back.
        val desktop = base.copy(apiKey = "", savedProfile = noMain).withProfile(saved, now = 2_000L)
        assertEquals("sk-or", phone.keepingCredentials(desktop).apiKey, "the phone takes the desktop's later word")
        assertEquals("sk-or", desktop.keepingCredentials(phone).apiKey, "and the desktop keeps what was typed")
    }

    @Test
    fun `a profile saved here is stamped, never sends the stamp, and loses it to one that arrives`() {
        val cfg = AiSettings.Config(provider = AiSettings.Provider.OpenAi, apiKey = "", model = null, syncEnabled = true)
        val saved = cfg.withProfile(AiProfile(), now = 7_000)
        assertEquals(7_000L, saved.savedProfile?.savedHereAtMs)
        assertTrue(saved.hasCredentials(), "an emptied profile saved here has something to say")
        assertEquals(0L, saved.savedProfile!!.forSync().savedHereAtMs)
        val entry = kotlinx.serialization.json.buildJsonObject {
            put("profile", kotlinx.serialization.json.Json.encodeToJsonElement(AiProfile.serializer(), AiProfile().forSync()))
        }
        val arrived = profileAfterEntry(entry, saved, saved)
        assertEquals(0L, arrived?.savedHereAtMs, "the ship's copy is not this device's saving")
        assertFalse(saved.copy(savedProfile = arrived).hasCredentials())
    }
}
