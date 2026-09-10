package io.nisfeb.talon.mail

import io.ktor.client.HttpClient
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The auspex mail API on the viewer's own ship.
 *
 * Auspex is a grubbery nexus, not a Gall agent, so none of this rides
 * the eyre channel: it is plain JSON over HTTP under one path, and the
 * session cookie Talon already holds is the whole of the authorisation.
 * Every route is owner-only, so this only ever reads our own mailbox.
 *
 * Construct one per session; [baseUrl] is that session's ship.
 */
class AuspexApi(
    private val http: HttpClient,
    baseUrl: String,
) {
    private val root = baseUrl.trimEnd('/') + APP_PATH

    // ---- reads ---------------------------------------------------------

    /** Our own @p, per the ship. Also the cheapest proof the session is
     *  live, since auspex has no unauthenticated surface at all. */
    suspend fun whoami(): String =
        decode<Whoami>(request(HttpMethod.Get, "/api/whoami")).ship

    /**
     * One page of a view. [limit] is capped at 200 by the ship, and a
     * limit of zero is an empty page rather than "everything".
     */
    suspend fun inbox(
        view: MailView = MailView.INBOX,
        label: String? = null,
        query: String? = null,
        offset: Int = 0,
        limit: Int = DEFAULT_PAGE,
    ): InboxPage {
        val q = buildString {
            append("?view=").append(view.wire)
            label?.let { append("&label=").append(it.encodeURLParameter()) }
            query?.let { append("&q=").append(it.encodeURLParameter()) }
            if (offset > 0) append("&offset=").append(offset)
            append("&limit=").append(limit)
        }
        return decode(request(HttpMethod.Get, "/api/inbox$q"))
    }

    /** One thread, or null when it is gone. Every other failure throws:
     *  a deleted thread and a failed load are different things to show. */
    suspend fun thread(id: String): MailThread? = try {
        decode<MailThread>(request(HttpMethod.Get, "/api/thread/${id.encodeURLParameter()}"))
    } catch (e: AuspexError.Refused) {
        if (e.status == NOT_FOUND) null else throw e
    }

    // ---- writes --------------------------------------------------------

    /**
     * Compose, reply and forward: one request, differing only in [prev].
     * A compose passes null, a reply passes the message it answers, and
     * a forward passes the message being forwarded — which is what makes
     * the chain a forward carries the path from the root to that message
     * and nothing from its sibling branches.
     *
     * Answering does not mean it applied. The route replies as soon as
     * the ship's single writer accepts the poke, and a few refusals never
     * surface over HTTP at all, so a send is confirmed by refetching and
     * seeing it, never by this returning.
     */
    suspend fun send(
        to: List<String>,
        subject: String,
        body: String,
        prev: String? = null,
        attachments: List<AttachRef> = emptyList(),
    ) {
        request(
            HttpMethod.Post,
            "/api/send",
            json.encodeToString(SendReq.serializer(), SendReq(to, subject, body, prev, attachments)),
        )
    }

    /** Move a thread in or out of the archive. Local state: no other
     *  ship can see it, so the client that changed it refreshes. */
    suspend fun setArchived(threadId: String, archived: Boolean) {
        request(
            HttpMethod.Post,
            "/api/archive",
            json.encodeToString(ArchiveReq.serializer(), ArchiveReq(threadId, archived)),
        )
    }

    suspend fun deleteThread(threadId: String) {
        request(
            HttpMethod.Post,
            "/api/delete-thread",
            json.encodeToString(ThreadReq.serializer(), ThreadReq(threadId)),
        )
    }

    /**
     * Ask the network for an attachment's bytes. [from] is a hint only:
     * any ship holding them may serve them, and the hash proves them.
     * Nothing pushes the answer, so the caller polls [blob].
     */
    suspend fun fetchBlob(hash: String, from: String) {
        request(
            HttpMethod.Post,
            "/api/fetch-blob",
            json.encodeToString(FetchBlobReq.serializer(), FetchBlobReq(hash, from)),
        )
    }

    /**
     * An attachment's bytes, or null when this ship holds no copy yet.
     *
     * Null is not "no such file". Bytes are never pushed, so a file on a
     * message we hold and have not pulled is the ordinary state of an
     * inbound one, and the answer to it is a fetch control rather than
     * an error.
     *
     * The filename comes back in the header and NOT from the message.
     * The name on an attachment is signed, which proves the author chose
     * it and not that it is safe to write to a disk; the ship's
     * sanitiser is what makes it a filename, and it runs on that header.
     */
    suspend fun blob(hash: String, name: String, mime: String): Blob? {
        val url = root + "/api/blob/" + hash.encodeURLParameter() +
            "?name=" + name.encodeURLParameter() + "&mime=" + mime.encodeURLParameter()
        val resp = try {
            http.request(url) { this.method = HttpMethod.Get }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            throw AuspexError.Unreachable(t)
        }
        if (resp.status.value == NOT_FETCHED) return null
        if (!resp.status.isSuccess()) {
            throw AuspexError.Refused(resp.status.value, reasonOf(runCatching { resp.bodyAsText() }.getOrDefault("")))
        }
        val bytes = try {
            resp.readRawBytes()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            throw AuspexError.Garbled(t)
        }
        return Blob(bytes, dispositionName(resp.headers["content-disposition"]) ?: hash)
    }

    /** Mark messages read. A set per request, deliberately: one id per
     *  request costs the ship a mailbox scan each, on one fiber. */
    suspend fun markRead(msgIds: List<String>) = mark("/api/read", msgIds)

    suspend fun markUnread(msgIds: List<String>) = mark("/api/unread", msgIds)

    private suspend fun mark(path: String, msgIds: List<String>) {
        if (msgIds.isEmpty()) return
        request(HttpMethod.Post, path, json.encodeToString(MarkReq.serializer(), MarkReq(msgIds)))
    }

    // ---- plumbing ------------------------------------------------------

    /**
     * One request, with the three outcomes kept apart: the ship refused,
     * the ship answered something we cannot read, or nothing came back.
     * A user can act on the first, should be told the second, and can
     * retry the third; collapsing them into one failure loses that.
     */
    private suspend fun request(
        method: HttpMethod,
        path: String,
        body: String? = null,
    ): String {
        val resp = try {
            http.request(root + path) {
                this.method = method
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            throw AuspexError.Unreachable(t)
        }
        // Past here the ship answered: headers arrived, which it could not
        // have sent without receiving the request.
        val text = try {
            resp.bodyAsText()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            throw AuspexError.Garbled(t)
        }
        if (!resp.status.isSuccess()) throw AuspexError.Refused(resp.status.value, reasonOf(text))
        return text
    }

    /** The ship's own words for a refusal. Every route answers JSON,
     *  errors included, so this is the reason rather than a guess. */
    private fun reasonOf(text: String): String = runCatching {
        (json.parseToJsonElement(text) as JsonObject)["error"]?.jsonPrimitive?.content
    }.getOrNull() ?: text.take(200).ifBlank { "no reason given" }

    private inline fun <reified T> decode(text: String): T = try {
        json.decodeFromString<T>(text)
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        throw AuspexError.Garbled(t)
    }

    companion object {
        /** Where grubbery binds the nexus. */
        const val APP_PATH = "/apps/auspex"
        const val DEFAULT_PAGE = 50

        /** A thread or draft that is gone, which is not a load failure. */
        const val NOT_FOUND = 404

        /** The session is over. It fails instantly and no retry fixes it. */
        const val FORBIDDEN = 403

        /** Bytes we hold no copy of yet. Not the same as "no such file":
         *  attachments are never pushed, so this is the ordinary state of
         *  an inbound one until it is asked for. */
        const val NOT_FETCHED = 409

        /** A blob over the ship's cap. */
        const val TOO_LARGE = 413

        /**
         * `explicitNulls` matters here: the ship's decoders require every
         * listed key and refuse a body missing one, so a compose has to
         * send its null parent rather than omit it.
         */
        internal val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = true
            encodeDefaults = true
        }
    }
}

// ---- errors ------------------------------------------------------------

/** Why a call did not produce an answer we can use. */
sealed class AuspexError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The ship answered and said no, in its own words. */
    class Refused(val status: Int, val reason: String) : AuspexError("HTTP $status: $reason")

    /** The ship answered and the answer was unreadable. It did receive
     *  the request, which is the part worth reporting. */
    class Garbled(cause: Throwable) : AuspexError("unreadable answer from the ship", cause)

    /** Nothing came back at all. */
    class Unreachable(cause: Throwable) : AuspexError("no answer from the ship", cause)
}

/** The session died. Stop polling and say so; retrying never helps. */
val AuspexError.isSignedOut: Boolean
    get() = this is AuspexError.Refused && status == AuspexApi.FORBIDDEN

// ---- the wire ----------------------------------------------------------

/**
 * What the ship concluded about a message's signature. Verification
 * happens on the ship, against jael's keys, before anything is stored;
 * the HTTP shape carries no signature, so a client renders this rather
 * than checking the work.
 */
enum class Verdict(val wire: String) {
    /** The key was held and the signature checked. */
    VERIFIED("verified"),

    /** No key for that ship at that life — a moon, a comet, a rotation.
     *  Never an accusation, and the safe reading of anything unknown. */
    UNVERIFIED("unverified"),

    /** The key was held and the signature failed. Shown, never hidden. */
    FORGED("forged");

    companion object {
        /** Anything unrecognised reads as unverified. A value we cannot
         *  place must never be rendered as a claim of authenticity. */
        fun fromWire(s: String): Verdict = entries.firstOrNull { it.wire == s } ?: UNVERIFIED
    }
}

internal object VerdictSerializer : KSerializer<Verdict> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Verdict", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Verdict = Verdict.fromWire(decoder.decodeString())
    override fun serialize(encoder: Encoder, value: Verdict) = encoder.encodeString(value.wire)
}

/** Which slice of the mailbox a listing walks. */
enum class MailView(val wire: String) {
    INBOX("inbox"),
    SENT("sent"),
    ARCHIVED("archived"),
    ALL("all"),
    LABEL("label"),
}

/**
 * One attached file as the message describes it. Metadata only: the
 * bytes live at [hash] and are fetched separately.
 *
 * Every field is inside the signature, which proves the author chose it
 * and nothing more. Only [hash] is checkable, because bytes either hash
 * to it or are discarded. [name] and [mime] are claims: never pick a
 * renderer from [mime], and never write [name] to disk — the ship
 * sanitises a filename into the download header, and that is the one to
 * use.
 */
@Serializable
data class Attachment(
    val name: String = "",
    val size: Long = 0,
    val mime: String = "",
    val hash: String = "",
)

@Serializable
data class MailMessage(
    val id: String,
    val from: String,
    val to: List<String> = emptyList(),
    val subject: String = "",
    val body: String = "",
    /** The author's rendering instruction, signed and so unalterable in
     *  transit. Empty means plain text. Reported, not obeyed: match it
     *  against an allow-list and fall back to plain text. */
    @SerialName("body-mime") val bodyMime: String = "",
    /** The author's clock, in milliseconds. Display and ordering only —
     *  an attacker controls it, so nothing about identity or filing may
     *  derive from it. */
    val sent: Long = 0,
    /** The message this one answers. What makes a flat list a chain and
     *  a thread a tree. */
    val prev: String? = null,
    /** Absent on messages written before the field existed, which is
     *  exactly a message with no attachments we can name. */
    val attachments: List<Attachment> = emptyList(),
    @Serializable(with = VerdictSerializer::class) val verdict: Verdict = Verdict.UNVERIFIED,
    val read: Boolean = false,
)

@Serializable
data class MailThread(
    val id: String,
    val messages: List<MailMessage> = emptyList(),
    val participants: List<String> = emptyList(),
    val last: Long = 0,
    /** Copies stored on the ship this build cannot read: written under an
     *  older shape, which the nexus refuses rather than relabelling,
     *  because rewriting a message breaks the signature that makes it
     *  evidence. Reported so a short thread says why. */
    val unreadable: Int = 0,
    val archived: Boolean = false,
    val labels: List<String> = emptyList(),
)

/** One row of a listing. The verdict is here and not only inside the
 *  thread because the row is the surface people scan fastest. */
@Serializable
data class InboxEntry(
    val id: String,
    val subject: String = "",
    val from: String = "",
    val snippet: String = "",
    @Serializable(with = VerdictSerializer::class) val verdict: Verdict = Verdict.UNVERIFIED,
    /** The thread holds at least one forged copy, whether or not the
     *  summary above was drawn from it. */
    val forged: Boolean = false,
    /** Stored copies, which is not the same as distinct messages. */
    val count: Int = 0,
    val last: Long = 0,
    val unread: Boolean = false,
    val participants: List<String> = emptyList(),
    /** A row with no readable copy still gets a row: a thread silently
     *  vanishing from the listing is the failure this field prevents. */
    val unreadable: Int = 0,
    val archived: Boolean = false,
    val labels: List<String> = emptyList(),
)

@Serializable
data class InboxPage(
    /** The whole view, not this page. */
    val total: Int = 0,
    val offset: Int = 0,
    val limit: Int = 0,
    val view: String = "",
    val threads: List<InboxEntry> = emptyList(),
)

/**
 * An attachment on its way into a send: what we claim about it, plus the
 * address the upload answered with. No bytes and no size — the ship
 * reads the length off the stored blob and signs it from there, so a
 * client cannot make a signature claim a length the bytes do not have.
 */
@Serializable
data class AttachRef(
    val name: String,
    val mime: String,
    val hash: String,
)

@Serializable
private data class Whoami(val ship: String = "")

@Serializable
private data class SendReq(
    val to: List<String>,
    val subject: String,
    val body: String,
    val prev: String?,
    val attachments: List<AttachRef> = emptyList(),
)

@Serializable
private data class MarkReq(@SerialName("msg-ids") val msgIds: List<String>)

@Serializable
private data class ArchiveReq(@SerialName("thread-id") val threadId: String, val archived: Boolean)

@Serializable
private data class ThreadReq(@SerialName("thread-id") val threadId: String)

@Serializable
private data class FetchBlobReq(val hash: String, val from: String)

/** Bytes, and the name the SHIP chose for them. */
class Blob(val bytes: ByteArray, val name: String)

/**
 * The filename out of a Content-Disposition header, or null when it is
 * absent or not the shape auspex sends. The ship's sanitiser has
 * already run on this value; the message's own name has not.
 */
internal fun dispositionName(header: String?): String? {
    val h = header ?: return null
    val marker = "filename=\""
    val start = h.indexOf(marker)
    if (start < 0) return null
    val from = start + marker.length
    val end = h.indexOf('"', from)
    if (end < 0) return null
    return h.substring(from, end).ifBlank { null }
}
