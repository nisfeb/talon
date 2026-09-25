package io.nisfeb.talon.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.nisfeb.talon.calendar.CalendarRepo
import io.nisfeb.talon.calendar.LocalCalendarRepo
import io.nisfeb.talon.mail.Attachment
import io.nisfeb.talon.mail.MailRepo
import io.nisfeb.talon.ui.screens.MailAttachmentRow
import io.nisfeb.talon.ui.theme.TalonTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One mail attachment: got from this ship, or asked of the network and
 * waited for; saved where the platform can; a small picture fetched
 * unasked; a calendar invite put straight into a calendar.
 */
@OptIn(ExperimentalTestApi::class)
class MailAttachmentRowTest {
    private val did: MutableList<String> = Collections.synchronizedList(mutableListOf())
    /** Whether this ship holds the bytes yet; asking the network makes it so. */
    @Volatile private var held = true
    @Volatile private var calendarTakes = true
    private val bytes = "BEGIN:VCALENDAR\nEND:VCALENDAR".encodeToByteArray()

    private val http = HttpClient(MockEngine { req ->
        val path = req.url.encodedPath
        val json = { body: String -> respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json")) }
        when {
            "/api/blob/" in path ->
                if (held) respond(bytes, HttpStatusCode.OK, headersOf("Content-Disposition", "attachment; filename=\"got.bin\""))
                else respond("", HttpStatusCode.Conflict)
            path.endsWith("/api/fetch-blob") -> { did += "asked the network"; held = true; json("{}") }
            path.endsWith("/import") && req.method == HttpMethod.Post -> {
                did += "import ${req.url.parameters["cal"]}"
                if (calendarTakes) json("{}") else respond("", HttpStatusCode.InternalServerError)
            }
            path.endsWith("/calendars.json") -> json("""[{"id":"default","name":"Personal","kind":"local"}]""")
            path.endsWith("/config.json") -> json("""{"title":"Calendar","zone":"UTC","ball":"abc123"}""")
            path.endsWith("/share/shares.json") -> json("""{"shares":{},"offers":{},"accepted":{}}""")
            path.endsWith("/window.json") -> json("""{"rows":[]}""")
            else -> json("[]")
        }
    })

    private val saves = object : ImageDownloader {
        override suspend fun saveImage(url: String) = SaveResult.Unsupported
        override suspend fun saveBytes(fileName: String, bytes: ByteArray): SaveResult {
            did += "saved $fileName ${bytes.size}"
            return SaveResult.Saved("Downloads")
        }
        override val canSaveFiles = true
    }

    private fun row(
        attachment: Attachment,
        downloader: ImageDownloader = saves,
        calendar: Boolean = false,
        block: ComposeUiTest.() -> Unit,
    ) {
        val scope = CoroutineScope(SupervisorJob())
        val mail = MailRepo(http, scope, pollIntervalMs = 60 * 60 * 1000L).also { it.attach("https://ship.example") }
        val cal = if (!calendar) null else CalendarRepo(http, scope, pollIntervalMs = 60 * 60_000L).apply {
            attach("https://ship.example")
            runBlocking { refresh(); refreshAll() }
        }
        try {
            runComposeUiTest {
                setContent {
                    CompositionLocalProvider(LocalImageDownloader provides downloader, LocalCalendarRepo provides cal) {
                        TalonTheme(darkTheme = false) { MailAttachmentRow(mail, attachment, from = "~bus") }
                    }
                }
                block()
            }
        } finally {
            scope.cancel()
        }
    }

    private fun ComposeUiTest.shows(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun ComposeUiTest.until(text: String) = waitUntil(timeoutMillis = 10_000) { shows(text) }

    private val file = Attachment(name = "minutes.pdf", size = 20_000, mime = "application/pdf", hash = "0vabc")

    @Test
    fun `a file this ship holds is got, then saved under the ship's name for it`() = row(file) {
        assertTrue(shows("application/pdf"), "before, the sender's claims")
        onNodeWithText("Get").performClick()
        until("Ready to save")
        onNodeWithText("Save").performClick()
        until("Saved to Downloads")
        assertEquals(listOf("saved got.bin ${bytes.size}"), did.toList())
        assertTrue(onAllNodesWithText("Get").fetchSemanticsNodes().isEmpty(), "saved is the end of it")
    }

    @Test
    fun `a file not here is asked of the network and waited for`() {
        held = false
        row(file) {
            onNodeWithText("Get").performClick()
            until("Ready to save")
            assertEquals(listOf("asked the network"), did.toList())
        }
    }

    @Test
    fun `where files cannot be saved, a file got offers no Save`() = row(file, downloader = NoopImageDownloader) {
        onNodeWithText("Get").performClick()
        until("Ready to save")
        assertTrue(onAllNodesWithText("Save").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `a small picture is fetched unasked`() = row(Attachment(name = "cat.png", size = 900, mime = "image/png", hash = "0vcat")) {
        until("Ready to save")
        assertTrue(onAllNodesWithText("Get").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `an invite goes into the calendar chosen, fetched first`() = row(
        Attachment(name = "party.ics", size = 30, mime = "text/calendar", hash = "0vics"), calendar = true,
    ) {
        onNodeWithText("Add to calendar").performClick()
        onNodeWithText("Personal").performClick()
        until("Added to Personal")
        assertEquals(listOf("import default"), did.toList())
    }

    @Test
    fun `an invite the calendar refuses says so, and can be tried again`() {
        calendarTakes = false
        row(Attachment(name = "party.ics", size = 30, mime = "text/calendar", hash = "0vics"), calendar = true) {
            onNodeWithText("Add to calendar").performClick()
            onNodeWithText("Personal").performClick()
            until("Personal did not take the event.")
            assertTrue(shows("Try again"))
        }
    }
}
