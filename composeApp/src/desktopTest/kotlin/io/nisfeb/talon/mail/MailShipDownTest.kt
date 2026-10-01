package io.nisfeb.talon.mail

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.nisfeb.talon.ui.ContactMap
import io.nisfeb.talon.ui.screens.MailList
import io.nisfeb.talon.ui.theme.TalonTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * "the auspex mail UI has a giant red banner with raw html saying 502 bad
 * gateway that can't be dismissed and doesn't go away after navigating
 * away and back again." The ship was down for a restart, and nginx in
 * front of it answered for it.
 */
@OptIn(ExperimentalTestApi::class)
class MailShipDownTest {
    @Volatile private var down = true

    private val inbox = """{"total":1,"offset":0,"limit":50,"view":"inbox","threads":[
        {"id":"0v1","subject":"Lunch","from":"~bus","snippet":"","verdict":"valid","forged":false,"count":1,"last":0,
         "unread":false,"participants":["~bus"],"unreadable":0,"archived":false,"labels":[]}]}"""

    private val scope = CoroutineScope(SupervisorJob())
    private val repo = MailRepo(
        HttpClient(MockEngine { req ->
            val path = req.url.encodedPath
            val json = { b: String -> respond(b, HttpStatusCode.OK, headersOf("Content-Type", "application/json")) }
            when {
                down -> respond(NGINX_502, HttpStatusCode.BadGateway, headersOf("Content-Type", "text/html"))
                path.endsWith("/api/inbox") -> json(inbox)
                path.endsWith("/api/whoami") -> json("""{"ship":"~zod"}""")
                else -> json("[]")
            }
        }),
        scope,
        pollIntervalMs = 60 * 60 * 1000L,
        firstRetryMs = 300L,
    )

    private fun list(block: ComposeUiTest.() -> Unit) = try {
        runComposeUiTest {
            setContent { TalonTheme(darkTheme = false) { MailList(repo = repo, contacts = ContactMap.EMPTY, onOpenThread = {}) } }
            repo.attach("https://ship.test")
            block()
        }
    } finally {
        scope.cancel()
    }

    private fun ComposeUiTest.shows(t: String) = onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `a ship that is down is said in words, never the web server's page, and the line can be put away`() = list {
        waitUntil(timeoutMillis = 5_000) { shows("Your ship isn't answering") }
        assertFalse(shows("<html") || shows("Bad Gateway") || shows("nginx"), "no markup on screen")
        onNodeWithText("Your ship isn't answering", substring = true).performClick()
        waitUntil(timeoutMillis = 5_000) { !shows("Your ship isn't answering") }
    }

    @Test
    fun `the line goes by itself once the ship is back, without waiting for the next poll`() = list {
        waitUntil(timeoutMillis = 5_000) { shows("Your ship isn't answering") }
        down = false
        // Asked again within a second, not at the hour-long poll this repo has.
        waitUntil(timeoutMillis = 5_000) { shows("Lunch") && !shows("Your ship isn't answering") }
    }
}
