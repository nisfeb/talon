package io.nisfeb.talon.calendar

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A write goes to the ball as a path. Encoding the ball whole made
 * every separator a %2F, the shell answered 404, and every calendar
 * edit failed with "the calendar did not take it".
 */
class CalendarPokeTest {
    @Test
    fun `the ball keeps its separators`() = runTest {
        var seen: HttpRequestData? = null
        val http = HttpClient(MockEngine { req -> seen = req; respond("", HttpStatusCode.OK) })
        val ball = "apps/shell.shell/desks/calendar.desk/desk/data/calendar.calendar_app"
        assertTrue(CalendarApi(http, "https://ship").poke(ball, buildJsonObject { }))
        assertEquals("/grubbery/api/poke/$ball/calendar.calendar", seen!!.url.encodedPath)
    }
}
