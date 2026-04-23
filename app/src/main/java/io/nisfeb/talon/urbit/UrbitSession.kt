package io.nisfeb.talon.urbit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/**
 * Holds the session cookie for one authenticated Urbit ship and owns the
 * OkHttp client used for channel traffic. Call login() once; afterwards
 * openChannel() returns an UrbitChannel configured with this session's
 * base URL and cookie jar.
 *
 * Not thread-safe across login/logout, but concurrent channel use is fine.
 */
class UrbitSession(
    parentClient: OkHttpClient,
    private val store: SessionStore,
) {

    private val cookieJar = InMemoryCookieJar()
    val http: OkHttpClient = parentClient.newBuilder().cookieJar(cookieJar).build()

    @Volatile var baseUrl: HttpUrl? = null
        private set
    @Volatile var shipName: String? = null
        private set

    /**
     * Authenticates against shipUrl (e.g. "https://mything.arvo.network" or
     * "http://localhost:8080") using +code. Strips dashes from the code
     * before POSTing. Returns Result.success(ship) on success.
     */
    suspend fun login(shipUrl: String, code: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = shipUrl.trimEnd('/').toHttpUrl()
                // Urbit's /~/login takes `password=<code>` with dashes intact.
                // Accept a leading `+` from users who paste verbatim from +code.
                val body = FormBody.Builder()
                    .add("password", code.trim().removePrefix("+"))
                    .build()
                val request = Request.Builder()
                    .url(url.newBuilder().addPathSegments("~/login").build())
                    .post(body)
                    .build()
                http.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) error("login HTTP ${resp.code}")
                    val cookie = cookieJar.loadForRequest(url)
                        .firstOrNull { it.name.startsWith("urbauth-~") }
                        ?: error("no urbauth cookie returned")
                    // Store with the leading ~ intact so post IDs and DmAction.ship
                    // match Tlon's wire format without client reconstruction.
                    val ship = cookie.name.removePrefix("urbauth-")
                    baseUrl = url
                    shipName = ship
                    store.save(
                        SessionStore.Saved(
                            shipUrl = url.toString().trimEnd('/'),
                            ship = ship,
                            cookieName = cookie.name,
                            cookieValue = cookie.value,
                            cookieDomain = url.host,
                        )
                    )
                    ship
                }
            }
        }

    fun logout() {
        baseUrl = null
        shipName = null
        cookieJar.clear()
        store.clear()
    }

    /**
     * Restore a previously saved session, if any. Returns the ship patp
     * on success (both baseUrl/shipName and the cookie jar are now ready
     * for channel use); null if no saved session exists. Doesn't verify
     * the cookie with the server — call a cheap scry after if you need
     * that.
     */
    fun tryRestore(): String? {
        val saved = store.load() ?: return null
        val url = runCatching { saved.shipUrl.toHttpUrl() }.getOrNull() ?: return null
        val cookie = Cookie.Builder()
            .name(saved.cookieName)
            .value(saved.cookieValue)
            .domain(saved.cookieDomain)
            .path("/")
            .build()
        cookieJar.saveFromResponse(url, listOf(cookie))
        baseUrl = url
        shipName = saved.ship
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

/** Minimal in-memory cookie jar — no persistence, wiped on logout. */
private class InMemoryCookieJar : CookieJar {
    private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        store.getOrPut(url.host) { mutableListOf() }.apply {
            removeAll { existing -> cookies.any { it.name == existing.name } }
            addAll(cookies)
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        store[url.host]?.filter { it.matches(url) } ?: emptyList()

    fun clear() = store.clear()
}
