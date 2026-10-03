package io.nisfeb.talon.ui

import androidx.compose.runtime.staticCompositionLocalOf
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.CometDomeEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * Whether a comet is on Groundwire, as the signed-in ship's Jael knows
 * it: `%dome` is on Jael's public scry list, and answers `[~ %gw-btc]`
 * for a comet attested on Bitcoin and `~` for one it has no record of.
 *
 * Asked once per comet, ever: an answer goes into the per-ship
 * database and is never asked again. Only an answer is kept. A ship
 * without Groundwire's Jael has no `%dome` at all: it says 500 or 404,
 * or answers some page that is not a jam, which means "can't tell",
 * not "no". Any of those stops the asking for the session, and a
 * request that failed is simply asked again next time.
 *
 * A dome is what that ship's Jael holds. `~` is its answer, and not
 * proof of anything wider: a comet attested on Bitcoin that this Jael
 * does not know of reads the same.
 */
class CometDomes(
    private val http: HttpClient,
    private val baseUrl: String,
    private val db: AppDatabase,
    private val scope: kotlinx.coroutines.CoroutineScope =
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + io.nisfeb.talon.util.ioDispatcher),
) {
    @kotlin.concurrent.Volatile private var noDome = false

    /** Comets asked about, or being asked about, this session. */
    private val asked = HashSet<String>()
    private val askedLock = kotlinx.atomicfu.locks.SynchronizedObject()

    /** One question at a time: a busy ship meets one scry, not a list's worth. */
    private val oneAtATime = kotlinx.coroutines.sync.Mutex()

    init {
        // Names ask this, the first time they draw a comet; the answers
        // already kept name theirs from the start.
        Mnemonym.onComet = ::check
        scope.launch {
            runCatching { db.cometDomes().attested() }.getOrNull()?.forEach { Mnemonym.markGroundwire(it) }
        }
    }

    /**
     * Whether [comet] is on Groundwire, asked in the background and once:
     * what a comet's name asks the first time it is drawn, so it wears
     * the single dot without its profile being opened first.
     */
    fun check(comet: String) {
        if (noDome || !isComet(comet)) return
        val fresh = kotlinx.atomicfu.locks.synchronized(askedLock) { asked.add(comet) }
        if (fresh) scope.launch { oneAtATime.withLock { registry(comet) } }
    }

    /** The registry attesting [comet] (`gw-btc` for Groundwire), "" when
     *  none, or null when it is not a comet or the ship can't tell. */
    suspend fun registry(comet: String): String? {
        if (!isComet(comet)) return null
        return try {
            db.cometDomes().get(comet)?.let { return it.registry.also { r -> if (r.isNotEmpty()) Mnemonym.markGroundwire(comet) } }
            if (noDome) return null
            ask(comet)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** Whether this ship's Jael can say: false once it has shown it has no `%dome`. */
    val canTell: Boolean get() = !noDome

    /**
     * Ask again, whatever was kept: the profile's "Check again". A
     * Groundwire Jael says `~` of a comet it has not learned of yet, and
     * that was kept for good: two attested comets wore ".." on the
     * user's own Groundwire comet. On the repo's scope, so closing the
     * profile does not stop it. Null when the ship can't tell.
     */
    suspend fun recheck(comet: String): String? {
        if (!isComet(comet)) return null
        return scope.async {
            try {
                oneAtATime.withLock { ask(comet) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }.await()
    }

    /** One question to the ship's Jael, its answer kept and worn by the name. */
    private suspend fun ask(comet: String): String? {
        // Eyre's scry of Jael: care `j`, path `/dome/<ship>`.
        val resp = http.get("${baseUrl.trimEnd('/')}/_~_/=/dome/=/j/$comet")
        // Eyre answers a scry the ship does not have with a 500
        // "scry failed", for every ship alike (a Jael without
        // Groundwire's %dome), and a 404 where the path is not
        // served at all. Either way there is nothing to ask here.
        if (resp.status.value == 404 || resp.status.value == 500) { noDome = true; return null }
        if (resp.status.value != 200) return null
        val answer = registryOf(resp.readRawBytes()) ?: run { noDome = true; return null }
        db.cometDomes().put(CometDomeEntity(comet, answer))
        if (answer.isNotEmpty()) Mnemonym.markGroundwire(comet) else Mnemonym.unmarkGroundwire(comet)
        return answer
    }

    internal companion object {
        /** What a jammed `%dome` answer says: the registry's name, "" for
         *  `~`, or null when the body is not a dome answer at all. */
        fun registryOf(jam: ByteArray): String? {
            // A dome's jam is a few bytes; anything long is some other page.
            if (jam.size > 64) return null
            val noun = try { cue(jam) } catch (e: IllegalArgumentException) { return null }
            if (noun is ByteArray) return if (noun.isEmpty()) "" else null
            val (head, tail) = noun as Pair<*, *>
            if ((head as? ByteArray)?.isEmpty() != true || tail !is ByteArray || tail.isEmpty()) return null
            return tail.decodeToString()
        }

        /**
         * Urbit's `cue`: a jammed noun back from its bits. An atom comes
         * back as its little-endian bytes with no trailing zeros (so 0
         * is empty), a cell as a [Pair]. Throws IllegalArgumentException
         * on a body that is not one jam, whole: a page that happens to
         * start like one is not taken for it.
         */
        fun cue(jam: ByteArray): Any {
            val end = jam.size * 8L
            fun bit(i: Long): Boolean {
                require(i < end) { "jam ends early" }
                return (jam[(i ushr 3).toInt()].toInt() shr (i and 7).toInt()) and 1 == 1
            }
            fun atom(from: Long, n: Long): ByteArray {
                require(from + n <= end) { "atom runs past the jam" }
                val out = ByteArray(((n + 7) / 8).toInt())
                for (j in 0L until n) if (bit(from + j)) {
                    val k = (j ushr 3).toInt()
                    out[k] = (out[k].toInt() or (1 shl (j and 7).toInt())).toByte()
                }
                return out.copyOf(out.indexOfLast { it != 0.toByte() } + 1)
            }
            fun num(a: ByteArray): Long {
                require(a.size <= 7) { "length too long" }
                return a.foldRight(0L) { b, acc -> (acc shl 8) or (b.toLong() and 0xFF) }
            }
            // mat: a run of z zeros, then the low z-1 bits of the
            // length, whose top bit is implied, then the value itself.
            fun rub(i: Long): Pair<Long, ByteArray> {
                var z = 0
                while (!bit(i + z)) z++
                if (z == 0) return 1L to ByteArray(0)
                require(z <= 32) { "length too long" }
                val n = num(atom(i + z + 1, z - 1L)) or (1L shl (z - 1))
                return (2L * z + n) to atom(i + 2L * z, n)
            }
            val refs = HashMap<Long, Any>()
            fun go(i: Long): Pair<Long, Any> = when {
                !bit(i) -> rub(i + 1).let { (w, v) -> refs[i] = v; (1 + w) to v }
                !bit(i + 1) -> {
                    val (wh, h) = go(i + 2)
                    val (wt, t) = go(i + 2 + wh)
                    val cell = h to t
                    refs[i] = cell
                    (2 + wh + wt) to cell
                }
                else -> rub(i + 2).let { (w, at) -> (2 + w) to requireNotNull(refs[num(at)]) { "bad backref" } }
            }
            val (width, noun) = go(0)
            // A jam ends on a set bit, so its bytes are exactly its bits.
            require((width + 7) / 8 == jam.size.toLong()) { "not one jam" }
            return noun
        }
    }
}

/** The signed-in ship's [CometDomes], or null before there is one. */
val LocalCometDomes = staticCompositionLocalOf<CometDomes?> { null }

/**
 * Under a comet's name: what the ship's Jael says of it, and "Check
 * again", which asks afresh ("add a manual recheck on the profile
 * interface for comets only"). Nothing where the Jael cannot say.
 */
@androidx.compose.runtime.Composable
fun GroundwireLine(ship: String) {
    val domes = LocalCometDomes.current ?: return
    if (!isComet(ship)) return
    var registry by androidx.compose.runtime.remember(ship, domes) { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var checking by androidx.compose.runtime.remember(ship, domes) { androidx.compose.runtime.mutableStateOf(true) }
    androidx.compose.runtime.LaunchedEffect(ship, domes) { registry = domes.registry(ship); checking = false }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val r = registry
    if (!checking && r == null && !domes.canTell) return
    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        androidx.compose.material3.Text(
            when {
                checking -> "Asking your ship about Groundwire…"
                r == null -> "Your ship did not answer about Groundwire."
                r.isNotEmpty() -> "Groundwire comet"
                else -> "Not on Groundwire, as your ship knows it"
            },
            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
            color = if (!r.isNullOrEmpty() && !checking) androidx.compose.material3.MaterialTheme.colorScheme.primary
            else androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
            enabled = !checking,
            onClick = {
                checking = true
                scope.launch { registry = domes.recheck(ship); checking = false }
            },
        ) { androidx.compose.material3.Text("Check again") }
    }
}
