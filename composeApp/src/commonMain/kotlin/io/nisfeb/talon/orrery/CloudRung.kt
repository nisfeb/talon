package io.nisfeb.talon.orrery

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
    override val name: String get() = "Your ${config().provider.label} key, in the cloud"
    private val ai by lazy { AiClient(config) }

    override suspend fun status(): RungStatus =
        if (config().apiKey.isBlank()) RungStatus.Unavailable("No API key is set under AI.") else RungStatus.Ready

    override suspend fun open(): LocalModel = object : LocalModel {
        override val rung: String get() = name
        override suspend fun complete(system: String, user: String, grammar: String?, maxTokens: Int): String =
            ai.complete(system, user, maxTokens)
        override fun close() = Unit
    }
}

/** The phone's choice to leave reading to a computer, and the setter behind the switch. */
class StandDown(val on: StateFlow<Boolean>, val set: (Boolean) -> Unit)
