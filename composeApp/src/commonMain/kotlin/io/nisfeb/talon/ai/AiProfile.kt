package io.nisfeb.talon.ai

import kotlinx.serialization.Serializable

/**
 * The AI settings as the owner thinks of them: the providers they have,
 * one default model, and for each feature whether it is on and which
 * model it uses. Every feature asks [AiProfile.resolve] what it runs on;
 * none reads provider settings directly.
 *
 * docs/superpowers/specs/2026-09-19-ai-settings-redesign.md is the plan.
 * Until the new screen saves one, the profile is derived from the
 * settings as they are ([migrateProfile]), so a feature resolves to
 * exactly what it used before.
 */
@Serializable
enum class ProviderKind(val label: String) {
    OpenRouter("OpenRouter"),
    Anthropic("Anthropic"),
    OpenAi("OpenAI"),
    OpenAiCompatible("A server of your own"),
    ThisDevice("On this device"),

    /**
     * Inference bought through the owner's own ship, from a vendor ship
     * they chose. Last in the list on purpose: an older build decoding
     * an unknown name drops the whole profile, so nothing that travels
     * ever carries this one (see [forSync]).
     */
    Armillary("Armillary"),
}

@Serializable
data class ModelInfo(
    val id: String,
    val name: String = id,
    /** Offered under zero data retention (OpenRouter's endpoints/zdr). */
    val zdr: Boolean = false,
    val tools: Boolean? = null,
    val contextLength: Int? = null,
    val speech: Boolean = false,
)

@Serializable
data class AiProvider(
    val id: String,
    val kind: ProviderKind,
    val label: String,
    val baseUrl: String? = null,
    val apiKey: String = "",
    val models: List<ModelInfo> = emptyList(),
    /** Jev is on this provider's ZDR list, as of the last fetch. */
    val offersJev: Boolean = false,
) {
    fun withCatalog(c: Catalog): AiProvider = copy(models = c.models, offersJev = c.jev)

    /** On this device, or on a server on this machine or the owner's own network. */
    val isPrivate: Boolean
        get() = kind == ProviderKind.ThisDevice || (kind == ProviderKind.OpenAiCompatible && isPrivateUrl(baseUrl))
}

/** A model on a provider. A blank [model] is the provider's own default. */
@Serializable
data class ModelRef(val provider: String, val model: String)

@Serializable
enum class AiFeature { CatchUp, Assistant, OrreryTriage, OrreryGenerator, OrreryBrief, Transcription }

/** A feature's switch and model; a null [model] follows the default model. */
@Serializable
data class FeatureSetting(val on: Boolean = false, val model: ModelRef? = null)

@Serializable
data class AiProfile(
    val providers: List<AiProvider> = emptyList(),
    val defaultModel: ModelRef? = null,
    val features: Map<AiFeature, FeatureSetting> = emptyMap(),
    /**
     * The Jev gate, status check and body picks, together. Null until
     * the owner flips it here: the decision model was set per install,
     * and one install's setting must not turn it off on another.
     */
    val jev: Boolean? = null,
) {
    fun provider(id: String): AiProvider? = providers.firstOrNull { it.id == id }

    /** What [f] runs on, whether or not it is on: its own model, else the default. */
    fun resolve(f: AiFeature): Resolved? {
        val ref = features[f]?.model ?: defaultModel ?: return null
        val p = provider(ref.provider) ?: return null
        // Armillary and a server of your own have no default model: the
        // list is all there is, so a blank ref takes the first of it
        // rather than reaching the OpenAI-shaped client with no model.
        val listOnly = p.kind == ProviderKind.Armillary || p.kind == ProviderKind.OpenAiCompatible
        val model = if (ref.model.isBlank() && listOnly) p.models.firstOrNull()?.id.orEmpty() else ref.model
        return Resolved(p, model)
    }

    fun isOn(f: AiFeature): Boolean = features[f]?.on == true

    /**
     * The provider Jev is reached through: an OpenRouter one with a key,
     * whose ZDR list names Jev, or whose models were never fetched.
     */
    fun jevProvider(): AiProvider? = providers.firstOrNull {
        it.kind == ProviderKind.OpenRouter && it.apiKey.isNotBlank() && (it.models.isEmpty() || it.offersJev)
    }
}

/** A feature's provider and model. */
data class Resolved(val provider: AiProvider, val model: String) {
    val private: Boolean get() = provider.isPrivate
}

/** What the settings alone do not say, for the migration: whether this install feeds orrery, and so on. */
data class ProfileInputs(
    val orreryFed: Boolean = false,
    val jevOn: Boolean? = null,
    /** The ship's generator as it is set: on, and the model it names. */
    val generatorOn: Boolean = false,
    val generatorUrl: String? = null,
    val generatorModel: String? = null,
)

const val MAIN_PROVIDER = "main"
const val PRIVATE_PROVIDER = "private"
const val DEVICE_PROVIDER = "device"
const val SPEECH_PROVIDER = "speech"

/** The one Armillary row: a device buys from one ship, its own. */
const val ARMILLARY_PROVIDER = "armillary"
const val WHISPER = "whisper-1"

private fun kindOf(p: AiSettings.Provider): ProviderKind = when (p) {
    AiSettings.Provider.OpenRouter -> ProviderKind.OpenRouter
    AiSettings.Provider.Anthropic -> ProviderKind.Anthropic
    AiSettings.Provider.OpenAi -> ProviderKind.OpenAi
    AiSettings.Provider.Custom -> ProviderKind.OpenAiCompatible
}

internal fun providerOf(k: ProviderKind): AiSettings.Provider? = when (k) {
    ProviderKind.OpenRouter -> AiSettings.Provider.OpenRouter
    ProviderKind.Anthropic -> AiSettings.Provider.Anthropic
    ProviderKind.OpenAi -> AiSettings.Provider.OpenAi
    ProviderKind.OpenAiCompatible -> AiSettings.Provider.Custom
    // Armillary answers the OpenAI shape, whether the base is the
    // vendor's proxy or the model provider a lease points straight at.
    ProviderKind.Armillary -> AiSettings.Provider.Custom
    ProviderKind.ThisDevice -> null
}

/**
 * Today's settings as a profile: the mapping table in the plan. The
 * frontier model is the main provider and the default model; the private
 * model is a provider of its own, or this device; triage reads with the
 * default model when the owner let the frontier model read messages, and
 * with the private one otherwise; a transcription key is a provider of
 * its own, and without one an OpenAI or custom chat key transcribes.
 */
fun migrateProfile(cfg: AiSettings.Config, inputs: ProfileInputs = ProfileInputs()): AiProfile {
    val providers = mutableListOf<AiProvider>()
    val hasMain = cfg.apiKey.isNotBlank() || (cfg.provider == AiSettings.Provider.Custom && !cfg.baseUrl.isNullOrBlank())
    if (hasMain) {
        providers += AiProvider(MAIN_PROVIDER, kindOf(cfg.provider), kindOf(cfg.provider).label, cfg.baseUrl, cfg.apiKey)
    }
    val privateUrl = cfg.privateBaseUrl?.takeIf { it.isNotBlank() }
    val privateRef = if (privateUrl != null) {
        val kind = if ("openrouter.ai" in privateUrl) ProviderKind.OpenRouter else ProviderKind.OpenAiCompatible
        providers += AiProvider(PRIVATE_PROVIDER, kind, "Private model", privateUrl, cfg.privateApiKey)
        ModelRef(PRIVATE_PROVIDER, cfg.privateModel.orEmpty())
    } else {
        ModelRef(DEVICE_PROVIDER, cfg.privateModel.orEmpty())
    }
    // This device is always a provider: every platform has a local rung.
    providers += AiProvider(DEVICE_PROVIDER, ProviderKind.ThisDevice, "On this device")
    val speech = if (cfg.sttApiKey.isNotBlank()) {
        providers += AiProvider(SPEECH_PROVIDER, ProviderKind.OpenAi, "OpenAI (transcription)", null, cfg.sttApiKey)
        ModelRef(SPEECH_PROVIDER, WHISPER)
    } else if (hasMain && (cfg.provider == AiSettings.Provider.OpenAi || cfg.provider == AiSettings.Provider.Custom)) {
        ModelRef(MAIN_PROVIDER, WHISPER)
    } else {
        null
    }
    val default = if (hasMain) ModelRef(MAIN_PROVIDER, cfg.model.orEmpty()) else null
    val generator = inputs.generatorUrl?.let { url ->
        providers.firstOrNull { p -> p.shipBase() == url.trim().trimEnd('/') || (p.kind == ProviderKind.OpenRouter && "openrouter.ai" in url) }
            ?.let { ModelRef(it.id, inputs.generatorModel.orEmpty()) }
    }
    return AiProfile(
        providers = providers,
        defaultModel = default,
        features = mapOf(
            AiFeature.CatchUp to FeatureSetting(cfg.catchMeUpEnabled),
            AiFeature.Assistant to FeatureSetting(cfg.assistantOn()),
            AiFeature.OrreryTriage to FeatureSetting(inputs.orreryFed, if (cfg.frontierReadsMessages && default != null) default else privateRef),
            AiFeature.OrreryGenerator to FeatureSetting(inputs.generatorOn, generator),
            // The brief was never a switch: every install feeding orrery
            // with a model sent it. On is what it was, on every device.
            AiFeature.OrreryBrief to FeatureSetting(true),
            AiFeature.Transcription to FeatureSetting(speech != null, speech),
        ),
        jev = inputs.jevOn,
    )
}

/** The profile these settings stand for: the one the owner saved, else the one today's fields make. */
fun AiSettings.Config.profile(): AiProfile = savedProfile ?: migrateProfile(this)

/**
 * Whether the owner switched [f] on. Before a profile is saved the old
 * settings decide, which for a switch the migration cannot see (the
 * brief, which followed Feed Orrery) is [before].
 */
fun AiSettings.Config.featureOn(f: AiFeature, before: Boolean): Boolean = savedProfile?.isOn(f) ?: before

/**
 * The OpenAI-shaped base the ship's generator can call on this
 * provider: none for Anthropic's own shape, this device, or a server
 * on this machine's loopback, which the ship cannot reach.
 */
fun AiProvider.shipBase(): String? = when (kind) {
    ProviderKind.OpenRouter -> ModelCatalog.OPENROUTER
    ProviderKind.OpenAi -> "https://api.openai.com/v1"
    ProviderKind.OpenAiCompatible -> baseUrl?.trim()?.trimEnd('/')?.removeSuffix("/chat/completions")
        ?.takeUnless { u -> u.substringAfter("://").substringBefore('/').substringBefore(':').let { it == "localhost" || it.startsWith("127.") } }
    // Whatever the ship handed us: OpenRouter's base under a lease, the
    // vendor's proxy otherwise. Both are OpenAI-shaped and both take
    // the key on the row, which is what the generator needs.
    ProviderKind.Armillary -> baseUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
    else -> null
}

/**
 * The settings a feature's chat client should see: its resolved provider
 * and model in the one slot the clients read. A feature with no cloud
 * model resolved keeps the settings as they are, so it fails the way it
 * did before rather than in a new way.
 */
fun AiSettings.Config.forFeature(f: AiFeature): AiSettings.Config {
    // Once a profile is saved it is the truth, and a feature it gives no
    // model gets no key: the old fields could still hold one from a
    // provider the owner took out, and sent it on every request.
    val r = profile().resolve(f) ?: return if (savedProfile != null) copy(apiKey = "") else this
    val provider = providerOf(r.provider.kind) ?: return this
    return copy(
        provider = provider,
        apiKey = r.provider.apiKey,
        model = r.model.ifBlank { null },
        baseUrl = r.provider.baseUrl,
        // OpenRouter says what a call cost only when asked, and so does
        // an Armillary base, which is OpenRouter under a lease and the
        // vendor's own proxy otherwise.
        usageInclude = r.provider.kind == ProviderKind.OpenRouter || r.provider.kind == ProviderKind.Armillary,
    )
}

/** Whether triage reads with a model off this device and the owner's own network. */
fun AiSettings.Config.triageInCloud(): Boolean = profile().resolve(AiFeature.OrreryTriage)?.private == false

/**
 * The private model triage falls back to: a server where the owner named
 * one, else whatever this device runs, as a slot the local ladder takes.
 */
fun AiSettings.Config.triagePrivateSlot(): AiSettings.Slot {
    val p = profile()
    // Triage's own model when it is a private server; else the private
    // provider, the fallback while triage reads in the cloud; else this device.
    val own = p.resolve(AiFeature.OrreryTriage)?.takeIf { it.private && it.provider.kind == ProviderKind.OpenAiCompatible }
        ?.let { AiSettings.Slot(null, it.provider.apiKey, it.model.ifBlank { null }, it.provider.baseUrl) }
    if (own != null) return own
    val fallback = p.provider(PRIVATE_PROVIDER)
    return if (fallback != null) AiSettings.Slot(null, fallback.apiKey, privateModel, fallback.baseUrl)
    else AiSettings.Slot(null, "", privateModel, null)
}

/**
 * Whether [url] is this machine or the owner's own network: loopback,
 * the private ranges, a .local name, or a tailnet (100.64.0.0/10,
 * *.ts.net). A model there reads nothing that leaves the owner's hands.
 */
fun isPrivateUrl(url: String?): Boolean {
    val host = url?.substringAfter("://", url)?.substringBefore('/')?.substringBefore(':')?.lowercase()?.trim('[', ']')
        ?: return false
    if (host.isEmpty()) return false
    if (host == "localhost" || host == "::1" || host.endsWith(".local") || host.endsWith(".ts.net") || host.endsWith(".lan")) return true
    val o = host.split('.').mapNotNull { it.toIntOrNull() }
    if (o.size != 4) return false
    return o[0] == 127 || o[0] == 10 || (o[0] == 192 && o[1] == 168) || (o[0] == 172 && o[1] in 16..31) ||
        (o[0] == 100 && o[1] in 64..127)
}

// ---- storage and sync ------------------------------------------------------

/**
 * The profile as it travels between devices: no keys, and no model
 * lists, which each device fetches.
 *
 * Armillary rows do not travel at all. The key is minted per ship and
 * per device, so another device's is no use here; and an older build
 * that cannot decode the kind drops the whole profile on arrival, which
 * a blob carrying none can never make it do.
 *
 * The model list and what it says about Jev are both this device's own
 * reading: sending the flag without the list told the device that had
 * fetched one that its provider no longer offers Jev.
 */
fun AiProfile.forSync(): AiProfile = copy(
    providers = providers
        .filterNot { it.kind == ProviderKind.Armillary }
        .map { it.copy(apiKey = "", models = emptyList(), offersJev = false) },
)

/**
 * Each provider's key, where it has one. Not Armillary's: it is minted
 * for this device, and goes nowhere else.
 */
fun AiProfile.keys(): Map<String, String> =
    providers.filter { it.apiKey.isNotBlank() && it.kind != ProviderKind.Armillary }.associate { it.id to it.apiKey }

/** Keys arriving for providers, by id. A blank never erases one. */
fun AiProfile.withKeys(keys: Map<String, String>, from: AiProfile? = null): AiProfile =
    copy(
        providers = providers.map { p ->
            // The same id means the same provider only where the kind
            // agrees: ids are shared constants, so an Anthropic key
            // could otherwise be sent to OpenRouter.
            val sameKind = from == null || from.provider(p.id)?.kind == p.kind
            keys[p.id]?.takeIf { it.isNotBlank() && sameKind }?.let { p.copy(apiKey = it) } ?: p
        },
    )

/**
 * A profile from elsewhere keeps this device's keys and model lists for
 * the providers they share: the same id and the same kind, so an
 * Anthropic key is never put on another device's OpenRouter provider.
 */
fun AiProfile.keepingLocal(local: AiProfile?): AiProfile {
    if (local == null) return this
    val arrived = providers.map { p ->
        val mine = local.provider(p.id)?.takeIf { it.kind == p.kind }
        p.copy(
            apiKey = p.apiKey.ifBlank { mine?.apiKey.orEmpty() },
            models = p.models.ifEmpty { mine?.models.orEmpty() },
            offersJev = p.offersJev || (p.models.isEmpty() && mine?.offersJev == true),
        )
    }
    // Nothing from elsewhere carries an Armillary row, so this device's
    // own is put back rather than dropped by a profile that arrives.
    val ours = local.providers.filter { it.kind == ProviderKind.Armillary && provider(it.id) == null }
    return copy(providers = arrived + ours)
}

/**
 * Whether [f] resolves to a model a chat client can call: one with a
 * key, or a server of the owner's own, which may want none.
 *
 * What the features gate on. The old check was whether the old key
 * field was set, which a local LM Studio or Ollama server never sets,
 * so those features stayed hidden for it, and which a removed key
 * went on satisfying. With no profile saved yet it answers as the old
 * check did, a keyless server of the owner's own aside.
 */
fun AiSettings.Config.hasModelFor(f: AiFeature): Boolean {
    val p = profile().resolve(f)?.provider ?: return false
    if (providerOf(p.kind) == null) return false
    return p.apiKey.isNotBlank() || (p.kind == ProviderKind.OpenAiCompatible && !p.baseUrl.isNullOrBlank())
}

/**
 * The settings once the owner saves [p] on this device, at [now]: the
 * profile, and the old fields derived from it.
 *
 * One place for all three platforms. A key that was on a provider and
 * is on none now was removed here, by taking the provider out or by
 * clearing the key: it is marked revoked, which takes it out of every
 * field that kept it, the transcription and private ones too, and
 * tells the peers. One the profile never held stays: that is the case
 * the "a blank never overwrites" rule in [legacyInto] was written for.
 */
fun AiSettings.Config.withProfile(p: AiProfile, now: Long): AiSettings.Config {
    val had = profile().keys().values.toSet()
    val has = p.keys().values.toSet()
    return p.legacyInto(this).copy(savedProfile = p).marking(gone = had - has, back = has - had, now = now)
}

/**
 * The old fields, derived from the profile, for the features that still
 * read them and for older installs, which know nothing else: the default
 * model is the frontier slot, triage's model the private one or the
 * frontier reading messages, the switches the old switches. A blank key
 * never overwrites a real one.
 */
fun AiProfile.legacyInto(cfg: AiSettings.Config): AiSettings.Config {
    // Armillary's key is this device's own, and these fields travel: an
    // Armillary default or triage leaves them as they were, and a key a
    // build before this copied into them is taken back out.
    val minted = providers.filter { it.kind == ProviderKind.Armillary && it.apiKey.isNotBlank() }.map { it.apiKey }
    var c = cfg.copy(
        apiKey = cfg.apiKey.takeUnless { it in minted }.orEmpty(),
        privateApiKey = cfg.privateApiKey.takeUnless { it in minted }.orEmpty(),
    )
    val def = defaultModel?.let { ref -> provider(ref.provider)?.let { it to ref } }?.takeIf { it.first.kind != ProviderKind.Armillary }
    def?.let { (p, ref) ->
        providerOf(p.kind)?.let { kind ->
            c = c.copy(provider = kind, apiKey = p.apiKey.ifBlank { c.apiKey }, model = ref.model.ifBlank { null }, baseUrl = p.baseUrl)
        }
    }
    resolve(AiFeature.OrreryTriage)?.takeIf { it.provider.kind != ProviderKind.Armillary }?.let { r ->
        val readsWithDefault = def != null && r.provider.id == def.first.id && r.provider.kind != ProviderKind.ThisDevice
        c = when {
            readsWithDefault -> c.copy(frontierReadsMessages = true)
            r.provider.kind == ProviderKind.ThisDevice -> c.copy(frontierReadsMessages = false, privateBaseUrl = null, privateModel = r.model.ifBlank { null })
            else -> c.copy(
                frontierReadsMessages = false,
                privateBaseUrl = r.provider.baseUrl,
                privateApiKey = r.provider.apiKey.ifBlank { c.privateApiKey },
                privateModel = r.model.ifBlank { null },
            )
        }
    }
    resolve(AiFeature.Transcription)?.takeIf { isOn(AiFeature.Transcription) }?.let { r ->
        if (r.provider.kind == ProviderKind.OpenAi && r.provider.id != def?.first?.id && r.provider.apiKey.isNotBlank()) {
            c = c.copy(sttApiKey = r.provider.apiKey)
        }
    }
    return c.copy(
        catchMeUpEnabled = isOn(AiFeature.CatchUp),
        agentEnabled = isOn(AiFeature.Assistant),
        askUrbitEnabled = isOn(AiFeature.Assistant),
    )
}

/**
 * What an older install's write means for the profile: it changed what
 * the old fields describe, the default model's provider, key and model,
 * and the catch-up and assistant switches, and nothing else it cannot
 * see.
 * ponytail: an old install's private-model or transcription edits are
 * not carried into a profile; they are rare, and the old fields keep them.
 */
fun AiProfile.withLegacy(cfg: AiSettings.Config): AiProfile {
    var providers = providers
    var def = defaultModel
    val dp = def?.let { provider(it.provider) }
    // Not an Armillary default: the old fields no longer describe it
    // ([legacyInto]), and an old install's key would become its key.
    if (dp != null && providerOf(dp.kind) != null && dp.kind != ProviderKind.Armillary) {
        providers = providers.map {
            if (it.id != dp.id) it else it.copy(
                kind = kindOf(cfg.provider),
                label = if (it.label == it.kind.label) kindOf(cfg.provider).label else it.label,
                apiKey = cfg.apiKey.ifBlank { it.apiKey },
                baseUrl = cfg.baseUrl,
            )
        }
        def = def.copy(model = cfg.model.orEmpty())
    } else if (dp == null && providers.isEmpty() && cfg.apiKey.isNotBlank()) {
        // Only where there is nothing here to speak of. A profile with
        // providers in it is one somebody curated, and an old install's
        // key used to put back the provider they had just deleted.
        providers = listOf(AiProvider(MAIN_PROVIDER, kindOf(cfg.provider), kindOf(cfg.provider).label, cfg.baseUrl, cfg.apiKey)) +
            providers.filterNot { it.id == MAIN_PROVIDER }
        def = ModelRef(MAIN_PROVIDER, cfg.model.orEmpty())
    }
    val f = features.toMutableMap()
    f[AiFeature.CatchUp] = (f[AiFeature.CatchUp] ?: FeatureSetting()).copy(on = cfg.catchMeUpEnabled)
    f[AiFeature.Assistant] = (f[AiFeature.Assistant] ?: FeatureSetting()).copy(on = cfg.assistantOn())
    return copy(providers = providers, defaultModel = def, features = f)
}

/** The switches that travel whatever the key sync says, as the old toggles did. */
// Transcription is not here: it is migrated from what the device
// itself can do (a speech key, or an OpenAI chat key), so syncing it
// let a phone with neither turn transcription off on the computer.
private val SYNCED_SWITCHES = listOf(AiFeature.CatchUp, AiFeature.Assistant, AiFeature.OrreryBrief)

/** The switches as the config entry carries them. Triage is Feed Orrery, per install; the generator is the ship's. */
fun AiProfile.switches(): kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.buildJsonObject {
    SYNCED_SWITCHES.forEach { f -> put(f.name, kotlinx.serialization.json.JsonPrimitive(isOn(f))) }
    jev?.let { put("jev", kotlinx.serialization.json.JsonPrimitive(it)) }
}

fun AiProfile.withSwitches(s: kotlinx.serialization.json.JsonObject): AiProfile = copy(
    features = features + SYNCED_SWITCHES.mapNotNull { f ->
        (s[f.name] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBooleanStrictOrNull()
            ?.let { on -> f to (features[f] ?: FeatureSetting()).copy(on = on) }
    },
    jev = (s["jev"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: jev,
)

/**
 * The profile after one AI settings entry arrives from the ship, [merged]
 * being the old fields as already applied. The terms are the old ones:
 * switches travel always, in the config entry; providers, models and
 * keys only in the credentials entry, and only where this device syncs
 * them. What arrives keeps this device's own keys and model lists, taken
 * from its old fields where it has saved no profile. An older install's
 * write changes what the old fields describe.
 */
fun profileAfterEntry(
    entry: kotlinx.serialization.json.JsonObject,
    current: AiSettings.Config,
    merged: AiSettings.Config,
): AiProfile? {
    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    val incoming = (entry["profile"] as? kotlinx.serialization.json.JsonObject)
        ?.takeIf { current.syncEnabled }
        ?.let { runCatching { json.decodeFromJsonElement(AiProfile.serializer(), it) }.getOrNull() }
    val switches = entry["switches"] as? kotlinx.serialization.json.JsonObject
    val base = current.savedProfile
    val fromNew = entry.containsKey("profile") || switches != null
    val fromOld = !fromNew && base != null &&
        (entry.containsKey("catchMeUpEnabled") || (entry.containsKey("apiKey") && !entry.containsKey("providerKeys")))
    val local = base ?: migrateProfile(current)
    var profile = when {
        // Only a device with keys writes the credentials entry, so the
        // switches in its profile can be older than the ones in config,
        // which every device writes. Its providers and models are news;
        // its switches are not, unless they came as switches.
        // Transcription is not news from anywhere: it is this device's
        // own (see SYNCED_SWITCHES), and the whole profile arriving took
        // it anyway, so a phone with no speech model turned it off here.
        incoming != null -> incoming.keepingLocal(local)
            .let { it.copy(features = it.features.filterKeys { f -> f != AiFeature.Transcription } + local.features.filterKeys { f -> f == AiFeature.Transcription }) }
            .let { if (switches != null) it else it.withSwitches(local.switches()).copy(jev = local.jev) }
        fromOld -> base!!.withLegacy(merged)
        else -> base
    }
    // Switches with no profile here yet make one from this device's own settings.
    if (switches != null) profile = (profile ?: migrateProfile(merged)).withSwitches(switches)
    profile ?: return null
    val keys = (entry["providerKeys"] as? kotlinx.serialization.json.JsonObject)
        ?.mapNotNull { (k, v) -> (v as? kotlinx.serialization.json.JsonPrimitive)?.content?.let { k to it } }?.toMap()
    // The keys are matched against the profile they came with, so a
    // shared id (they are constants) cannot put an Anthropic key on this
    // device's OpenRouter provider.
    return if (keys != null && current.syncEnabled) profile.withKeys(keys, from = incoming) else profile
}
