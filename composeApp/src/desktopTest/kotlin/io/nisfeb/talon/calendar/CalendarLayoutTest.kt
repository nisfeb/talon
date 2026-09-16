package io.nisfeb.talon.calendar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.nisfeb.talon.ui.screens.CalendarScreen
import io.nisfeb.talon.ui.theme.TalonTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertTrue

/** Where the selected day's events sit around the month, and the view toggle the shells save. */
@OptIn(ExperimentalTestApi::class)
class CalendarLayoutTest {

    private fun layout(width: Dp, check: ComposeUiTest.(CalendarRepo) -> Unit) = runComposeUiTest {
        val repo = CalendarRepo(
            HttpClient(MockEngine { respondError(HttpStatusCode.NotFound) }),
            CoroutineScope(SupervisorJob()),
        )
        setContent {
            TalonTheme(darkTheme = false) {
                Box(Modifier.requiredSize(width, 800.dp)) {
                    CalendarScreen(repo = repo, twentyFourHour = true, onBack = null, modifier = Modifier.fillMaxSize())
                }
            }
        }
        waitForIdle()
        check(repo)
    }

    // Nothing is read yet, so the day's list says "Looking…".
    @Test
    fun `a wide month lists the day beside the grid`() = layout(1200.dp) {
        val grid = onNodeWithText("Mon").getUnclippedBoundsInRoot()
        val day = onNodeWithText("Looking…").getUnclippedBoundsInRoot()
        assertTrue(day.left > 600.dp, "day list starts at ${day.left}")
        assertTrue(day.top < grid.bottom + 100.dp, "day list top ${day.top}, grid labels end ${grid.bottom}")
    }

    @Test
    fun `a narrow month keeps the day under the grid`() = layout(400.dp) {
        val grid = onNodeWithText("Mon").getUnclippedBoundsInRoot()
        val day = onNodeWithText("Looking…").getUnclippedBoundsInRoot()
        assertTrue(day.left < 100.dp, "day list starts at ${day.left}")
        assertTrue(day.top > grid.bottom + 200.dp, "day list top ${day.top}, grid labels end ${grid.bottom}")
    }

    @Test
    fun `the week toggle sets what the shell saves`() = layout(1200.dp) { repo ->
        onNodeWithText("Week").performClick()
        waitForIdle()
        assertTrue(repo.weekView.value)
    }

    // The regression: nine controls and a title shared one row, the title
    // was left a few dip, and "Sep 2026" wrapped one letter to a line.
    @Test
    fun `a phone puts the month on its own line, one line tall`() = layout(400.dp) {
        val month = onNodeWithText(monthLabel()).getUnclippedBoundsInRoot()
        assertTrue(month.top > 40.dp, "month sits under the toolbar, not in it: top ${month.top}")
        val tall = month.bottom - month.top
        assertTrue(tall < 60.dp, "month is one line, not a stack of letters: $tall")
    }

    @Test
    fun `a wide window keeps the month in the toolbar`() = layout(1200.dp) {
        val month = onNodeWithText(monthLabel()).getUnclippedBoundsInRoot()
        assertTrue(month.top < 40.dp, "month stays in the toolbar: top ${month.top}")
    }

    private fun monthLabel(): String {
        val today = kotlinx.datetime.Instant.fromEpochMilliseconds(io.nisfeb.talon.util.nowMs())
            .toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault()).date
        return "${kotlinx.datetime.format.MonthNames.ENGLISH_ABBREVIATED.names[today.monthNumber - 1]} ${today.year}"
    }
}
