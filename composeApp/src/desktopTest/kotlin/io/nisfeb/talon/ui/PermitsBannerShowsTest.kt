package io.nisfeb.talon.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.nisfeb.talon.ui.theme.TalonTheme
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import kotlin.test.Test
import kotlin.test.assertEquals

/** The home list says when an app waits on its permissions, and takes the owner to them. */
@OptIn(ExperimentalTestApi::class)
class PermitsBannerShowsTest {
    @Test
    fun `a pending app shows the line, and it opens the permits page`() = runComposeUiTest {
        val http = HttpClient(MockEngine { req ->
            val body = when {
                req.url.encodedPath.endsWith("/asks.json") -> """[{"app":"/apps/orrery","poke":[{"road":"/sys/eyre/"}],"peek":[],"make":[]}]"""
                req.url.encodedPath.endsWith("/approved.json") -> "{}"
                else -> return@MockEngine respond("", HttpStatusCode.NotFound)
            }
            respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        })
        val opened = mutableListOf<String>()
        setContent {
            TalonTheme(darkTheme = false) {
                CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                    override fun openUri(uri: String) { opened += uri }
                }) {
                    PermitsBanner(http, "https://ship.example")
                }
            }
        }
        waitUntil(timeoutMillis = 5_000) {
            runCatching { onNodeWithText("1 app needs its permissions approved.").assertExists(); true }.getOrDefault(false)
        }
        onNodeWithText("Review").performClick()
        waitForIdle()
        // No in-app browser here: through eyre's login, which goes on to
        // the page, so a signed-out browser is not answered "forbidden".
        assertEquals(listOf("https://ship.example/~/login?redirect=/apps/grubbery/permits"), opened)
    }

    // On a phone the browser was not signed in to the ship, and grubbery
    // answered "forbidden". Where there is an in-app browser the page
    // opens there with this session, and closing it reads the approvals
    // again.
    @Test
    fun `where there is an in-app browser, the page opens there signed in, and closing it reads again`() = runComposeUiTest {
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val http = HttpClient(MockEngine { req ->
            val body = when {
                req.url.encodedPath.endsWith("/asks.json") -> { reads.incrementAndGet(); """[{"app":"/apps/orrery","poke":[{"road":"/sys/eyre/"}],"peek":[],"make":[]}]""" }
                req.url.encodedPath.endsWith("/approved.json") -> "{}"
                else -> return@MockEngine respond("", HttpStatusCode.NotFound)
            }
            respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        })
        val opened = mutableListOf<String>()
        setContent {
            TalonTheme(darkTheme = false) {
                CompositionLocalProvider(
                    LocalUriHandler provides object : UriHandler { override fun openUri(uri: String) { opened += uri } },
                    LocalShipCookie provides "session=x",
                ) {
                    PermitsBanner(http, "https://ship.example", inApp = true)
                }
            }
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("1 app needs its permissions approved.").fetchSemanticsNodes().isNotEmpty() }
        val before = reads.get()
        onNodeWithText("Review").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Permissions").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(emptyList(), opened, "not the browser")
        onNodeWithContentDescription("Close").performClick()
        waitUntil(timeoutMillis = 5_000) { reads.get() > before }
    }
}
