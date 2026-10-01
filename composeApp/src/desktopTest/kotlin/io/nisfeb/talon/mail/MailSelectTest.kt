package io.nisfeb.talon.mail

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.nisfeb.talon.ui.ContactMap
import io.nisfeb.talon.ui.screens.MailList
import io.nisfeb.talon.ui.theme.TalonTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * "message multi-select in the auspex mail UI ... take message actions
 * like archive on all of the selected messages", and "loading auspex
 * messages ... feels slow and the frequent and long running spinner".
 */
@OptIn(ExperimentalTestApi::class)
class MailSelectTest {
    /** Every request: "METHOD path body". */
    private val asked = CopyOnWriteArrayList<String>()
    @Volatile private var refuse: String? = null
    @Volatile private var holdInboxMs = 0L

    private fun row(id: String, subject: String, unread: Boolean = false, archived: Boolean = false) =
        """{"id":"$id","subject":"$subject","from":"~bus","snippet":"","verdict":"valid","forged":false,"count":1,"last":0,
            "unread":$unread,"participants":["~bus"],"unreadable":0,"archived":$archived,"labels":[]}"""

    private val inbox = """{"total":3,"offset":0,"limit":50,"view":"inbox","threads":[
        ${row("0v1", "Lunch", unread = true)}, ${row("0v2", "Rent")}, ${row("0v3", "Tickets", unread = true)}]}"""

    private fun thread(id: String) = """{"id":"$id","participants":["~bus"],"last":0,"messages":[
        {"id":"$id.a","from":"~bus","subject":"s","body":"b","sent":1,"read":true},
        {"id":"$id.b","from":"~bus","subject":"s","body":"b","sent":2,"read":false}]}"""

    private val repo = MailRepo(
        HttpClient(MockEngine { req ->
            val path = req.url.encodedPath
            val body = if (req.method == HttpMethod.Post) req.body.toByteArray().decodeToString() else ""
            asked += "${req.method.value} $path $body".trim()
            val json = { b: String -> respond(b, HttpStatusCode.OK, headersOf("Content-Type", "application/json")) }
            when {
                refuse != null && refuse!! in body -> respond("""{"error":"no"}""", HttpStatusCode.BadRequest, headersOf("Content-Type", "application/json"))
                path.endsWith("/api/inbox") -> { delay(holdInboxMs); json(inbox) }
                path.contains("/api/thread/") -> json(thread(path.substringAfterLast('/')))
                path.endsWith("/api/whoami") -> json("""{"ship":"~zod"}""")
                req.method == HttpMethod.Post -> json("""{"ok":true}""")
                else -> json("[]")
            }
        }),
        CoroutineScope(SupervisorJob()),
        pollIntervalMs = 60 * 60 * 1000L,
    )

    private fun reads() = asked.count { it.startsWith("GET") && it.contains("/api/inbox") }
    private fun posts(api: String) = asked.filter { it.startsWith("POST /apps/auspex/api/$api ") }.map { it.substringAfter("/api/$api ") }

    private fun list(block: ComposeUiTest.() -> Unit) = runComposeUiTest {
        setContent { TalonTheme(darkTheme = false) { MailList(repo = repo, contacts = ContactMap.EMPTY, onOpenThread = {}) } }
        repo.attach("https://ship.test")
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Tickets").fetchSemanticsNodes().isNotEmpty() }
        block()
    }

    private fun ComposeUiTest.shows(t: String) = onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `threads are picked by a long press and a tap, and archived together with one read after`() = list {
        onNodeWithText("Lunch").performTouchInput { longClick() }
        waitUntil(timeoutMillis = 5_000) { shows("1 selected") }
        onNodeWithText("Tickets").performClick() // a tap adds, while picking
        waitUntil(timeoutMillis = 5_000) { shows("2 selected") }
        val before = reads()
        onNodeWithText("Archive").performClick()
        // Gone from the inbox at once, the bar with them.
        waitUntil(timeoutMillis = 5_000) { !shows("Lunch") && !shows("Tickets") && !shows("selected") }
        assertTrue(shows("Rent"))
        waitUntil(timeoutMillis = 5_000) { posts("archive").size == 2 }
        assertEquals(setOf("""{"thread-id":"0v1","archived":true}""", """{"thread-id":"0v3","archived":true}"""), posts("archive").toSet())
        // The listing read once the writes are in, once.
        waitUntil(timeoutMillis = 5_000) { reads() == before + 1 }
        Thread.sleep(MailRepo.RELIST_AFTER_MS + 500)
        assertEquals(before + 1, reads(), "one read for the whole selection")
    }

    @Test
    fun `select all picks every thread listed, and clearing ends the selection`() = list {
        onNodeWithText("Rent").performTouchInput { longClick() }
        waitUntil(timeoutMillis = 5_000) { shows("1 selected") }
        onNodeWithText("Select all").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("3 selected") }
        onNodeWithContentDescription("Clear selection").performClick()
        waitUntil(timeoutMillis = 5_000) { !shows("selected") }
        assertTrue(shows("Lunch"), "a tap opens again once nothing is picked")
    }

    @Test
    fun `deleting a selection asks first, and Keep sends nothing`() = list {
        onNodeWithText("Lunch").performTouchInput { longClick() }
        onNodeWithText("Rent").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("2 selected") }
        onNodeWithText("Delete").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Delete 2 threads?") }
        onNodeWithText("Keep").performClick()
        waitForIdle()
        assertTrue(posts("delete-thread").isEmpty())
        onNodeWithText("Delete").performClick()
        onAllNodesWithText("Delete").let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
        waitUntil(timeoutMillis = 5_000) { posts("delete-thread").size == 2 }
        assertEquals(setOf("""{"thread-id":"0v1"}""", """{"thread-id":"0v2"}"""), posts("delete-thread").toSet())
    }

    // A refused write puts its thread back and says how many, the rest stay done.
    @Test
    fun `a thread the ship refuses comes back, and the count is said`() = runBlocking<Unit> {
        repo.attach("https://ship.test")
        withTimeout(5_000) { while (repo.page.value == null) delay(20) }
        refuse = "0v2"
        repo.archiveMany(listOf("0v1", "0v2"), archived = true).join()
        assertEquals(listOf("0v2", "0v3"), repo.page.value!!.threads.map { it.id })
        assertEquals("1 of 2 were not archived; they are back as they were.", repo.problem.value)
    }

    // The ship marks messages, not threads; a row names none, so a
    // thread is read for its unread ones first.
    @Test
    fun `marking a selection read marks each thread's unread messages`() = runBlocking<Unit> {
        repo.attach("https://ship.test")
        withTimeout(5_000) { while (repo.page.value == null) delay(20) }
        repo.markManyRead(listOf("0v1", "0v3"), read = true).join()
        assertEquals(setOf("""{"thread-id":"0v1","msg-ids":["0v1.b"]}""", """{"thread-id":"0v3","msg-ids":["0v3.b"]}"""), posts("read").toSet())
        assertTrue(repo.page.value!!.threads.none { it.unread }, "shown read at once")
        repo.markManyRead(listOf("0v2"), read = false).join()
        assertEquals(listOf("""{"thread-id":"0v2","msg-ids":["0v2.b"]}"""), posts("unread"), "unread is the newest")
    }

    // The spinner is for someone waiting: a read behind mail already on
    // screen is quiet; a refresh asked for, or a first load, spins.
    @Test
    fun `only a read somebody waits on spins`() = runBlocking<Unit> {
        holdInboxMs = 400
        repo.attach("https://ship.test")
        withTimeout(5_000) { while (!repo.loading.value) delay(5) }
        withTimeout(5_000) { while (repo.page.value == null) delay(20) }
        withTimeout(5_000) { while (repo.loading.value) delay(20) }
        var spun = false
        val watch = launch(kotlinx.coroutines.Dispatchers.Default) { while (true) { if (repo.loading.value) spun = true; delay(5) } }
        repo.refresh()
        assertFalse(spun, "a background read behind what is shown")
        repo.refresh(asked = true)
        watch.cancel()
        assertTrue(spun, "the refresh control")
    }

    // Threads opened one after another each marked read: one listing read
    // for the run, not one per thread, each seconds on a busy ship.
    @Test
    fun `writes in a burst are followed by one listing read`() = runBlocking<Unit> {
        repo.attach("https://ship.test")
        withTimeout(5_000) { while (repo.page.value == null) delay(20) }
        val before = reads()
        repo.markRead(listOf("0v1.b"), "0v1")
        repo.markRead(listOf("0v3.b"), "0v3")
        repo.setArchived("0v2", true)
        withTimeout(5_000) { while (reads() == before) delay(20) }
        delay(MailRepo.RELIST_AFTER_MS + 500)
        assertEquals(before + 1, reads())
    }

}
