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
enum class ProviderKind { OpenRouter, Anthropic, OpenAi, OpenAiCompatible, ThisDevice }

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
    /** The Jev gate, status check and body picks, together. */
    val jev: Boolean = false,
) {
    fun provider(id: String): AiProvider? = providers.firstOrNull { it.id == id }

    /** What [f] runs on, whether or not it is on: its own model, else the default. */
    fun resolve(f: AiFeature): Resolved? {
        val ref = features[f]?.model ?: defaultModel ?: return null
        val p = provider(ref.provider) ?: return null
        return Resolved(p, ref.model)
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
    val jevOn: Boolean = false,
    /** The ship's generator as it is set: on, and the model it names. */
    val generatorOn: Boolean = false,
    val generatorUrl: String? = null,
    val generatorModel: String? = null,
)

const val MAIN_PROVIDER = "main"
const val PRIVATE_PROVIDER = "private"
const val DEVICE_PROVIDER = "device"
const val SPEECH_PROVIDER = "speech"
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
        providers += AiProvider(MAIN_PROVIDER, kindOf(cfg.provider), cfg.provider.label, cfg.baseUrl, cfg.apiKey)
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
        providers.firstOrNull { p -> p.kind == ProviderKind.OpenRouter && "openrouter.ai" in url }
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
            AiFeature.OrreryBrief to FeatureSetting(inputs.orreryFed),
            AiFeature.Transcription to FeatureSetting(speech != null, speech),
        ),
        jev = inputs.jevOn,
    )
}

/** The profile these settings stand for: the one the owner saved, else the one today's fields make. */
fun AiSettings.Config.profile(): AiProfile = savedProfile ?: migrateProfile(this)

/**
 * The settings a feature's chat client should see: its resolved provider
 * and model in the one slot the clients read. A feature with no cloud
 * model resolved keeps the settings as they are, so it fails the way it
 * did before rather than in a new way.
 */
fun AiSettings.Config.forFeature(f: AiFeature): AiSettings.Config {
    val r = profile().resolve(f) ?: return this
    val provider = providerOf(r.provider.kind) ?: return this
    return copy(provider = provider, apiKey = r.provider.apiKey, model = r.model.ifBlank { null }, baseUrl = r.provider.baseUrl)
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

/** The profile as it travels between devices: no keys, and no model lists, which each device fetches. */
fun AiProfile.forSync(): AiProfile = copy(providers = providers.map { it.copy(apiKey = "", models = emptyList()) })

/** Each provider's key, where it has one. */
fun AiProfile.keys(): Map<String, String> = providers.filter { it.apiKey.isNotBlank() }.associate { it.id to it.apiKey }

/** Keys arriving for providers, by id. A blank never erases one. */
fun AiProfile.withKeys(keys: Map<String, String>): AiProfile =
    copy(providers = providers.map { p -> keys[p.id]?.takeIf { it.isNotBlank() }?.let { p.copy(apiKey = it) } ?: p })

/** A profile from elsewhere keeps this device's keys and model lists for the providers they share. */
fun AiProfile.keepingLocal(local: AiProfile?): AiProfile {
    if (local == null) return this
    return copy(
        providers = providers.map { p ->
            val mine = local.provider(p.id)
            p.copy(
                apiKey = p.apiKey.ifBlank { mine?.apiKey.orEmpty() },
                models = p.models.ifEmpty { mine?.models.orEmpty() },
            )
        },
    )
}

/**
 * The old fields, derived from the profile, for the features that still
 * read them and for older installs, which know nothing else: the default
 * model is the frontier slot, triage's model the private one or the
 * frontier reading messages, the switches the old switches. A blank key
 * never overwrites a real one.
 */
fun AiProfile.legacyInto(cfg: AiSettings.Config): AiSettings.Config {
    var c = cfg
    val def = defaultModel?.let { ref -> provider(ref.provider)?.let { it to ref } }
    def?.let { (p, ref) ->
        providerOf(p.kind)?.let { kind ->
            c = c.copy(provider = kind, apiKey = p.apiKey.ifBlank { c.apiKey }, model = ref.model.ifBlank { null }, baseUrl = p.baseUrl)
        }
    }
    resolve(AiFeature.OrreryTriage)?.let { r ->
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
    if (dp != null && providerOf(dp.kind) != null) {
        providers = providers.map {
            if (it.id == dp.id) it.copy(kind = kindOfLegacy(cfg.provider), apiKey = cfg.apiKey.ifBlank { it.apiKey }, baseUrl = cfg.baseUrl) else it
        }
        def = def.copy(model = cfg.model.orEmpty())
    } else if (dp == null && cfg.apiKey.isNotBlank()) {
        providers = listOf(AiProvider(MAIN_PROVIDER, kindOfLegacy(cfg.provider), cfg.provider.label, cfg.baseUrl, cfg.apiKey)) +
            providers.filterNot { it.id == MAIN_PROVIDER }
        def = ModelRef(MAIN_PROVIDER, cfg.model.orEmpty())
    }
    val f = features.toMutableMap()
    f[AiFeature.CatchUp] = (f[AiFeature.CatchUp] ?: FeatureSetting()).copy(on = cfg.catchMeUpEnabled)
    f[AiFeature.Assistant] = (f[AiFeature.Assistant] ?: FeatureSetting()).copy(on = cfg.assistantOn())
    return copy(providers = providers, defaultModel = def, features = f)
}

private fun kindOfLegacy(p: AiSettings.Provider): ProviderKind = when (p) {
    AiSettings.Provider.OpenRouter -> ProviderKind.OpenRouter
    AiSettings.Provider.Anthropic -> ProviderKind.Anthropic
    AiSettings.Provider.OpenAi -> ProviderKind.OpenAi
    AiSettings.Provider.Custom -> ProviderKind.OpenAiCompatible
}

/**
 * The profile after one AI settings entry arrives from the ship, [merged]
 * being the old fields as already applied. A new install's entry carries
 * the profile, which keeps this device's keys and model lists; an old
 * install's changes what the old fields describe; provider keys come
 * only with the credentials entry, and only when this device syncs them.
 */
fun profileAfterEntry(
    entry: kotlinx.serialization.json.JsonObject,
    current: AiSettings.Config,
    merged: AiSettings.Config,
): AiProfile? {
    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    val incoming = (entry["profile"] as? kotlinx.serialization.json.JsonObject)
        ?.let { runCatching { json.decodeFromJsonElement(AiProfile.serializer(), it) }.getOrNull() }
    val base = current.savedProfile
    val fromOld = incoming == null && base != null &&
        (entry.containsKey("catchMeUpEnabled") || (entry.containsKey("apiKey") && !entry.containsKey("providerKeys")))
    val profile = when {
        incoming != null -> incoming.keepingLocal(base)
        fromOld -> base!!.withLegacy(merged)
        else -> base
    } ?: return null
    val keys = (entry["providerKeys"] as? kotlinx.serialization.json.JsonObject)
        ?.mapNotNull { (k, v) -> (v as? kotlinx.serialization.json.JsonPrimitive)?.content?.let { k to it } }?.toMap()
    return if (keys != null && current.syncEnabled) profile.withKeys(keys) else profile
}
