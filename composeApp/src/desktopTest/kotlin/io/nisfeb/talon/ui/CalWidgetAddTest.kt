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
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.nisfeb.talon.calendar.CalendarRepo
import io.nisfeb.talon.calendar.LocalCalendarRepo
import io.nisfeb.talon.ui.theme.TalonTheme
import io.nisfeb.talon.urbit.StoryPart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A chat's event card, added to a calendar the calendar then refuses:
 * the card says why, rather than "Not added" alone, and the calendar
 * does not say it again when it next opens.
 */
@OptIn(ExperimentalTestApi::class)
class CalWidgetAddTest {
    private val refusals = CopyOnWriteArrayList<String>()
    /** What the calendar answers a poke with: OK and a refusal a beat after, or a failure outright. */
    @Volatile private var pokeStatus = HttpStatusCode.OK
    private val json = headersOf("Content-Type", "application/json")

    private val http = HttpClient(MockEngine { req ->
        val path = req.url.encodedPath
        when {
            // Acked first and refused a beat after, as the calendar does.
            path.startsWith("/grubbery/api/poke/") -> {
                if (pokeStatus == HttpStatusCode.OK) {
                    refusals += """{"at_ms":${System.currentTimeMillis()},"action":"add-event","id":"","from":"","why":"no such calendar"}"""
                }
                respond("", pokeStatus, json)
            }
            path.endsWith("/outcomes.json") -> respond("""{"refusals":[${refusals.joinToString(",")}],"faults":{}}""", HttpStatusCode.OK, json)
            path.endsWith("/calendars.json") -> respond("""[{"id":"default","name":"Personal","kind":"local"}]""", HttpStatusCode.OK, json)
            path.endsWith("/config.json") -> respond("""{"title":"Calendar","zone":"UTC","ball":"abc123"}""", HttpStatusCode.OK, json)
            path.endsWith("/share/shares.json") -> respond("""{"shares":{},"offers":{},"accepted":{}}""", HttpStatusCode.OK, json)
            path.endsWith("/window.json") -> respond("""{"rows":[]}""", HttpStatusCode.OK, json)
            else -> respond("[]", HttpStatusCode.OK, json)
        }
    })

    private fun ComposeUiTest.shows(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `a refused add says the calendar's reason on the card`() {
        val scope = CoroutineScope(SupervisorJob())
        val cal = CalendarRepo(http, scope, pollIntervalMs = 60 * 60_000L).apply {
            attach("https://ship.example")
            runBlocking { refresh(); refreshAll() }
        }
        val start = System.currentTimeMillis() + 3_600_000
        try {
            runComposeUiTest {
                setContent {
                    CompositionLocalProvider(LocalCalendarRepo provides cal) {
                        TalonTheme(darkTheme = false) {
                            StoryRenderer(listOf(StoryPart.CalWidget(start, start + 3_600_000, "Picnic")), onMentionTap = {}, onLinkTap = {})
                        }
                    }
                }
                onNodeWithText("Add").performClick()
                onNodeWithText("Personal").performClick()
                waitUntil(timeoutMillis = 15_000) { shows("Not added") }
                assertTrue(shows("no such calendar"), "the calendar's own words")
                assertNull(cal.unheard.value, "heard here, so not said again on the calendar")
            }
        } finally {
            scope.cancel()
        }
    }

    // An add whose write failed outright left no refusal of its own, and
    // the card said whatever older one was waiting for the calendar.
    @Test
    fun `an add that fails outright says so and leaves another's refusal waiting`() {
        val scope = CoroutineScope(SupervisorJob())
        val cal = CalendarRepo(http, scope, pollIntervalMs = 60 * 60_000L).apply {
            attach("https://ship.example")
            runBlocking { refresh(); refreshAll() }
        }
        val since = System.currentTimeMillis()
        refusals += """{"at_ms":$since,"action":"edit-event","id":"lunch-1","from":"","why":"that event is gone"}"""
        val older = runBlocking { cal.refusalOfSave("lunch-1", since) { "\"Lunch\" was not saved: $it" } }
        kotlin.test.assertEquals("\"Lunch\" was not saved: that event is gone", cal.unheard.value, "waiting from $older")
        pokeStatus = HttpStatusCode.InternalServerError
        val start = System.currentTimeMillis() + 3_600_000
        try {
            runComposeUiTest {
                setContent {
                    CompositionLocalProvider(LocalCalendarRepo provides cal) {
                        TalonTheme(darkTheme = false) {
                            StoryRenderer(listOf(StoryPart.CalWidget(start, start + 3_600_000, "Picnic")), onMentionTap = {}, onLinkTap = {})
                        }
                    }
                }
                onNodeWithText("Add").performClick()
                onNodeWithText("Personal").performClick()
                waitUntil(timeoutMillis = 15_000) { shows("Not added") }
                assertTrue(!shows("that event is gone"), "not the other save's reason")
                kotlin.test.assertEquals("\"Lunch\" was not saved: that event is gone", cal.unheard.value, "still for the calendar to say")
            }
        } finally {
            scope.cancel()
        }
    }
}
