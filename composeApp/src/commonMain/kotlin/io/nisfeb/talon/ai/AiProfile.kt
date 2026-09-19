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
) {
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

    /** The provider Jev is reached through: an OpenRouter one with a key. */
    fun jevProvider(): AiProvider? = providers.firstOrNull { it.kind == ProviderKind.OpenRouter && it.apiKey.isNotBlank() }
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

/** The profile these settings stand for. */
fun AiSettings.Config.profile(): AiProfile = migrateProfile(this)

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
