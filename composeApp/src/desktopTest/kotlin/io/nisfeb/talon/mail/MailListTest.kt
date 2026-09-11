package io.nisfeb.talon.mail

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import io.nisfeb.talon.ui.ContactMap
import io.nisfeb.talon.ui.screens.MailList
import io.nisfeb.talon.ui.screens.unreadableLine
import io.nisfeb.talon.ui.theme.TalonTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The mailbox on screen. The claims worth pinning are the ones a
 * forgery depends on: that a verdict reaches the row, and that a ship
 * with no mail app says so instead of showing an empty inbox.
 */
class MailListTest {

    private fun repoServing(handler: (String) -> Pair<Int, String>): MailRepo {
        val http = HttpClient(
            MockEngine { req ->
                val (code, body) = handler(req.url.encodedPath)
                if (code == 200) {
                    respond(
                        ByteReadChannel(body),
                        HttpStatusCode.OK,
                        headersOf("Content-Type", "application/json"),
                    )
                } else {
                    respondError(HttpStatusCode.fromValue(code), body)
                }
            },
        )
        return MailRepo(http, CoroutineScope(SupervisorJob()), pollIntervalMs = 60 * 60 * 1000L)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a forged thread is labelled on the row itself`() = runComposeUiTest {
        val repo = repoServing {
            200 to """{"total":1,"offset":0,"limit":50,"view":"inbox","threads":[
               {"id":"0v1","subject":"Invoice","from":"~zod","snippet":"pay me",
                "verdict":"forged","forged":true,"count":1,"last":0,
                "unread":true,"participants":["~zod"],"unreadable":0,
                "archived":false,"labels":[]}]}"""
        }
        setContent {
            TalonTheme(darkTheme = false) {
                MailList(repo = repo, contacts = ContactMap.EMPTY, onOpenThread = {})
            }
        }
        repo.attach("https://ship.example")
        waitUntil(timeoutMillis = 5_000) {
            runCatching { onNodeWithText("Invoice").assertIsDisplayed(); true }.getOrDefault(false)
        }
        onNodeWithText("FORGED").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a ship without the app offers to install rather than showing an empty inbox`() =
        runComposeUiTest {
            val repo = repoServing { path ->
                if (path.endsWith("manifest.webmanifest")) 404 to ""
                else 404 to """{"error":"not found"}"""
            }
            var offered = false
            setContent {
                TalonTheme(darkTheme = false) {
                    // The offer arrives through the local; without one
                    // the empty state says what is wrong and no more.
                    androidx.compose.runtime.CompositionLocalProvider(
                        io.nisfeb.talon.mail.LocalGrubberyInstall provides {
                            offered = true
                            Result.success(Unit)
                        },
                    ) {
                        MailList(
                            repo = repo,
                            contacts = ContactMap.EMPTY,
                            onOpenThread = {},
                        )
                    }
                }
            }
            repo.attach("https://ship.example")
            waitUntil(timeoutMillis = 5_000) {
                runCatching {
                    onNodeWithText("Install Grubbery").assertIsDisplayed(); true
                }.getOrDefault(false)
            }
            assertTrue(!offered, "the offer is a control, not something that fires on its own")
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `with no installer there is no button to press`() = runComposeUiTest {
        val repo = repoServing { path ->
            if (path.endsWith("manifest.webmanifest")) 404 to ""
            else 404 to """{"error":"not found"}"""
        }
        setContent {
            TalonTheme(darkTheme = false) {
                MailList(repo = repo, contacts = ContactMap.EMPTY, onOpenThread = {})
            }
        }
        repo.attach("https://ship.example")
        waitUntil(timeoutMillis = 5_000) {
            runCatching {
                onNodeWithText("Mail runs inside Grubbery, which this ship does not have yet.")
                    .assertIsDisplayed()
                true
            }.getOrDefault(false)
        }
        onNodeWithText("Install Grubbery").assertDoesNotExist()
    }

    @Test
    fun `a thread with no readable copy says that, not just how many`() {
        val allUnreadable = InboxEntry(id = "0v1", count = 0, unreadable = 2)
        assertTrue(unreadableLine(allUnreadable).contains("none of them"))

        val partly = InboxEntry(id = "0v2", count = 3, unreadable = 1)
        assertEquals(
            "1 message here in a form this build cannot read.",
            unreadableLine(partly),
        )
    }
}
