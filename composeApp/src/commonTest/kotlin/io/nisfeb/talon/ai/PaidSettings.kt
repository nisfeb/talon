package io.nisfeb.talon.ai

/** The vendor's speech model in tests. */
const val VENDOR_WHISPER = "openai/whisper-large-v3-turbo"

/** The Armillary row as a paying owner's device has it: a lease key, and the vendor's picks. */
fun armillaryRow(
    key: String = "sk-or-lease",
    base: String? = "https://openrouter.ai/api/v1",
    suggested: Map<String, String> = mapOf("default" to "anthropic/claude-sonnet-5.5"),
) = AiProvider(ARMILLARY_PROVIDER, ProviderKind.Armillary, ProviderKind.Armillary.label, baseUrl = base, apiKey = key, suggested = suggested)

/**
 * Settings that run AI: this device and an Armillary row, with the
 * assistant on unless [assistant] says not, and with [speech] the
 * vendor's transcription model and call transcription on.
 */
fun paidSettings(
    key: String = "sk-or-lease",
    assistant: Boolean = true,
    speech: Boolean = false,
    row: AiProvider = armillaryRow(key, suggested = mapOf("default" to "anthropic/claude-sonnet-5.5") + if (speech) mapOf(TRANSCRIPTION to VENDOR_WHISPER) else emptyMap()),
) = AiSettings.Config(
    provider = AiSettings.Provider.Custom,
    apiKey = "",
    model = null,
    agentEnabled = assistant,
    askUrbitEnabled = assistant,
    savedProfile = AiProfile(
        providers = listOf(AiProvider(DEVICE_PROVIDER, ProviderKind.ThisDevice, ProviderKind.ThisDevice.label), row),
        features = mapOf(
            AiFeature.CatchUp to FeatureSetting(on = true),
            AiFeature.Assistant to FeatureSetting(on = assistant),
            AiFeature.Transcription to FeatureSetting(on = speech),
        ),
    ),
)
