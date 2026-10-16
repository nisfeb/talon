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
    /**
     * Armillary only: the model the vendor runs each feature on, by the
     * vendor's feature name (`catch_up`, `assistant`, `decision`), with
     * `default` for a feature it names none for. Empty until it says.
     */
    val suggested: Map<String, String> = emptyMap(),
    /** Armillary only: the tier the vendor put each feature on (`zdr`, `frontier`, ...), by its name for the feature. */
    val suggestedTiers: Map<String, String> = emptyMap(),
    /** Armillary only: the vendor's Brave search, its base and the key this ship searches with. */
    val searchUrl: String? = null,
    val searchKey: String = "",
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
// OrreryBrief does nothing now that the ship writes the brief; it stays
// so a profile saved with it, here or by an older install, still reads.
// Dictation is per device and never travels ([forSync]): an older
// install cannot read a feature it does not know, and drops the profile.
enum class AiFeature { CatchUp, Assistant, OrreryTriage, OrreryGenerator, OrreryBrief, Transcription, Dictation }

/**
 * A feature's switch and model; a null [model] follows the default model.
 * [own] is a model the owner picked by hand, which an Armillary vendor's
 * pick does not replace; until then a feature the vendor names a model
 * for runs on it ([AiProfile.resolve]).
 */
@Serializable
data class FeatureSetting(val on: Boolean = false, val model: ModelRef? = null, val own: Boolean = false)

/** The features an Armillary vendor picks the model for, by the vendor's names for them. */
val VENDOR_FEATURES = mapOf(AiFeature.CatchUp to "catch_up", AiFeature.Assistant to "assistant", AiFeature.Dictation to "dictation")

/**
 * This setting back on the Armillary vendor's pick: no longer the
 * owner's own, and on the Armillary row, so a private model chosen
 * before it does not hold it ([AiProfile.followsVendor]).
 */
fun FeatureSetting.followingVendor(): FeatureSetting = copy(own = false, model = ModelRef(ARMILLARY_PROVIDER, ""))

@Serializable
data class AiProfile(
    val providers: List<AiProvider> = emptyList(),
    val defaultModel: ModelRef? = null,
    /**
     * The owner picked the default model by hand. Until then a default on
     * Armillary follows the vendor's ([followingVendorDefault]).
     */
    val defaultOwn: Boolean = false,
    val features: Map<AiFeature, FeatureSetting> = emptyMap(),
    /**
     * The Jev gate, status check and body picks, together. Null until
     * the owner flips it here: the decision model was set per install,
     * and one install's setting must not turn it off on another.
     */
    val jev: Boolean? = null,
    /**
     * Orrery, all of it: off (null or false) unless the owner turned it on.
     * Many will not want it: it reads messages, calls and mail. One switch
     * for every device, travelling with the other switches. Null is never
     * set, false turned off, which is what tells a device to give its key
     * back; see [orreryOn].
     */
    val orrery: Boolean? = null,
    /**
     * When the owner saved this profile on this device, or 0 for one
     * that arrived from the ship or was made from older settings. It
     * never travels. While set, this device has something to say about
     * the profile even with nothing in it: a provider taken out here is
     * gone everywhere, until the owner puts something back.
     */
    val savedHereAtMs: Long = 0,
    /**
     * [AiSettings.Config.armillaryOnly] has run on this device: switches
     * set after it are the owner's, and it never runs again. Local: the
     * profile no longer travels.
     */
    val armillaryOnly: Boolean = false,
    /** Own providers or keys were dropped by [AiSettings.Config.armillaryOnly]; the AI tab says so until the owner has read it. */
    val ownKeysDropped: Boolean = false,
) {
    fun provider(id: String): AiProvider? = providers.firstOrNull { it.id == id }

    /**
     * What [f] runs on, whether or not it is on. Armillary only
     * (docs/superpowers/specs/2026-10-16-armillary-only-design.md): the
     * vendor's model for the feature, else its default, else the row's
     * first chat model. Triage reads every message unasked, so it stays
     * on this device. A dictation the vendor names no model for runs on
     * the assistant's. Null with no Armillary row: nothing runs.
     */
    fun resolve(f: AiFeature): Resolved? = when (f) {
        AiFeature.OrreryTriage -> Resolved(
            provider(DEVICE_PROVIDER) ?: AiProvider(DEVICE_PROVIDER, ProviderKind.ThisDevice, ProviderKind.ThisDevice.label),
            features[f]?.model?.takeIf { it.provider == DEVICE_PROVIDER }?.model.orEmpty(),
        )
        AiFeature.Dictation -> vendorModel(f) ?: resolve(AiFeature.Assistant)
        else -> vendorModel(f) ?: armillary()?.let { resolve(ModelRef(it.id, "")) }
    }

    /**
     * [f]'s own model, or null where it follows the default. The device
     * model triage is started on, and the one a removed provider leaves
     * it on, are nobody's pick: triage follows the default from there.
     */
    fun pick(f: AiFeature): ModelRef? {
        val s = features[f] ?: return null
        if (f == AiFeature.OrreryTriage && !s.own && s.model?.provider == DEVICE_PROVIDER) return null
        return s.model
    }

    /**
     * What "Default" is for [f]: the default model. Triage reads every
     * message unasked, so it follows only a private default and stays on
     * this device otherwise; a cloud model picked on its own row is the
     * opt-in that takes it off the device.
     */
    fun defaultFor(f: AiFeature): ModelRef? {
        // A dictation is the assistant run on what was said: until it has
        // a model of its own it runs on the assistant's, whatever that is.
        if (f == AiFeature.Dictation) return resolve(AiFeature.Assistant)?.let { ModelRef(it.provider.id, it.model) }
        if (f != AiFeature.OrreryTriage) return defaultModel
        defaultModel?.takeIf { resolve(it)?.private == true }?.let { return it }
        return features[f]?.model?.takeIf { it.provider == DEVICE_PROVIDER } ?: ModelRef(DEVICE_PROVIDER, "")
    }

    /**
     * The ship's generator follows the default model: the owner picked
     * "Default" on its row. A row with no model the owner never picked
     * may be a generator set up on the ship itself, which is left alone.
     */
    fun generatorFollowsDefault(): Boolean = features[AiFeature.OrreryGenerator]?.let { it.own && it.model == null } == true

    /** The Armillary row, where this device buys from its ship. */
    fun armillary(): AiProvider? = providers.firstOrNull { it.kind == ProviderKind.Armillary }

    /** The vendor's model for [f] on the Armillary row, or null where the vendor names none. */
    fun vendorModel(f: AiFeature): Resolved? {
        val name = VENDOR_FEATURES[f] ?: return null
        val row = armillary() ?: return null
        val named = row.suggested[name]?.takeIf { it.isNotBlank() }
        // Not the vendor's default for a dictation: one the vendor did not
        // name follows the assistant ([defaultFor]), which may be the vendor's anyway.
        if (named == null && f == AiFeature.Dictation) return null
        val model = named ?: row.suggested["default"]?.takeIf { it.isNotBlank() } ?: return null
        return Resolved(row, model)
    }

    /** Whether [f] runs on Armillary: every feature but triage, once the row is there. */
    fun followsVendor(f: AiFeature): Boolean = resolve(f)?.provider?.kind == ProviderKind.Armillary

    /**
     * Whether [f]'s calls must go to ZDR endpoints only: every call on
     * Armillary, on whatever tier the vendor runs it, as Orrery asks on
     * every call. The payment that turned it on promised as much.
     */
    fun vendorZdr(f: AiFeature): Boolean = followsVendor(f)

    /**
     * The vendor's decision model and the key it runs on: the lease's
     * own OpenRouter key, so only under a lease, where the decisions
     * route is OpenRouter's. Null where the vendor names none.
     */
    fun vendorDecision(): Pair<String, String>? {
        val row = armillary() ?: return null
        val model = row.suggested["decision"]?.takeIf { it.isNotBlank() } ?: return null
        if (row.apiKey.isBlank() || "openrouter.ai" !in row.baseUrl.orEmpty()) return null
        return row.apiKey to model
    }

    /** What [ref] runs on, or null where it names no provider this profile has. */
    fun resolve(ref: ModelRef?): Resolved? {
        ref ?: return null
        val p = provider(ref.provider) ?: return null
        // Armillary and a server of your own have no default model: the
        // list is all there is, so a blank ref takes the first of it
        // rather than reaching the OpenAI-shaped client with no model.
        // Armillary's is the vendor's default where it names one
        // (armillary 19), so a blank Armillary default runs what Settings
        // calls "Default".
        val listOnly = p.kind == ProviderKind.Armillary || p.kind == ProviderKind.OpenAiCompatible
        val model = when {
            ref.model.isNotBlank() || !listOnly -> ref.model
            else -> p.suggested["default"]?.takeIf { it.isNotBlank() } ?: p.firstChatModel().orEmpty()
        }
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

/** The features besides triage that read the owner's messages. */
private val READS_MESSAGES = setOf(AiFeature.CatchUp, AiFeature.Assistant)

/**
 * The profile with provider [id] taken out. A feature on it follows
 * the default model after, which is usually a cloud one. Not one that
 * read messages on a model of the owner's own: following the default
 * sent them to the cloud with no word from the owner. Triage goes to
 * this device, wherever it read, since it reads every message with no
 * one asking and this device is its own ladder. Catch-up and the
 * assistant cannot run here, so they are turned off, which the owner
 * sees, rather than vanishing.
 */
fun AiProfile.without(id: String): AiProfile {
    val gone = provider(id)
    // A feature following the default was on this provider too, when the
    // default was: left on, it went to whatever default was picked next.
    val defaultGone = defaultModel?.provider == id
    return copy(
        providers = providers.filterNot { it.id == id },
        defaultModel = defaultModel?.takeUnless { it.provider == id },
        features = features.mapValues { (feature, f) ->
            val onIt = f.model?.provider == id || (f.model == null && defaultGone)
            when {
                !onIt -> f
                feature == AiFeature.OrreryTriage -> f.copy(model = ModelRef(DEVICE_PROVIDER, ""))
                gone?.isPrivate == true && feature in READS_MESSAGES -> FeatureSetting(on = false, model = null)
                else -> f.copy(model = null)
            }
        },
    )
}

/**
 * The first model on the list that chats: not a speech model, and not
 * an embedding one. ponytail: "embed" in the id is how an embedding
 * model is told from a chat one; LM Studio lists both and says nothing
 * else.
 */
fun AiProvider.firstChatModel(): String? = models.firstOrNull { !it.speech && "embed" !in it.id.lowercase() }?.id

/**
 * A blank choice on a server of your own made the model it stands for,
 * once the list is here. Lists do not travel, so another device got the
 * blank with no list to read it by, and every feature on it went.
 * Armillary's is left blank: its rows do not travel, and its list is
 * the ship's to change.
 */
fun AiProfile.pinningModels(): AiProfile {
    fun pin(ref: ModelRef?): ModelRef? {
        if (ref == null || ref.model.isNotBlank()) return ref
        val p = provider(ref.provider)?.takeIf { it.kind == ProviderKind.OpenAiCompatible } ?: return ref
        return p.firstChatModel()?.let { ref.copy(model = it) } ?: ref
    }
    return copy(defaultModel = pin(defaultModel), features = features.mapValues { (_, f) -> f.copy(model = pin(f.model)) })
}

/** A feature's provider and model. */
data class Resolved(val provider: AiProvider, val model: String) {
    val private: Boolean get() = provider.isPrivate

    /**
     * Why a chat client cannot call this, as a sentence, or null when it
     * can. One answer for the gate and for the screens that say why:
     * they disagreed, and a server with no address was told it lacked a
     * key.
     */
    fun problem(): String? {
        val p = provider
        return when {
            providerOf(p.kind) == null -> "${p.label} runs no chat model."
            p.kind == ProviderKind.OpenAiCompatible && p.baseUrl.isNullOrBlank() -> "${p.label} has no address."
            p.kind != ProviderKind.OpenAiCompatible && p.apiKey.isBlank() -> "${p.label} has no key."
            // A server of your own, or Armillary, before a model is picked
            // or listed: every call failed with "requires a model name".
            model.isBlank() && (p.kind == ProviderKind.OpenAiCompatible || p.kind == ProviderKind.Armillary) -> "${p.label} has no model chosen."
            else -> null
        }
    }
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
 * A profile for settings that never saved one: this device, and the
 * switches as the old toggles had them. Under Armillary-only an old
 * install's own keys make no provider: the Armillary row comes from the
 * ship ([io.nisfeb.talon.armillary.ArmillaryRepo]), and nothing else runs.
 */
fun migrateProfile(cfg: AiSettings.Config, inputs: ProfileInputs = ProfileInputs()): AiProfile = AiProfile(
    providers = listOf(AiProvider(DEVICE_PROVIDER, ProviderKind.ThisDevice, ProviderKind.ThisDevice.label)),
    features = mapOf(
        AiFeature.CatchUp to FeatureSetting(cfg.catchMeUpEnabled),
        AiFeature.Assistant to FeatureSetting(cfg.assistantOn()),
        AiFeature.OrreryTriage to FeatureSetting(inputs.orreryFed, ModelRef(DEVICE_PROVIDER, cfg.privateModel.orEmpty())),
        AiFeature.OrreryGenerator to FeatureSetting(inputs.generatorOn),
    ),
    jev = inputs.jevOn,
)

/** The features a payment buys, which it turns on ([allOn]). Triage and the generator are Orrery's. */
val PAID_FEATURES = listOf(AiFeature.CatchUp, AiFeature.Assistant, AiFeature.Transcription)

/** Every feature a payment buys, on: the payment is the owner's yes (sneagan, 2026-10-16). */
fun AiProfile.allOn(): AiProfile = copy(features = features + PAID_FEATURES.associateWith { (features[it] ?: FeatureSetting()).copy(on = true) })

/**
 * These settings as Armillary-only has them, once per device
 * (docs/superpowers/specs/2026-10-16-armillary-only-design.md): own
 * providers, own picks and the own keys in the old fields are dropped,
 * and every paid feature is on, whatever it was. sneagan, 2026-10-16:
 * the reset turns back on what someone turned off before, since most of
 * those offs meant no key yet. Search by meaning runs on this device and
 * keeps its switch. Triage keeps its device model and Orrery's switch.
 */
fun AiSettings.Config.armillaryOnly(): AiSettings.Config {
    if (savedProfile?.armillaryOnly == true) return this
    val p = profile()
    val kept = p.providers.filter { it.kind == ProviderKind.Armillary || it.kind == ProviderKind.ThisDevice }
    val hadOwn = kept.size != p.providers.size || apiKey.isNotBlank() || sttApiKey.isNotBlank() || braveApiKey.isNotBlank() || privateApiKey.isNotBlank()
    val triage = p.features[AiFeature.OrreryTriage]?.let { s -> s.copy(model = s.model?.takeIf { it.provider == DEVICE_PROVIDER }, own = false) }
    val profile = AiProfile(
        providers = kept.ifEmpty { listOf(AiProvider(DEVICE_PROVIDER, ProviderKind.ThisDevice, ProviderKind.ThisDevice.label)) },
        features = listOfNotNull(triage?.let { AiFeature.OrreryTriage to it }, p.features[AiFeature.OrreryGenerator]?.let { AiFeature.OrreryGenerator to it.copy(model = null, own = false) }).toMap(),
        jev = p.jev,
        orrery = p.orrery,
        savedHereAtMs = p.savedHereAtMs,
        armillaryOnly = true,
        ownKeysDropped = hadOwn,
    ).allOn()
    return copy(
        apiKey = "", sttApiKey = "", braveApiKey = "", privateApiKey = "", privateBaseUrl = null,
        catchMeUpEnabled = true, askUrbitEnabled = true, agentEnabled = true,
        savedProfile = profile,
    )
}

/** The profile these settings stand for: the one the owner saved, else the one today's fields make. */
fun AiSettings.Config.profile(): AiProfile = savedProfile ?: migrateProfile(this)

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
    // This device is no chat client's: the old fields it returned held
    // the frontier key, so a caller that skipped [hasModelFor] sent the
    // messages to the cloud.
    val provider = providerOf(r.provider.kind) ?: return copy(apiKey = "")
    return copy(
        provider = provider,
        apiKey = r.provider.apiKey,
        model = r.model.ifBlank { null },
        baseUrl = r.provider.baseUrl,
        // OpenRouter says what a call cost only when asked, and so does
        // an Armillary base, which is OpenRouter under a lease and the
        // vendor's own proxy otherwise.
        usageInclude = r.provider.kind == ProviderKind.OpenRouter || r.provider.kind == ProviderKind.Armillary,
        zdrOnly = profile().vendorZdr(f),
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
    // Every label a number: `10.0.0.1.example.com` is a public name, whose
    // owner points it anywhere, and was taken for 10.0.0.1.
    val o = host.split('.').map { it.toIntOrNull() ?: return false }
    if (o.size != 4) return false
    return o[0] == 127 || o[0] == 10 || (o[0] == 192 && o[1] == 168) || (o[0] == 172 && o[1] in 16..31) ||
        (o[0] == 100 && o[1] in 64..127)
}

// ---- storage and sync ------------------------------------------------------

/**
 * Each provider's key, where it has one. Not Armillary's: it is minted
 * for this device, and goes nowhere else.
 */
fun AiProfile.keys(): Map<String, String> =
    providers.filter { it.apiKey.isNotBlank() && it.kind != ProviderKind.Armillary }.associate { it.id to it.apiKey }

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
 * Whether [f] resolves to a model a chat client can call: see
 * [modelProblem] for what that takes.
 *
 * What the features gate on. The old check was whether the old key
 * field was set, which a local LM Studio or Ollama server never sets,
 * so those features stayed hidden for it, and which a removed key
 * went on satisfying.
 */
fun AiSettings.Config.hasModelFor(f: AiFeature): Boolean = modelProblem(f) == null

/**
 * Loops run on the assistant, so only while it is on and has a model.
 * They ran with it off, and lost its web tools without a word.
 */
fun AiSettings.Config.loopsCanRun(): Boolean = assistantOn() && hasModelFor(AiFeature.Assistant)

/** Why [f] has no model a chat client can call, as a sentence, or null when it has one ([Resolved.problem]). */
fun AiSettings.Config.modelProblem(f: AiFeature): String? {
    val r = profile().resolve(f) ?: return "No model is set."
    return r.problem()
}

/**
 * The settings once the owner saves [saved] on this device, at [now]:
 * the profile, with any blank server model made the one it stands for
 * ([pinningModels]), and the old fields derived from it.
 *
 * One place for all three platforms. A key that was on a provider and
 * is on none now was removed here, by taking the provider out or by
 * clearing the key: it is marked revoked, which takes it out of every
 * field that kept it, the transcription and private ones too, and
 * tells the peers. One the profile never held stays: that is the case
 * the "a blank never overwrites" rule in [legacyInto] was written for.
 */
fun AiSettings.Config.withProfile(saved: AiProfile, now: Long): AiSettings.Config {
    val p = saved.pinningModels().copy(savedHereAtMs = now)
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
 * The switches that travel, as the old toggles did. Transcription stays
 * per device: an older build turned it off where it had no speech key.
 * ponytail: under Armillary-only it could travel too; it is left as it
 * was so an older build's write cannot reach it.
 */
private val SYNCED_SWITCHES = listOf(AiFeature.CatchUp, AiFeature.Assistant)

/** The switches as the config entry carries them. Triage is Feed Orrery, per install; the generator is the ship's. */
fun AiProfile.switches(): kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.buildJsonObject {
    SYNCED_SWITCHES.forEach { f -> put(f.name, kotlinx.serialization.json.JsonPrimitive(isOn(f))) }
    jev?.let { put("jev", kotlinx.serialization.json.JsonPrimitive(it)) }
    orrery?.let { put("orrery", kotlinx.serialization.json.JsonPrimitive(it)) }
}

fun AiProfile.withSwitches(s: kotlinx.serialization.json.JsonObject): AiProfile = copy(
    features = features + SYNCED_SWITCHES.mapNotNull { f ->
        (s[f.name] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBooleanStrictOrNull()
            ?.let { on -> f to (features[f] ?: FeatureSetting()).copy(on = on) }
    },
    jev = (s["jev"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: jev,
    orrery = (s["orrery"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: orrery,
)

/**
 * What transcription runs on: its own speech model, else whisper-1 on the
 * default provider where that one can transcribe, as
 * [CallRecordingPublisher.sttFrom] does. The row said "None chosen" while
 * whisper-1 ran.
 */
fun AiProfile.transcriptionRef(): ModelRef? = features[AiFeature.Transcription]?.model
    ?: defaultModel?.takeIf { d -> provider(d.provider)?.kind.let { it == ProviderKind.OpenAi || it == ProviderKind.OpenAiCompatible } }?.copy(model = WHISPER)

/** Orrery is on: turned on by the owner, on some device. Off by default. */
fun AiSettings.Config.orreryOn(): Boolean = savedProfile?.orrery == true

/** The profile with Orrery turned [on], from the saved one or the one these settings make. */
fun AiSettings.Config.withOrrery(on: Boolean): AiProfile = (savedProfile ?: migrateProfile(this)).copy(orrery = on)

/** The model Talon runs on [this] when none is picked; null where the provider's own list is all there is. */
fun ProviderKind.defaultModel(): String? = when (this) {
    ProviderKind.OpenRouter -> "anthropic/claude-sonnet-4"
    ProviderKind.OpenAi -> "gpt-4o-mini"
    else -> null
}
