package io.nisfeb.talon.orrery

import io.nisfeb.talon.ai.forFeature
import io.nisfeb.talon.ai.triageInCloud
import io.nisfeb.talon.ai.AiClient
import io.nisfeb.talon.ai.AiSettings
import kotlinx.coroutines.flow.StateFlow

/**
 * The person's own cloud key as a rung, only while they have said so.
 *
 * It sits at the top of the ladder when on, because they chose it
 * knowing what it costs: every message the triage reads goes to the
 * provider they picked. Off by default, in its own switch, with that
 * consequence written beside it. Nothing here runs unless [on] says.
 */
class CloudTriage(
    val on: StateFlow<Boolean>,
    val set: (Boolean) -> Unit,
    val config: () -> AiSettings.Config,
) {
    val rung: CloudRung by lazy { CloudRung(config) }
}

class CloudRung(private val config: () -> AiSettings.Config) : Rung() {
    // The model triage is assigned, which is the frontier model's unless
    // the owner named another.
    private fun triage() = config().forFeature(io.nisfeb.talon.ai.AiFeature.OrreryTriage)
    override val name: String get() = "Your ${triage().provider.label} key, in the cloud"
    private val ai by lazy { AiClient { triage() } }

    override suspend fun status(): RungStatus =
        if (triage().apiKey.isBlank()) RungStatus.Unavailable("No API key is set under AI.") else RungStatus.Ready

    override suspend fun open(): LocalModel = object : LocalModel {
        override val rung: String get() = name
        override suspend fun complete(system: String, user: String, grammar: String?, maxTokens: Int): String =
            ai.complete(system, user, maxTokens)
        override val lastCostUsd: Double? get() = ai.lastCostUsd
        override fun close() = Unit
    }
}

/** The phone's choice to leave reading to a computer, and the setter behind the switch. */
class StandDown(val on: StateFlow<Boolean>, val set: (Boolean) -> Unit)

/**
 * The switch that lets the frontier model read messages, as a flow.
 * It lives with the rest of the AI configuration, so it follows the
 * person across their devices like every other setting.
 */
fun frontierReadsMessages(
    settings: io.nisfeb.talon.ai.AiSettingsRepository,
): kotlinx.coroutines.flow.StateFlow<Boolean> = io.nisfeb.talon.util.mapState(settings.state) { it.triageInCloud() }

/**
 * Carry the private model's address across from where it used to be
 * kept, once. It was a per-device preference and is now part of the
 * configuration that syncs, which is the whole point: a person who set
 * up their own server on the computer should not have to set it up
 * again on the laptop.
 */
suspend fun movePrivateModelIn(
    settings: io.nisfeb.talon.ai.AiSettingsRepository,
    ui: io.nisfeb.talon.ui.UiSettings,
) {
    val cfg = settings.state.value
    if (cfg.privateBaseUrl != null || cfg.privateModel != null) return
    val url = ui.orreryServerUrl.value.trim()
    val model = ui.orreryServerModel.value.trim()
    if (url.isEmpty() && model.isEmpty()) return
    settings.setPrivateModel(url.ifEmpty { null }, model.ifEmpty { null }, cfg.privateApiKey)
}
