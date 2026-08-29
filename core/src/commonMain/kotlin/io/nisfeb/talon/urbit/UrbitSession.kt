package io.nisfeb.talon.urbit
import kotlin.concurrent.Volatile

import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.Url
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import io.nisfeb.talon.util.ioDispatcher
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.withContext

/**
 * Coerce a user-typed ship URL to a well-formed absolute URL string.
 * If no scheme is present, prepend `https://` — matches the
 * security-preferred posture and means users only have to type
 * `http://` when they explicitly want cleartext (e.g. a LAN ship).
 * Trailing slashes are stripped so the saved-session entry is stable.
 */
internal fun normalizeShipUrl(input: String): String {
    val trimmed = input.trim().trimEnd('/')
    val lower = trimmed.lowercase()
    return if (lower.startsWith("http://") || lower.startsWith("https://")) {
        trimmed
    } else {
        "https://$trimmed"
    }
}

/**
 * Holds the session cookie for one authenticated Urbit ship and owns the
 * HttpClient used for channel traffic. Call login() once; afterwards
 * openChannel() returns an UrbitChannel configured with this session's
 * base URL and cookie storage.
 *
 * Not thread-safe across login/logout, but concurrent channel use is fine.
 */
class UrbitSession(
    parentClient: HttpClient,
    private val store: SessionStore,
) {

    private val cookieStorage = SessionCookieStorage()

    /**
     * A cookie-scoped derivative of the shared client, sharing its
     * engine. HttpCookies captures the urbauth-~ cookie on login and
     * replays it on every channel/scry/poke; SSE backs the channel
     * event stream.
     */
    val http: HttpClient = parentClient.config {
        install(HttpCookies) { storage = cookieStorage }
    }

    @Volatile var baseUrl: String? = null
        private set
    @Volatile var shipName: String? = null
        private set

    /**
     * Authenticates against shipUrl (e.g. "https://mything.arvo.network" or
     * "http://localhost:8080") using +code. Strips dashes from the code
     * before POSTing. Returns Result.success(ship) on success.
     *
     * If `shipUrl` has no scheme, `https://` is assumed — users only have
     * to type `http://` when they explicitly want cleartext.
     */
    suspend fun login(shipUrl: String, code: String): Result<String> =
        withContext(ioDispatcher) {
            runCatching {
                cookieStorage.clear()
                val url = normalizeShipUrl(shipUrl)
                // Urbit's /~/login takes `password=<code>` with dashes intact.
                // Accept a leading `+` from users who paste verbatim from +code.
                val resp = http.submitForm(
                    url = "$url/~/login",
                    formParameters = parameters {
                        append("password", code.trim().removePrefix("+"))
                    },
                )
                if (!resp.status.isSuccess()) {
                    // Drain the body so the engine can recycle the connection.
                    resp.bodyAsText()
                    error("login HTTP ${resp.status.value}")
                }
                val cookie = cookieStorage.snapshot()
                    .firstOrNull { it.name.startsWith("urbauth-~") }
                    ?: error("no urbauth cookie returned")
                // Store with the leading ~ intact so post IDs and DmAction.ship
                // match Tlon's wire format without client reconstruction.
                val ship = cookie.name.removePrefix("urbauth-")
                baseUrl = url
                shipName = ship
                store.save(
                    SavedSession(
                        shipUrl = url,
                        ship = ship,
                        cookieName = cookie.name,
                        cookieValue = cookie.value,
                        cookieDomain = Url(url).host,
                    ),
                    makeActive = true,
                )
                ship
            }
        }

    /**
     * Sign out the currently active ship. Removes just its entry from
     * the session list — other saved ships stay.
     */
    fun logout() {
        val s = shipName
        baseUrl = null
        shipName = null
        cookieStorage.clear()
        if (s != null) store.remove(s) else store.clearAll()
    }

    /**
     * Restore the currently-active saved session, if any. Returns the
     * ship patp on success. Doesn't verify the cookie with the server —
     * call a cheap scry after if you need that.
     */
    fun tryRestore(ship: String? = null): String? {
        val saved = if (ship != null) {
            store.all().firstOrNull { it.ship == ship }
        } else store.active()
        if (saved == null) return null
        // Fresh cookie context so no stale cookies from a prior ship's
        // session bleed into the new host's requests.
        cookieStorage.clear()
        cookieStorage.add(
            Cookie(
                name = saved.cookieName,
                value = saved.cookieValue,
                domain = saved.cookieDomain,
                path = "/",
                encoding = CookieEncoding.RAW,
            ),
        )
        baseUrl = saved.shipUrl.trimEnd('/')
        shipName = saved.ship
        store.setActive(saved.ship)
        return saved.ship
    }

    /** Open a new channel connection. Caller owns the returned instance. */
    fun openChannel(): UrbitChannel {
        val base = baseUrl ?: error("not logged in")
        // Eyre's action JSON wants the bare patp (no leading ~); strip it here
        // so the channel instance has a wire-ready value for subscribe/poke.
        val bareShip = (shipName ?: error("no ship")).removePrefix("~")
        return UrbitChannel(http, base, bareShip)
    }

    /** Our patp as Tlon wire form, e.g. "~mister-botter-dozzod-nisfeb". */
    val ourPatp: String get() = shipName ?: error("not logged in")
}

/**
 * Minimal in-memory Ktor cookie storage — no persistence, wiped on
 * logout. The session only ever talks to its own ship's host, so
 * get() returns every stored cookie regardless of request URL (the
 * urbauth cookie must ride every channel/scry/poke). Guarded because
 * concurrent HTTP calls (parallel scries, uploads, pokes) hit it at
 * once and a bare list would corrupt under concurrent structural
 * modification.
 */
internal class SessionCookieStorage : io.ktor.client.plugins.cookies.CookiesStorage {
    private val lock = SynchronizedObject()
    private val cookies = mutableListOf<Cookie>()

    override suspend fun get(requestUrl: Url): List<Cookie> =
        synchronized(lock) { cookies.toList() }

    override suspend fun addCookie(requestUrl: Url, cookie: Cookie) {
        add(cookie)
    }

    override fun close() {}

    fun add(cookie: Cookie) = synchronized(lock) {
        cookies.removeAll { it.name == cookie.name }
        cookies.add(cookie)
    }

    fun snapshot(): List<Cookie> = synchronized(lock) { cookies.toList() }

    fun clear() = synchronized(lock) { cookies.clear() }
}
