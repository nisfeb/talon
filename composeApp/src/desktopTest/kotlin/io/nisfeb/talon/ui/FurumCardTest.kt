package io.nisfeb.talon.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.nisfeb.talon.ui.theme.TalonTheme
import io.nisfeb.talon.urbit.FurumPreview
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The card under a message that links furum, as a reader sees and taps it. */
@OptIn(ExperimentalTestApi::class)
class FurumCardTest {
    private val json = headersOf("Content-Type", "application/json")

    private fun card(reply: Pair<HttpStatusCode, String>, link: String, block: androidx.compose.ui.test.ComposeUiTest.(List<String>) -> Unit) {
        runBlocking { FurumPreview.clear() }
        val opened = CopyOnWriteArrayList<String>()
        val http = HttpClient(MockEngine { respond(reply.second, reply.first, json) })
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalShipUrl provides "https://me.example", LocalShipCookie provides "c=1") {
                    TalonTheme(darkTheme = false) { FurumCard(link, http, onOpen = { opened += it }) }
                }
            }
            block(opened)
        }
    }

    @Test
    fun `a linked post shows its board, standing and title, and a tap opens the link`() = card(
        HttpStatusCode.OK to """{"board":{"title":"Cats"},"post":{"title":"A cat sat","author":"~bus","points":12,"comments":4,"excerpt":"on the mat"}}""",
        link = "https://sharer.io/apps/furum/b/~zod/cats/42",
    ) { opened ->
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("A cat sat").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("f/~zod/cats · 12 points · 4 comments").assertExists()
        onNodeWithText("on the mat").performClick()
        assertEquals(listOf("https://sharer.io/apps/furum/b/~zod/cats/42"), opened)
    }

    @Test
    fun `a ship with no furum, or none new enough, shows no card`() = card(
        HttpStatusCode.NotFound to "", link = "f/~zod/none",
    ) {
        waitForIdle()
        Thread.sleep(300)
        waitForIdle()
        assertTrue(onAllNodesWithText("f/~zod/none", substring = true).fetchSemanticsNodes().isEmpty())
    }
}
