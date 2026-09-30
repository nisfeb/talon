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
        assertEquals(listOf("https://ship.example/apps/grubbery/permits"), opened)
    }
}
