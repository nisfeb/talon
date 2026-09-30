package io.nisfeb.talon.orrery

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A language model that runs on this device. One contract on every
 * rung of the ladder: a system prompt, a user turn, an optional
 * grammar that bounds the answer, and the text that came back.
 */
interface LocalModel : AutoCloseable {
    /** The rung's name, for Settings. */
    val rung: String

    suspend fun complete(system: String, user: String, grammar: String?, maxTokens: Int): String

    /** What the last answer cost, where someone charges for it; nothing on this device. */
    val lastCostUsd: Double? get() = null
}

sealed interface RungStatus {
    data object Ready : RungStatus

    /** Could run here after a download of about [bytes]. */
    data class NeedsDownload(val bytes: Long) : RungStatus

    /** Not on this device, and why, in words for the person. */
    data class Unavailable(val reason: String) : RungStatus
}

/**
 * One way to run a model on this platform. The platform lists its
 * rungs best first; the ladder takes the first that is ready.
 */
abstract class Rung {
    abstract val name: String
    abstract suspend fun status(): RungStatus

    /** Fetch what the rung needs, reporting 0 to 1. A no-op where nothing is needed. */
    open suspend fun prepare(progress: (Float) -> Unit) {}

    abstract suspend fun open(): LocalModel
}

/** The platform's rungs, best first. Empty where no local model runs yet. */
expect fun localModelRungs(): List<Rung>

/**
 * The ladder: the best rung that is ready on this device, opened once
 * and kept. A rung that fails to open is passed over, not retried on
 * every message.
 */
object LocalModels {
    /**
     * The triage's own server, from Settings: a base URL to use instead
     * of looking on the usual ports, and a model name to use instead of
     * the server's best by name. Empty means look and pick.
     */
    @kotlin.concurrent.Volatile var serverUrl: String = ""
    @kotlin.concurrent.Volatile var serverModel: String = ""

    /** The key that server wants, where it wants one. Most want none. */
    @kotlin.concurrent.Volatile var serverKey: String = ""

    /** The private model's settings, as one call from wherever they are kept. */
    suspend fun usePrivate(slot: io.nisfeb.talon.ai.AiSettings.Slot) {
        val url = slot.baseUrl.orEmpty()
        val name = slot.model.orEmpty()
        if (url == serverUrl && name == serverModel && slot.apiKey == serverKey) return
        serverUrl = url
        serverModel = name
        serverKey = slot.apiKey
        reset()
    }

    private val lock = Mutex()
    private var opened: Pair<Rung, LocalModel>? = null
    private val failed = mutableSetOf<String>()
    /** Rungs that opened and then failed as a runtime (an Error), set aside for the process. */
    private val broken = mutableSetOf<String>()

    /**
     * A rung whose runtime failed mid-answer, out of memory or a library
     * missing: set aside until the process restarts, so the ladder moves
     * on. Held as "the model did not answer", it held the reading on the
     * same failure every pass for good.
     */
    suspend fun broke(rung: String) = lock.withLock {
        broken += rung
        if (opened?.first?.name == rung) {
            runCatching { opened?.second?.close() }
            opened = null
        }
    }

    suspend fun best(): Pair<Rung, LocalModel>? = lock.withLock {
        opened?.let { return it }
        for (r in localModelRungs()) {
            if (r.name in failed || r.name in broken || r.status() != RungStatus.Ready) continue
            // A pass stopped while a model loads is not a model that
            // failed: counted as one, every later pass read without it.
            val m = io.nisfeb.talon.util.runSuspendCatching { r.open() }.getOrElse { failed += r.name; null } ?: continue
            opened = r to m
            return opened
        }
        null
    }

    /** Every rung and where it stands, best first. */
    suspend fun statuses(): List<Pair<Rung, RungStatus>> = localModelRungs().map { it to it.status() }

    /** Forget the opened model, so the next ask re-walks the ladder (after a download, say). */
    suspend fun reset() = lock.withLock {
        opened?.second?.close()
        opened = null
        failed.clear()
    }
}
