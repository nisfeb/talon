package io.nisfeb.talon.mail

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import io.nisfeb.talon.ui.ContactMap
import io.nisfeb.talon.ui.screens.MailThreadPane
import io.nisfeb.talon.ui.screens.sizeLabel
import io.nisfeb.talon.ui.screens.unreadableThreadLine
import io.nisfeb.talon.ui.theme.TalonTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The reader. The claims here are the ones the tree exists for: what a
 * reply would carry is stated, it changes when the selection changes,
 * and a forged copy says so and cannot be selected to answer.
 */
class MailThreadPaneTest {

    /** A root with two replies, so the thread branches. */
    private val branching = """
        {"id":"0vt","participants":["~zod","~nec"],"last":40,"unreadable":0,
         "archived":false,"labels":[],"messages":[
          {"id":"0vroot","from":"~zod","to":["~nec"],"subject":"Plans",
           "body":"the root","sent":10,"prev":null,"verdict":"verified","read":true},
          {"id":"0vmine","from":"~nec","to":["~zod"],"subject":"Plans",
           "body":"my branch","sent":20,"prev":"0vroot","verdict":"verified","read":true},
          {"id":"0vtheirs","from":"~zod","to":["~nec"],"subject":"Plans",
           "body":"their branch","sent":30,"prev":"0vroot","verdict":"verified","read":true}]}
    """.trimIndent()

    private fun repoServing(body: String): MailRepo {
        val http = HttpClient(
            MockEngine {
                respond(
                    ByteReadChannel(body),
                    HttpStatusCode.OK,
                    headersOf("Content-Type", "application/json"),
                )
            },
        )
        return MailRepo(http, CoroutineScope(SupervisorJob()), pollIntervalMs = 60 * 60 * 1000L)
            .also { it.attach("https://ship.example") }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the tree says what a reply from the selected message would carry`() = runComposeUiTest {
        val repo = repoServing(branching)
        setContent {
            TalonTheme(darkTheme = false) {
                MailThreadPane(
                    repo = repo,
                    threadId = "0vt",
                    contacts = ContactMap.EMPTY,
                    ourShip = "~nec",
                    onCompose = {},
                )
            }
        }
        waitUntil(timeoutMillis = 5_000) {
            runCatching { onNodeWithText("the root").assertIsDisplayed(); true }.getOrDefault(false)
        }
        // The default is the newest honest message, which is one of the
        // two branches: root plus that branch, and never its sibling.
        waitUntil(timeoutMillis = 5_000) {
            runCatching {
                onNodeWithText("2 signed messages travel with a reply from here.")
                    .assertIsDisplayed()
                true
            }.getOrDefault(false)
        }

        // Selecting the root narrows what travels to the root alone.
        onNodeWithText("the root").performClick()
        waitUntil(timeoutMillis = 5_000) {
            runCatching {
                onNodeWithText("1 signed message travels with a reply from here.")
                    .assertIsDisplayed()
                true
            }.getOrDefault(false)
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a forged copy says why it cannot be answered`() = runComposeUiTest {
        val repo = repoServing(
            """{"id":"0vt","participants":["~zod"],"last":10,"unreadable":0,
                "archived":false,"labels":[],"messages":[
                 {"id":"0vf","from":"~zod","subject":"Invoice","body":"pay me",
                  "sent":10,"prev":null,"verdict":"forged","read":true}]}""",
        )
        setContent {
            TalonTheme(darkTheme = false) {
                MailThreadPane(
                    repo = repo,
                    threadId = "0vt",
                    contacts = ContactMap.EMPTY,
                    ourShip = "~nec",
                    onCompose = {},
                )
            }
        }
        waitUntil(timeoutMillis = 5_000) {
            runCatching { onNodeWithText("FORGED").assertIsDisplayed(); true }.getOrDefault(false)
        }
        onNodeWithText(
            "A copy of this message failed its signature. It is kept as " +
                "evidence and cannot be answered.",
        ).assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a body that asked for another format is shown as plain text and says so`() =
        runComposeUiTest {
            val repo = repoServing(
                """{"id":"0vt","participants":["~zod"],"last":10,"unreadable":0,
                    "archived":false,"labels":[],"messages":[
                     {"id":"0vh","from":"~zod","subject":"Hi","body":"<b>bold</b>",
                      "body-mime":"text/html","sent":10,"prev":null,
                      "verdict":"verified","read":true}]}""",
            )
            setContent {
                TalonTheme(darkTheme = false) {
                    MailThreadPane(
                    repo = repo,
                    threadId = "0vt",
                    contacts = ContactMap.EMPTY,
                    ourShip = "~nec",
                    onCompose = {},
                )
                }
            }
            waitUntil(timeoutMillis = 5_000) {
                runCatching {
                    onNodeWithText("Shown as plain text; this message asked for text/html.")
                        .assertIsDisplayed()
                    true
                }.getOrDefault(false)
            }
            // The markup is text, not markup: it is rendered verbatim.
            onNodeWithText("<b>bold</b>").assertIsDisplayed()
        }

    @Test
    fun `sizes and unreadable counts read as sentences`() {
        assertEquals("unknown size", sizeLabel(0))
        assertEquals("512 B", sizeLabel(512))
        assertEquals("2.0 KB", sizeLabel(2048))
        assertTrue(unreadableThreadLine(1).startsWith("1 copy here is"))
        assertTrue(unreadableThreadLine(3).startsWith("3 copies here are"))
    }

    // Writing a reply puts the composer where the reader was, and the
    // reader came back from it as a list, not the tree it was left in.
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the tree is still shown when the reader comes back from a reply`() = runComposeUiTest {
        val repo = repoServing(branching)
        var reading by androidx.compose.runtime.mutableStateOf(true)
        setContent {
            TalonTheme(darkTheme = false) {
                if (reading) {
                    MailThreadPane(
                        repo = repo,
                        threadId = "0vt",
                        contacts = ContactMap.EMPTY,
                        ourShip = "~nec",
                        onCompose = {},
                    )
                }
            }
        }
        waitUntil(timeoutMillis = 5_000) {
            runCatching { onNodeWithText("Tree").assertIsDisplayed(); true }.getOrDefault(false)
        }
        onNodeWithText("Tree").performClick()
        waitForIdle()
        onNodeWithText("Tree").assertIsSelected()
        reading = false // the composer takes its place
        waitForIdle()
        reading = true // sent, and back to the thread
        waitUntil(timeoutMillis = 5_000) {
            runCatching { onNodeWithText("Tree").assertIsDisplayed(); true }.getOrDefault(false)
        }
        onNodeWithText("Tree").assertIsSelected()
    }

    // By the time the thread shows, the ship has marked it read. What was
    // new when it opened stays marked under a New line, and what had been
    // read already folds to its header, in a thread long enough to fold.
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `what was new when the thread opened is marked, and what was read folds`() = runComposeUiTest {
        val repo = repoServing(
            """
            {"id":"0vt","participants":["~zod","~nec"],"last":30,"unreadable":0,
             "archived":false,"labels":[],"messages":[
              {"id":"0va","from":"~zod","to":["~nec"],"subject":"Plans",
               "body":"first line\nsecond line","sent":10,"prev":null,"verdict":"verified","read":true},
              {"id":"0vb","from":"~nec","to":["~zod"],"subject":"Plans",
               "body":"a reply","sent":20,"prev":"0va","verdict":"verified","read":true},
              {"id":"0vc","from":"~zod","to":["~nec"],"subject":"Plans",
               "body":"the news","sent":30,"prev":"0vb","verdict":"verified","read":false}]}
            """.trimIndent(),
        )
        setContent {
            TalonTheme(darkTheme = false) {
                MailThreadPane(repo = repo, threadId = "0vt", contacts = ContactMap.EMPTY, ourShip = "~nec", onCompose = {})
            }
        }
        waitUntil(timeoutMillis = 5_000) {
            runCatching { onNodeWithText("New").assertIsDisplayed(); true }.getOrDefault(false)
        }
        onNodeWithText("the news").assertIsDisplayed()
        // Folded: its two lines as one, which only the header line shows.
        onNodeWithText("first line second line").assertIsDisplayed()
    }

    // Auspex 15 keeps a card the owner closed, on every client: one the
    // ship holds closed opens closed here, and opening or closing one by
    // hand tells the ship, with the thread named.
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a message closed on another client opens closed, and a close or open here is kept`() = runComposeUiTest {
        val posts = java.util.concurrent.CopyOnWriteArrayList<String>()
        val http = HttpClient(
            MockEngine { req ->
                if (req.method == io.ktor.http.HttpMethod.Post) {
                    posts += req.url.encodedPath + " " + (req.body as io.ktor.http.content.TextContent).text
                }
                val body = if ("/api/thread/" in req.url.encodedPath) """
                    {"id":"0vt","participants":["~bus","~nec"],"last":20,"unreadable":0,
                     "archived":false,"labels":[],"folded":["0va"],"messages":[
                      {"id":"0va","from":"~bus","to":["~nec"],"subject":"Plans",
                       "body":"first line\nsecond line","sent":10,"prev":null,"verdict":"verified","read":true},
                      {"id":"0vb","from":"~nec","to":["~bus"],"subject":"Plans",
                       "body":"a reply","sent":20,"prev":"0va","verdict":"verified","read":true}]}
                """.trimIndent() else """{"ok":true,"threads":[]}"""
                respond(ByteReadChannel(body), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            },
        )
        val repo = MailRepo(http, CoroutineScope(SupervisorJob()), pollIntervalMs = 60 * 60 * 1000L)
            .also { it.attach("https://ship.example") }
        setContent {
            TalonTheme(darkTheme = false) {
                MailThreadPane(repo = repo, threadId = "0vt", contacts = ContactMap.EMPTY, ourShip = "~nec", onCompose = {})
            }
        }
        // Two messages, which Talon would show open: the ship's fold wins.
        waitUntil(timeoutMillis = 5_000) {
            runCatching { onNodeWithText("first line second line").assertIsDisplayed(); true }.getOrDefault(false)
        }
        onNodeWithText("~bus").performClick()
        waitUntil(timeoutMillis = 5_000) { posts.any { "/api/unfold" in it } }
        assertEquals(listOf("""/apps/auspex/api/unfold {"thread-id":"0vt","msg-ids":["0va"]}"""), posts.filter { "fold" in it })
        onNodeWithText("~bus").performClick()
        waitUntil(timeoutMillis = 5_000) { posts.any { it.startsWith("/apps/auspex/api/fold") } }
        // And it stays closed: on desktop the card's own click watcher saw
        // the name's click too, and opened the card again straight after.
        waitForIdle()
        onNodeWithText("first line second line").assertIsDisplayed()
        assertEquals(
            listOf(
                """/apps/auspex/api/unfold {"thread-id":"0vt","msg-ids":["0va"]}""",
                """/apps/auspex/api/fold {"thread-id":"0vt","msg-ids":["0va"]}""",
            ),
            posts.filter { "fold" in it },
        )
        // The other card was never closed by hand, so nothing was said of it.
        assertTrue(posts.none { "0vb" in it && "fold" in it }, "$posts")
    }
}
