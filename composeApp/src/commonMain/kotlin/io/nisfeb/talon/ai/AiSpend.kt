package io.nisfeb.talon.ai

import io.nisfeb.talon.data.OrrerySentDao
import io.nisfeb.talon.data.OrrerySentEntity
import io.nisfeb.talon.util.nowMs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * This month's spend by feature, from the cost each call reports, for
 * the feature table. Kept in the ship's database beside the orrery
 * record; the shell points [dao] there when the database opens. Keyed
 * by [AiFeature] name, and [JEV] for the gate, check and picks.
 * ponytail: a call whose provider reports no cost (Anthropic's unknown
 * models, a server of your own, transcription) adds nothing.
 */
object AiSpend {
    const val JEV = "Jev"

    @kotlin.concurrent.Volatile var dao: OrrerySentDao? = null
    private val lock = Mutex()
    private val _month = MutableStateFlow<Map<String, Double>>(emptyMap())
    val month: StateFlow<Map<String, Double>> = _month.asStateFlow()
    private val serializer = MapSerializer(String.serializer(), Double.serializer())

    suspend fun add(what: String, usd: Double?, atMs: Long = nowMs()) {
        if (usd == null || usd <= 0.0) return
        val d = dao ?: return
        // A ledger that cannot write loses a line; the call it counts has already happened.
        runCatching {
            lock.withLock {
                val key = key(atMs)
                val was = read(d, key)
                val now = was + (what to (was[what] ?: 0.0) + usd)
                d.put(OrrerySentEntity("", key, Json.encodeToString(serializer, now), atMs))
                _month.value = now
            }
        }
    }

    suspend fun load(atMs: Long = nowMs()) {
        val d = dao ?: return
        runCatching { _month.value = read(d, key(atMs)) }
    }

    private suspend fun read(d: OrrerySentDao, key: String): Map<String, Double> =
        d.get("", key)?.value?.let { runCatching { Json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

    private fun key(ms: Long) = "spend:" + kotlinx.datetime.Instant.fromEpochMilliseconds(ms)
        .toLocalDateTime(TimeZone.currentSystemDefault()).date.toString().take(7)
}
