package io.nisfeb.talon.urbit

import com.ionspin.kotlin.bignum.integer.BigInteger
import com.ionspin.kotlin.bignum.integer.Sign
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Signing and checking with this ship's own key, over lattice's /sign
 * and /verify.
 *
 * The ship signs; nothing here holds a key. What is signed is a salted
 * hash of the content, so signing a file and signing a sentence are one
 * operation, and the salt keeps these signatures out of the preimage
 * space of ames packets and auspex mail — a lattice signature can never
 * be presented as one of those.
 *
 * Needs lattice 31 or later on the ship. An older one answers 404 and
 * the caller says so.
 */
class LatticeSign(private val http: HttpClient, shipUrl: String) {
    private val root = shipUrl.trimEnd('/') + "/apps/lattice"

    /** Sign [content]. The bytes go up raw: the ship hashes them. */
    suspend fun sign(content: ByteArray): SignedRecord {
        if (content.isEmpty()) error("There is nothing to sign.")
        val resp = http.post("$root/sign") {
            contentType(ContentType.Application.OctetStream)
            setBody(content)
        }
        if (resp.status.value == 404) error("This ship's Lattice is too old to sign; it needs version 31.")
        if (!resp.status.isSuccess()) error("Lattice refused to sign: ${reason(resp.bodyAsText(), resp.status.value)}")
        return recordOf(resp.bodyAsText())
    }

    suspend fun sign(text: String): SignedRecord = sign(text.encodeToByteArray())

    /**
     * Check a record. With [content], the ship also re-derives the digest
     * and refuses a record whose digest disagrees with its own content;
     * without it, this says only that the signature covers the digest as
     * given.
     */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun verify(rec: SignedRecord, content: String? = null): Verdict {
        // digest and sig are 256- and 512-bit atoms in decimal, past every
        // integer type there is, and the route reads them as JSON numbers.
        // So they go back out as the digits that came in, unquoted. life is
        // a small atom but the same JSON-number reader, and it is
        // digit-validated at parse (signedRecordIn), so it goes unquoted
        // too rather than as a string the route would have to slav apart.
        val body = buildJsonObject {
            put("ship", rec.ship)
            put("life", JsonUnquotedLiteral(rec.life))
            put("digest", JsonUnquotedLiteral(rec.digest))
            put("sig", JsonUnquotedLiteral(rec.sig))
            if (content != null) put("content", content)
        }
        val resp = http.post("$root/verify") {
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        if (resp.status.value == 404) return Verdict.No("This ship's Lattice is too old to check signatures.")
        val text = resp.bodyAsText()
        if (!resp.status.isSuccess()) return Verdict.No(reason(text, resp.status.value))
        val o = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return Verdict.No("The ship answered something we could not read.")
        val ok = o["ok"]?.jsonPrimitive?.content == "true"
        return if (ok) Verdict.Ok else Verdict.No(o["reason"]?.jsonPrimitive?.content ?: "The signature does not check out.")
    }

    private fun recordOf(body: String): SignedRecord {
        val o = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: error("The ship answered something we could not read.")
        fun field(k: String) = o[k]?.jsonPrimitive?.content ?: error("The signature came back without $k.")
        return SignedRecord(
            ship = field("ship"),
            life = field("life"),
            alg = o["alg"]?.jsonPrimitive?.content ?: "ed25519",
            salt = o["salt"]?.jsonPrimitive?.content ?: "lattice",
            digest = field("digest"),
            sig = field("sig"),
        )
    }

    private fun reason(body: String, status: Int): String =
        runCatching { Json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
            ?: body.take(120).ifBlank { "HTTP $status" }
}

/**
 * The digest a lattice signature covers for [content]: `(shaf %lattice
 * (sham content))`, the bytes read as an atom, least significant first.
 * Worked out here, so checking another ship's file never has this ship
 * sign it: getting the digest by signing made a signature of ours over
 * whatever was being checked. LatticeSignTest has vectors from a dojo.
 */
fun latticeDigest(content: ByteArray): String {
    // `sham` of an atom is `(shaf %mash atom)`.
    val sham = io.nisfeb.talon.ui.AzimuthFingerprint.shaf("mash".encodeToByteArray(), content)
    val d = io.nisfeb.talon.ui.AzimuthFingerprint.shaf("lattice".encodeToByteArray(), sham)
    return BigInteger.fromByteArray(d.reversedArray(), Sign.POSITIVE).toString()
}

/**
 * One signature, as lattice renders it. Every field is text: [digest]
 * and [sig] are decimal atoms far past any integer type, and they are
 * only ever carried and compared, never done arithmetic on.
 */
data class SignedRecord(
    val ship: String,
    val life: String,
    val alg: String,
    val salt: String,
    val digest: String,
    val sig: String,
)

sealed interface Verdict {
    data object Ok : Verdict
    data class No(val reason: String) : Verdict
}

private const val HEAD = "-----BEGIN LATTICE SIGNATURE-----"
private const val TAIL = "-----END LATTICE SIGNATURE-----"

/** A record as a block to paste into a message, a mail or a page. */
fun SignedRecord.armor(): String = buildString {
    append(HEAD).append('\n')
    append("ship: ").append(ship).append('\n')
    append("life: ").append(life).append('\n')
    append("alg: ").append(alg).append('\n')
    append("salt: ").append(salt).append('\n')
    append("digest: ").append(digest).append('\n')
    append("sig: ").append(sig).append('\n')
    append(TAIL)
}

/**
 * The record in [text], or null when there is none to read. Tolerant of
 * whatever surrounds the block, because it arrives pasted out of a
 * message. The two numbers must be digits: anything else would go up as
 * a broken request and come back a bare 400.
 */
fun signedRecordIn(text: String): SignedRecord? {
    val start = text.indexOf(HEAD)
    if (start < 0) return null
    val end = text.indexOf(TAIL, start)
    if (end < 0) return null
    val fields = text.substring(start + HEAD.length, end).lines()
        .mapNotNull { line ->
            val k = line.substringBefore(':', "").trim().lowercase()
            val v = line.substringAfter(':', "").trim()
            if (k.isEmpty() || v.isEmpty()) null else k to v
        }.toMap()
    val ship = fields["ship"] ?: return null
    val life = fields["life"]?.takeIf { it.all(Char::isDigit) } ?: return null
    val digest = fields["digest"]?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: return null
    val sig = fields["sig"]?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: return null
    return SignedRecord(
        ship = ship,
        life = life,
        alg = fields["alg"] ?: "ed25519",
        salt = fields["salt"] ?: "lattice",
        digest = digest,
        sig = sig,
    )
}
