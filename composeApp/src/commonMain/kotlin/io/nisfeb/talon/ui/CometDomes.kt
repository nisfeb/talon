package io.nisfeb.talon.ui

import androidx.compose.runtime.staticCompositionLocalOf
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.CometDomeEntity
import kotlinx.coroutines.CancellationException

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
) {
    private var noDome = false

    /** The registry attesting [comet] (`gw-btc` for Groundwire), "" when
     *  none, or null when it is not a comet or the ship can't tell. */
    suspend fun registry(comet: String): String? {
        if (!isComet(comet)) return null
        return try {
            db.cometDomes().get(comet)?.let { return it.registry }
            if (noDome) return null
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
            answer
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
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

/** "Groundwire comet" under a comet's name, where the ship's Jael says it is one. */
@androidx.compose.runtime.Composable
fun GroundwireLine(ship: String) {
    val domes = LocalCometDomes.current
    val registry = androidx.compose.runtime.produceState<String?>(null, ship, domes) { value = domes?.registry(ship) }.value
    if (!registry.isNullOrEmpty()) {
        androidx.compose.material3.Text(
            "Groundwire comet",
            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
            color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
        )
    }
}
