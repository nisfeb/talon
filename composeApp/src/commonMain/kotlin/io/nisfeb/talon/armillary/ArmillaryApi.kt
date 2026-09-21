package io.nisfeb.talon.armillary

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Armillary's HTTP API on the buyer's own ship, at `/apps/armillary/api`.
 *
 * One client, the owner's, since every route here is the customer half
 * and the ship answers it over the session cookie. The ship talks to
 * the vendor ship over ames; nothing in Talon ever reaches the vendor
 * directly, and no key or cookie is ever logged.
 */
class ArmillaryApi(
    private val owner: HttpClient,
    baseUrl: String,
) {
    private val root = baseUrl.trimEnd('/') + APP_PATH

    /** Whether armillary answers on this ship, by the cheapest owner read. */
    suspend fun probe(): ArmillaryAvailability {
        val resp = send("/api/account") { method = HttpMethod.Get }
        return when {
            resp.status.isSuccess() -> ArmillaryAvailability.PRESENT
            resp.status.value == NOT_FOUND -> ArmillaryAvailability.MISSING
            resp.status.value == FORBIDDEN -> ArmillaryAvailability.SIGNED_OUT
            else -> throw ArmillaryError.Refused(resp.status.value, reasonOf(reading { resp.bodyAsText() }))
        }
    }

    /**
     * What a client needs to run: the mode, the base to call, the key
     * and the models on offer. A ship holding no key yet answers 404
     * with `no key yet`, which is [InferenceAnswer.NoKey] and not a
     * failure: minting one is the next step.
     */
    suspend fun inference(): InferenceAnswer {
        val resp = send("/api/inference") { method = HttpMethod.Get }
        val text = reading { resp.bodyAsText() }
        if (resp.status.value == NOT_FOUND) {
            return if (NO_KEY in reasonOf(text)) InferenceAnswer.NoKey else InferenceAnswer.Missing
        }
        if (!resp.status.isSuccess()) throw ArmillaryError.Refused(resp.status.value, reasonOf(text))
        return InferenceAnswer.Have(inferenceOf(reading { Json.parseToJsonElement(text).jsonObject }))
    }

    /**
     * This ship's account as it last read it from the vendor. [fresh]
     * asks the ship to peek the vendor first, which it waits up to
     * thirty seconds for.
     */
    suspend fun account(fresh: Boolean = false): Account =
        accountOf(reading { Json.parseToJsonElement(request(HttpMethod.Get, if (fresh) "/api/account?fresh=1" else "/api/account")).jsonObject })

    /**
     * Who this ship buys from. The ship says hello to a fresh vendor at
     * once, which is what opens the account over there.
     */
    suspend fun setVendor(ship: String) {
        request(HttpMethod.Put, "/api/vendor", buildJsonObject { put("ship", ship) }.toString())
    }

    /** The vendor's plans, read live through the ship. */
    suspend fun plans(): List<Plan> =
        plansOf(reading { Json.parseToJsonElement(request(HttpMethod.Get, "/api/plans")) })

    /** The vendor's public catalog with its prices, read live through the ship. */
    suspend fun catalog(): List<CatalogRow> =
        catalogOf(reading { Json.parseToJsonElement(request(HttpMethod.Get, "/api/catalog")) })

    /**
     * Open a checkout with the vendor. One of [plan] and [amountMicro],
     * never both. The ship waits thirty seconds for the vendor's row and
     * then answers the nonce instead, which is not a failure.
     */
    suspend fun checkout(rail: String, plan: String?, amountMicro: Long?): CheckoutAnswer {
        val body = buildJsonObject {
            put("rail", rail)
            plan?.takeIf { it.isNotBlank() }?.let { put("plan", it) }
            amountMicro?.takeIf { it > 0 }?.let { put("amount", it) }
        }
        val resp = send("/api/checkout") {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        val text = reading { resp.bodyAsText() }
        return when {
            resp.status.value == ACCEPTED ->
                CheckoutAnswer.Pending(reading { Json.parseToJsonElement(text).jsonObject }.str("nonce").orEmpty())
            resp.status.isSuccess() -> {
                val o = reading { Json.parseToJsonElement(text).jsonObject }
                CheckoutAnswer.Url(o.str("url").orEmpty(), o.str("nonce").orEmpty())
            }
            resp.status.value == BAD_GATEWAY -> CheckoutAnswer.Refused(reasonOf(text))
            else -> throw ArmillaryError.Refused(resp.status.value, reasonOf(text))
        }
    }

    /**
     * Ask the vendor for an inference key. True when the secret came
     * back inside the wait; 202 is false, meaning the op is queued and
     * the next pass will land it.
     */
    suspend fun mintKey(name: String): Boolean {
        val resp = send("/api/keys") {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("name", name) }.toString())
        }
        val text = reading { resp.bodyAsText() }
        if (resp.status.value == ACCEPTED) return false
        if (!resp.status.isSuccess()) throw ArmillaryError.Refused(resp.status.value, reasonOf(text))
        return true
    }

    /**
     * Ask the vendor for a direct provider lease. False where there is
     * none to be had: a vendor without leases and a desk older than the
     * lease routes both answer 404 or 501, and neither is an error.
     */
    suspend fun lease(): Boolean {
        val resp = send("/api/lease") { method = HttpMethod.Post }
        val text = reading { resp.bodyAsText() }
        if (resp.status.value == NOT_FOUND || resp.status.value == NOT_IMPLEMENTED) return false
        if (!resp.status.isSuccess()) throw ArmillaryError.Refused(resp.status.value, reasonOf(text))
        return true
    }

    /** Give the lease back. A ship with no lease routes says so and nothing happens. */
    suspend fun dropLease() {
        val resp = send("/api/lease") { method = HttpMethod.Delete }
        val text = reading { resp.bodyAsText() }
        if (resp.status.value == NOT_FOUND || resp.status.value == NOT_IMPLEMENTED) return
        if (!resp.status.isSuccess()) throw ArmillaryError.Refused(resp.status.value, reasonOf(text))
    }

    /** Ask the vendor to stop the subscription renewing. The view says when it is done. */
    suspend fun cancelSubscription() {
        request(HttpMethod.Post, "/api/cancel-subscription")
    }

    private suspend fun request(
        method: HttpMethod,
        path: String,
        body: String? = null,
    ): String {
        val resp = send(path) {
            this.method = method
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        val text = reading { resp.bodyAsText() }
        if (!resp.status.isSuccess()) throw ArmillaryError.Refused(resp.status.value, reasonOf(text))
        return text
    }

    /** Anything that stops a request arriving is [ArmillaryError.Unreachable]. */
    private suspend fun send(path: String, build: HttpRequestBuilder.() -> Unit): HttpResponse =
        try {
            owner.request(root + path, build)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            throw ArmillaryError.Unreachable(t)
        }

    private inline fun <T> reading(block: () -> T): T = try {
        block()
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        throw ArmillaryError.Garbled(t)
    }

    private fun reasonOf(text: String): String = armillaryReason(text)

    companion object {
        const val APP_PATH = "/apps/armillary"
        private const val NO_KEY = "no key yet"
        private const val ACCEPTED = 202
        private const val NOT_FOUND = 404
        private const val FORBIDDEN = 403
        private const val BAD_GATEWAY = 502
        private const val NOT_IMPLEMENTED = 501
    }
}

enum class ArmillaryAvailability {
    /** Not probed yet this session. */
    UNKNOWN,

    /** Armillary answered. */
    PRESENT,

    /** Nothing at its path: armillary is not installed, or Grubbery is not. */
    MISSING,

    /** The ship refused the cookie. */
    SIGNED_OUT,
}

sealed class ArmillaryError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Refused(val status: Int, val reason: String) : ArmillaryError("HTTP $status: $reason")
    class Unreachable(cause: Throwable) : ArmillaryError("no answer from the ship", cause)
    class Garbled(cause: Throwable) : ArmillaryError("the ship's answer could not be read", cause)
}

/** What a client needs to run, as `GET /api/inference` answers it. */
data class Inference(
    /** `lease` when the key is the model provider's own, `proxy` when the vendor forwards. */
    val mode: String,
    val baseUrl: String,
    val key: String,
    val models: List<String>,
)

sealed class InferenceAnswer {
    data class Have(val inference: Inference) : InferenceAnswer()

    /** This ship holds no key yet. Minting one is what comes next. */
    data object NoKey : InferenceAnswer()

    /** No such route: armillary is not on this ship after all. */
    data object Missing : InferenceAnswer()
}

/** One checkout the vendor has written into this ship's view. */
data class Checkout(
    val nonce: String,
    val url: String,
    /** `pending`, `processing`, `paid`, `failed`, `expired` or `refused`. */
    val status: String,
    val rail: String,
    val amountMicro: Long,
    /** Why the vendor refused it, on a `refused` row; blank otherwise. */
    val note: String = "",
)

/** This ship's account with its vendor, as the ship last read it. */
data class Account(
    /**
     * Whether the ship has read a view from the vendor at all. False
     * until the hello has landed, when the balance below means nothing.
     */
    val hasView: Boolean,
    val balanceMicro: Long,
    val plan: String,
    val subscriptionActive: Boolean,
    /** When the subscription renews, as the vendor said it; null when there is none. */
    val renews: String?,
    /** The vendor ship, or blank where none is set. */
    val vendor: String,
    /** Seconds since the ship last read the vendor's view. */
    val stale: Long,
    val leaseHeld: Boolean,
    /** The vendor has a lease for us but will not serve it, an empty balance being the usual reason. */
    val leaseDisabled: Boolean,
    val checkouts: List<Checkout>,
)

/** One of the vendor's plans, top-up or subscription. */
data class Plan(
    val id: String,
    val name: String,
    /** `topup` or `subscription`. */
    val kind: String,
    val priceMicro: Long,
    val creditMicro: Long,
    /** `month` or `year` on a subscription, blank on a top-up. */
    val interval: String,
)

/** One model the vendor sells, at the vendor's prices per million tokens. */
data class CatalogRow(
    val id: String,
    val provider: String,
    val inMicro: Long,
    val outMicro: Long,
    val tags: List<String>,
) {
    val zdr: Boolean get() = tags.any { it.equals("zdr", ignoreCase = true) }
}

sealed class CheckoutAnswer {
    /** The page to send the person to, and the nonce its row in the view carries. */
    data class Url(val url: String, val nonce: String = "") : CheckoutAnswer()

    /** The vendor had not answered inside the ship's wait. The op is still queued. */
    data class Pending(val nonce: String) : CheckoutAnswer()

    /** The vendor refused, and said which field was wrong. */
    data class Refused(val reason: String) : CheckoutAnswer()
}

// ── readers ────────────────────────────────────────────────────────
//
// Hand-rolled tree walking, as the orrery client does: the ship's
// documents grow fields between versions, and a reader that ignores
// what it does not know keeps working when they do.

private fun JsonObject.str(k: String): String? = (get(k) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonObject.num(k: String): Long = (get(k) as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L

private fun JsonObject.bool(k: String): Boolean = (get(k) as? JsonPrimitive)?.booleanOrNull ?: false

private fun JsonObject.obj(k: String): JsonObject = get(k) as? JsonObject ?: JsonObject(emptyMap())

private fun texts(e: kotlinx.serialization.json.JsonElement?): List<String> =
    (e as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

internal fun inferenceOf(o: JsonObject) = Inference(
    mode = o.str("mode") ?: "proxy",
    baseUrl = o.str("base_url").orEmpty(),
    key = o.str("key").orEmpty(),
    models = texts(o["models"]),
)

internal fun checkoutOf(o: JsonObject) = Checkout(
    nonce = o.str("nonce").orEmpty(),
    url = o.str("url").orEmpty(),
    status = o.str("status").orEmpty(),
    rail = o.str("rail").orEmpty(),
    amountMicro = o.num("amount"),
    note = o.str("note").orEmpty(),
)

internal fun accountOf(o: JsonObject): Account {
    val sub = o.obj("subscription")
    val lease = o.obj("lease")
    // The vendor keys its checkout rows by nonce; an older document, or
    // a page that flattened them, sends a list. Both read the same.
    val rows = when (val c = o["checkouts"]) {
        is JsonObject -> c.values.mapNotNull { it as? JsonObject }
        is JsonArray -> c.mapNotNull { it as? JsonObject }
        else -> emptyList()
    }
    return Account(
        hasView = o.containsKey("balance"),
        balanceMicro = o.num("balance"),
        plan = o.str("plan").orEmpty(),
        subscriptionActive = sub.bool("active"),
        renews = sub.str("renews"),
        vendor = o.str("vendor").orEmpty(),
        stale = o.num("stale"),
        leaseHeld = lease.bool("held"),
        leaseDisabled = lease.bool("disabled"),
        checkouts = rows.map(::checkoutOf),
    )
}

internal fun plansOf(e: kotlinx.serialization.json.JsonElement): List<Plan> {
    val rows = when (e) {
        is JsonArray -> e.mapNotNull { it as? JsonObject }
        is JsonObject -> e.values.mapNotNull { it as? JsonObject }
        else -> emptyList()
    }
    return rows.mapNotNull { o ->
        val id = o.str("id") ?: return@mapNotNull null
        Plan(
            id = id,
            name = o.str("name") ?: id,
            kind = o.str("kind") ?: "topup",
            priceMicro = o.num("price"),
            creditMicro = o.num("credit"),
            interval = o.str("interval").orEmpty(),
        )
    }
}

internal fun catalogOf(e: kotlinx.serialization.json.JsonElement): List<CatalogRow> {
    val rows = when (e) {
        is JsonArray -> e.mapNotNull { it as? JsonObject }
        is JsonObject -> e.values.mapNotNull { it as? JsonObject }
        else -> emptyList()
    }
    return rows.mapNotNull { o ->
        val id = o.str("id") ?: return@mapNotNull null
        CatalogRow(
            id = id,
            provider = o.str("provider").orEmpty(),
            inMicro = o.num("in"),
            outMicro = o.num("out"),
            tags = texts(o["tags"]),
        )
    }
}

/** The ship's own words about a refusal, where it gave any. */
internal fun armillaryReason(text: String): String =
    runCatching { Json.parseToJsonElement(text).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content }.getOrNull()
        ?: runCatching { Json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
        ?: text.take(160).ifBlank { "no reason given" }

/**
 * Microdollars as money, to the cent. Under half a cent is said in
 * words rather than as `$0.00`, the way the AI settings screen says a
 * month's spend.
 */
fun money(micro: Long): String {
    if (micro < 5_000L) return "under a cent"
    val cents = (micro + 5_000L) / 10_000L
    return "$" + (cents / 100) + "." + (cents % 100).toString().padStart(2, '0')
}
