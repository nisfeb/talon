package io.nisfeb.talon.ai

import kotlinx.serialization.Serializable
import okio.ByteString.Companion.encodeUtf8

/**
 * Portable data types for AI provider configuration.
 *
 * The Android implementation (backed by EncryptedSharedPreferences) lives in
 * app/src/main/java/io/nisfeb/talon/ai/AiSettings.kt. Desktop and other
 * targets will provide their own storage in Stage C.
 *
 * This file lives in commonMain so that AiClient (also commonMain) can
 * reference Config and Provider without pulling in Android APIs.
 */
object AiSettings {
    /**
     * Set by an error surface whose failure was the empty Armillary
     * balance, when its Top up action opens Settings, AI. The Armillary
     * card reads it once, opens the top-up sheet, and clears it.
     */
    val pendingTopUp = kotlinx.coroutines.flow.MutableStateFlow(false)


    enum class Provider(val label: String) {
        Anthropic("Anthropic (Claude)"),
        OpenRouter("OpenRouter"),
        OpenAi("OpenAI"),
        Custom("Custom (OpenAI endpoint)"),
    }

    /**
     * One model Talon can reach, whichever of the two it is.
     *
     * Either may be on this machine or across the world, and either
     * may need a key or none: what decides it is the address, not the
     * word. A blank [baseUrl] on the private model means the device's
     * own, whatever it has.
     */
    data class Slot(
        val provider: Provider?,
        val apiKey: String,
        val model: String?,
        val baseUrl: String?,
    ) {
        val hasKey: Boolean get() = apiKey.isNotBlank()
    }

    @Serializable
    data class Config(
        val provider: Provider,
        val apiKey: String,
        val model: String?,
        val baseUrl: String? = null,
        /**
         * The private model: the one that reads your messages.
         *
         * Blank [privateBaseUrl] means whatever this device can run
         * itself, which is what every platform falls back to. A URL
         * points it at a server instead, on this machine or another of
         * yours, with a key only where that server wants one.
         */
        val privateApiKey: String = "",
        val privateModel: String? = null,
        val privateBaseUrl: String? = null,
        /**
         * Let the frontier model read messages too. Off, and it stays
         * off until it is chosen: every message it reads leaves the
         * device. Was the orrery "cloud triage" switch.
         */
        val frontierReadsMessages: Boolean = false,
        // Feature toggles default to true so a fresh install starts with
        // the full feature set on. Capability flags still hide what a
        // platform can't run. Users who explicitly disable a feature keep
        // that choice across upgrades — applyRemote / setFeature persist
        // the explicit value, so the new defaults only apply when the
        // SharedPreferences key is absent.
        val catchMeUpEnabled: Boolean = true,
        // One switch for the on-device embedder features: search-by-meaning,
        // topic clustering, and bookmark-similarity highlighting. They all
        // share the same on-device index, so they're enabled together.
        val smartFeaturesEnabled: Boolean = true,
        // Opt-in, unlike the others: the assistant is rolled out behind
        // rc releases, so it defaults OFF and stays invisible until the
        // user both configures a key and turns it on. The assistant
        // subsumes MCP (ship tools) and web access — there are no separate
        // toggles; both are active whenever the assistant is on (writes
        // and pokes are still confirmed; dangerous MCP tools stay hidden).
        val askUrbitEnabled: Boolean = false,
        val agentEnabled: Boolean = false,
        val syncEnabled: Boolean = true,
        // Brave Search API credential for the assistant's web search.
        // Optional (the assistant can open URLs without it). Travels with
        // the same syncEnabled gate as the LLM key (see SettingsSyncImpl).
        val braveApiKey: String = "",
        // Dedicated speech-to-text (Whisper) credential for call
        // transcription. Independent of the chat provider so a user can
        // run Claude for chat and OpenAI Whisper for transcripts. Blank
        // falls back to the chat key (OpenAI / Custom only). Same sync
        // gate as apiKey.
        val sttApiKey: String = "",
        /**
         * When the user last removed the transcription key on some
         * device, as a wall-clock stamp; 0 when it was never removed or
         * a key has been set since. Synced so a removal reaches other
         * devices, while a device that simply never had the key cannot
         * blank everyone's by pushing.
         */
        val sttApiKeyRemovedAtMs: Long = 0L,
        /**
         * When this device last set the transcription key. A removal
         * only counts if it happened after: without this, setting a key
         * here reset the removal stamp to zero and a peer's old removal,
         * still sitting on the ship, blanked the key again on the next
         * pull. That is the "I just typed it and it vanished" loop.
         */
        val sttApiKeySetAtMs: Long = 0L,
        /**
         * Every key taken out, on any device, by its fingerprint
         * ([keyPrint]): a provider removed because its key leaked, or a
         * key cleared. A blank never replaces a key, so a removal used
         * to reach nobody, and came back from the first peer, older
         * install or ship copy that still held it. A key marked here is
         * refused wherever it arrives from and taken out wherever it is
         * kept ([withoutRevoked]). Typing the same key in again marks
         * it back; the later mark wins. Travels with the credentials.
         */
        val revokedKeys: Map<String, KeyMark> = emptyMap(),
        // Editable agent system-prompt parts. Each blank = use its built-in
        // default; the effective prompt for a role is the shared knowledge
        // followed by that role's specifics (see AgentPrompt/LoopPrompt).
        // Not credentials — synced like preferences (always, schemaVersion>=2).
        val urbitKnowledgePrompt: String = "", // shared by assistant + loops
        val assistantPrompt: String = "",      // interactive-assistant specifics
        val loopPrompt: String = "",           // headless-loop specifics
        // Stable per-device id, generated once by the platform store and
        // DEVICE-LOCAL — never pushed to or read from %settings (see
        // SettingsSyncImpl push/apply). Identifies this device when it
        // contests the cross-device write-loop lease (LoopWriteCoordinator).
        val deviceId: String = "",
        /**
         * The AI settings as the owner set them on the new screen:
         * providers, the default model, each feature's switch and model.
         * Null until then, and the fields above are what [profile]
         * derives one from. While it is set the fields above are kept
         * derived from it (legacyInto), for older installs.
         */
        val savedProfile: AiProfile? = null,
        /**
         * Whether the chat clients should ask this call to report its
         * cost. Derived by [forFeature] from the resolved provider, not
         * a setting: it is never stored and never synced.
         */
        @kotlinx.serialization.Transient
        val usageInclude: Boolean = false,
    ) {
        fun hasKey(): Boolean = apiKey.isNotBlank()

        /** The model everything but message reading uses. */
        val frontier: Slot get() = Slot(provider, apiKey, model, baseUrl)

        /** The model that reads messages, when it is not this device's own. */
        val private: Slot get() = Slot(null, privateApiKey, privateModel, privateBaseUrl)

        /**
         * Whether this device has anything to say about credentials. A
         * device with none never writes them to the ship, so saving a
         * preference here cannot wipe the copy another device put there.
         */
        fun hasCredentials(): Boolean =
            apiKey.isNotBlank() || braveApiKey.isNotBlank() || sttApiKey.isNotBlank() ||
                privateApiKey.isNotBlank() || privateBaseUrl?.isNotBlank() == true ||
                sttApiKeyRemovedAtMs > 0L || revokedKeys.isNotEmpty() || savedProfile?.keys()?.isNotEmpty() == true

        /** The unified assistant is on (current flag or the legacy one).
         *  Gates MCP + web access, which are now part of the assistant. */
        fun assistantOn(): Boolean = agentEnabled || askUrbitEnabled

        /** Read the editable prompt for [kind] (blank = use built-in default). */
        fun prompt(kind: PromptKind): String = when (kind) {
            PromptKind.UrbitKnowledge -> urbitKnowledgePrompt
            PromptKind.Assistant -> assistantPrompt
            PromptKind.Loop -> loopPrompt
        }

        /** Copy with [kind]'s editable prompt set to [value]. */
        fun withPrompt(kind: PromptKind, value: String): Config = when (kind) {
            PromptKind.UrbitKnowledge -> copy(urbitKnowledgePrompt = value)
            PromptKind.Assistant -> copy(assistantPrompt = value)
            PromptKind.Loop -> copy(loopPrompt = value)
        }
    }

    /** The three editable system-prompt parts. UrbitKnowledge is shared by
     *  the assistant and loops; the other two are role-specific. */
    enum class PromptKind { UrbitKnowledge, Assistant, Loop }

    /**
     * Migration policy for the pre-0.14 fold of four per-feature toggles
     * into SmartFeatures, shared by both platform stores. [present] holds
     * the legacy values that exist in storage; [total] is how many legacy
     * toggles there were. Neither store ever wrote a toggle the user hadn't
     * touched (Android writes per-key on change; desktop serialized with
     * encodeDefaults=false), so an ABSENT toggle means default-true — the
     * fold is off only when every toggle is present and false.
     */
    fun migratedSmartFeatures(present: List<Boolean>, total: Int): Boolean =
        present.size < total || present.any { it }

    /**
     * Per-feature toggles. SettingsScreen iterates this enum to render
     * the AI features section. Field-for-field copy of the production
     * enum at app/src/main/java/io/nisfeb/talon/ai/AiSettings.kt.
     */
    enum class Feature(
        val key: String,
        val label: String,
        val description: String,
        val requiresCloudKey: Boolean,
    ) {
        CatchMeUp(
            "feat_catch_me_up",
            "Catch me up",
            "When you open a chat with unread messages, offer a summary.",
            requiresCloudKey = true,
        ),
        // One toggle for the on-device embedder suite — search by meaning,
        // topic clusters, and important-message highlighting. They share
        // the same on-device index, so they live and die together.
        SmartFeatures(
            "feat_smart_features",
            "Smart features (on-device)",
            "Search your chats by meaning, group a chat's messages by topic, and highlight incoming messages similar to ones you've bookmarked. Runs entirely on-device.",
            requiresCloudKey = false,
        ),
        // One unified assistant (was Ask + Act). It answers questions
        // grounded in your real messages AND takes actions; anything that
        // changes data is confirmed first. Keeps key "feat_agent" so
        // existing agent-enabled installs carry over; setFeature/read
        // keep the legacy askUrbit flag in lockstep for migration. The
        // assistant subsumes MCP and web access — no separate toggles.
        Agent(
            "feat_agent",
            "Assistant (beta)",
            "A chat assistant grounded in your real messages. Ask about your history or tell it to do things — search, send, reply, react, mark read. It can also reach your ship's MCP tools and the public web. Anything that changes data is shown for your confirmation first.",
            requiresCloudKey = true,
        ),
    }
}

/** A key taken out at [at], or put back where [revoked] is false. */
@Serializable
data class KeyMark(val at: Long, val revoked: Boolean = true)

/** Marks kept, newest first: enough for every key anyone will remove. */
private const val MAX_MARKS = 64

/** A key's fingerprint: enough to know it again, nothing to use it with. */
fun keyPrint(key: String): String = key.trim().encodeUtf8().sha256().hex().take(16)

/** Two sets of marks as one: for each key the later word. Ties go to the removal. */
fun mergedMarks(a: Map<String, KeyMark>, b: Map<String, KeyMark>): Map<String, KeyMark> =
    (a.keys + b.keys).associateWith { k -> listOfNotNull(a[k], b[k]).maxWith(compareBy({ it.at }, { it.revoked })) }
        .entries.sortedWith(compareByDescending<Map.Entry<String, KeyMark>> { it.value.at }.thenBy { it.key })
        .take(MAX_MARKS).associate { it.key to it.value }

fun AiSettings.Config.isRevoked(key: String): Boolean = key.isNotBlank() && revokedKeys[keyPrint(key)]?.revoked == true

/**
 * These settings with every revoked key taken out, wherever it was
 * kept. A transcription key taken out is stamped as the older installs
 * understand, so they drop it too.
 */
fun AiSettings.Config.withoutRevoked(): AiSettings.Config {
    if (revokedKeys.values.none { it.revoked }) return this
    fun kept(k: String) = if (isRevoked(k)) "" else k
    val sttGone = revokedKeys[keyPrint(sttApiKey)]?.takeIf { isRevoked(sttApiKey) }
    return copy(
        apiKey = kept(apiKey),
        privateApiKey = kept(privateApiKey),
        braveApiKey = kept(braveApiKey),
        sttApiKey = kept(sttApiKey),
        sttApiKeyRemovedAtMs = sttGone?.let { maxOf(sttApiKeyRemovedAtMs, it.at) } ?: sttApiKeyRemovedAtMs,
        savedProfile = savedProfile?.let { p -> p.copy(providers = p.providers.map { it.copy(apiKey = kept(it.apiKey)) }) },
    )
}

/**
 * These settings once the owner, on this device at [now], took the keys
 * [gone] out and put [back] in: the one way a key is revoked, or a
 * revoked one restored.
 *
 * Every key put in is marked, not only one this device knows was
 * revoked: typed on a device the revocation had not reached yet, it
 * had no mark, and the older revocation took it out when it arrived.
 */
fun AiSettings.Config.marking(gone: Collection<String>, back: Collection<String>, now: Long): AiSettings.Config {
    val marks = revokedKeys.toMutableMap()
    gone.filter { it.isNotBlank() }.forEach { marks[keyPrint(it)] = KeyMark(now) }
    back.filter { it.isNotBlank() }.forEach { marks[keyPrint(it)] = KeyMark(now, revoked = false) }
    return copy(revokedKeys = mergedMarks(marks, emptyMap())).withoutRevoked()
}

/** The Brave key set to [key] on this device at [now]: the one it replaces is revoked. */
fun AiSettings.Config.withBraveKey(key: String, now: Long): AiSettings.Config =
    copy(braveApiKey = key).marking(gone = listOf(braveApiKey) - key, back = listOf(key) - braveApiKey, now = now)

/**
 * What arriving state may not do: drop a credential this device holds.
 *
 * The one rule that has been missing. This bug has been fixed four
 * times, each time by guarding the path that had just lost a key, and
 * each time a new path appeared: a peer's push, an entry that replaced
 * a whole blob, a stale removal stamp. A blank is not a value here. It
 * is the absence of one, and absence never wins against something
 * real. A deliberate removal says so with a mark ([revokedKeys]) and
 * is the only way a credential goes; the marks of both sides are kept.
 *
 * Enforced in the store rather than in the sync layer, so that a
 * future caller has to break the rule on purpose to lose a key.
 */
fun AiSettings.Config.keepingCredentials(of: AiSettings.Config): AiSettings.Config {
    val removalWins = sttApiKeyRemovedAtMs > maxOf(of.sttApiKeySetAtMs, of.sttApiKeyRemovedAtMs)
    return copy(
        apiKey = apiKey.ifBlank { of.apiKey },
        revokedKeys = mergedMarks(revokedKeys, of.revokedKeys),
        braveApiKey = braveApiKey.ifBlank { of.braveApiKey },
        privateApiKey = privateApiKey.ifBlank { of.privateApiKey },
        privateBaseUrl = privateBaseUrl ?: of.privateBaseUrl,
        privateModel = privateModel ?: of.privateModel,
        model = model ?: of.model,
        baseUrl = baseUrl ?: of.baseUrl,
        sttApiKey = if (sttApiKey.isNotBlank() || removalWins) sttApiKey else of.sttApiKey,
        sttApiKeySetAtMs = maxOf(sttApiKeySetAtMs, of.sttApiKeySetAtMs),
        // A profile arriving without keys keeps this device's; none arriving keeps this device's profile.
        savedProfile = savedProfile?.keepingLocal(of.savedProfile ?: migrateProfile(of)) ?: of.savedProfile,
    ).withoutRevoked()
}
