package io.nisfeb.talon.armillary

import io.nisfeb.talon.ai.ModelInfo

/**
 * What Brave Leo's "Bring your own model" form asks for, filled from the
 * ship's inference config, or why Leo cannot run on it.
 *
 * Leo streams every chat answer (`"stream": true`), and the vendor's
 * proxy refuses a stream, so only a lease works: the model provider's
 * own key and address, which Leo calls directly with a bearer key. A
 * ship holds one lease, so the key is the one Talon itself uses; no
 * second key is minted (a proxy key would not stream either, and the
 * newest key is the one `/api/inference` hands every Talon device).
 */
sealed interface LeoSetup {
    data class Ready(
        /** Leo's Label: the name in its model menu, free text. */
        val label: String,
        /** Leo's Model request name. */
        val model: String,
        /** Leo's Server endpoint: the full chat completions URL. */
        val endpoint: String,
        /** Leo's API Key, sent as `Authorization: Bearer`. */
        val apiKey: String,
        /** Leo's Context size in tokens where the model's own is known; null leaves Leo's 4000. */
        val contextSize: Int?,
    ) : LeoSetup

    data class Cannot(val why: String) : LeoSetup
}

/**
 * Leo's fields from [inf], on [model] where the ship lists it and its
 * first model otherwise. [known] is the provider row's models, which is
 * where a model's context length is kept. [leaseDisabled] is the account
 * saying its lease ran out of credit, which is why a ship answers proxy.
 */
fun leoSetup(inf: Inference, leaseDisabled: Boolean, model: String?, known: List<ModelInfo> = emptyList()): LeoSetup {
    if (inf.mode != "lease") return LeoSetup.Cannot(
        if (leaseDisabled) {
            "Your balance is empty, so your ship's lease is switched off. Leo works again once there is credit on the account."
        } else {
            "Leo streams its answers, and the vendor's ship does not pass a stream on. Leo needs a lease, a key " +
                "straight to the model provider, and your ship holds none from this vendor."
        },
    )
    val base = inf.baseUrl.trim().trimEnd('/')
    // Brave refuses a plain http address that is not on this machine.
    if (!base.startsWith("https://")) {
        return LeoSetup.Cannot("Brave takes only an https address, and your ship's model provider is at ${base.ifBlank { "no address" }}.")
    }
    if (inf.key.isBlank()) return LeoSetup.Cannot("Your ship holds no key yet.")
    val pick = model?.takeIf { it in inf.models } ?: inf.models.firstOrNull()
        ?: return LeoSetup.Cannot("Your ship lists no models yet.")
    return LeoSetup.Ready(
        label = "$pick (Armillary)",
        model = pick,
        endpoint = if (base.endsWith(CHAT_PATH)) base else base + CHAT_PATH,
        apiKey = inf.key.trim(),
        contextSize = known.firstOrNull { it.id == pick }?.contextLength?.takeIf { it > 0 }?.coerceAtMost(LEO_MAX_CONTEXT),
    )
}

/** Where Leo's form is, in words that hold on a computer and on an iPhone. */
const val LEO_STEPS =
    "In Brave, open Settings, then Leo. Under Bring your own model, add a new model and paste these in. " +
        "On a computer the page is brave://settings/leo-ai."

/** Said under the key, since it is not one of Leo's own. */
const val LEO_KEY_NOTE =
    "This is your ship's lease, the same key Talon uses here and on your other devices: a ship holds only one. " +
        "Leo spends from the same balance. Removing Armillary here gives the lease back, and Leo stops with it."

private const val CHAT_PATH = "/chat/completions"

/** The largest context size Leo's form takes. */
private const val LEO_MAX_CONTEXT = 2_000_000
