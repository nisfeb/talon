package io.nisfeb.talon.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import io.nisfeb.talon.calendar.EventCat
import io.nisfeb.talon.calendar.EventDraft
import io.nisfeb.talon.ui.screens.FromMessage
import io.nisfeb.talon.ui.screens.MessageToCalendarDialog
import io.nisfeb.talon.ui.theme.TalonTheme
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A message made into an event or a task: the title and note it starts
 * with, the next hour as the time, a late night moved to the morning,
 * and a task with no date unless one is picked.
 */
@OptIn(ExperimentalTestApi::class)
class MessageToCalendarDialogTest {
    private val saved = mutableListOf<EventDraft>()

    private fun at(hour: Int, minute: Int) = LocalDateTime(2026, 9, 25, hour, minute).toInstant(TimeZone.UTC).toEpochMilliseconds()

    private fun dialog(kind: FromMessage, nowMs: Long = at(10, 30), block: ComposeUiTest.() -> Unit) = runComposeUiTest {
        setContent {
            TalonTheme(darkTheme = false) {
                MessageToCalendarDialog(
                    kind = kind, initialTitle = "Lunch with Bus", initialNote = "at the usual place",
                    zone = TimeZone.UTC, nowMs = nowMs, twentyFourHour = true,
                    onDismiss = {}, onSave = { saved += it },
                )
            }
        }
        block()
    }

    @Test
    fun `an event starts at the next hour, an hour long, today`() = dialog(FromMessage.Event) {
        onNodeWithText("Add").performClick()
        val d = saved.single()
        assertEquals("Lunch with Bus" to "at the usual place", d.name to d.note)
        assertEquals(LocalDate(2026, 9, 25), d.date)
        assertEquals(11 * 60 to 60, d.minuteOfDay to d.durMin)
    }

    @Test
    fun `an event made late at night is for tomorrow morning`() = dialog(FromMessage.Event, nowMs = at(23, 40)) {
        onNodeWithText("Add").performClick()
        val d = saved.single()
        assertEquals(LocalDate(2026, 9, 26) to 9 * 60, d.date to d.minuteOfDay)
    }

    @Test
    fun `a task has no due date until one is picked`() = dialog(FromMessage.Task) {
        onNodeWithText("No due date").performClick()
        onNodeWithText("OK").performClick()
        onNodeWithText("Due 2026-09-25").performClick()
        onNodeWithText("No date").performClick()
        onNodeWithText("Add").performClick()
        val d = saved.single()
        assertEquals(EventCat.TODO, d.cat)
        assertNull(d.due)
    }

    @Test
    fun `a task given a date is due that day`() = dialog(FromMessage.Task) {
        onNodeWithText("No due date").performClick()
        onNodeWithText("OK").performClick()
        onNodeWithText("Add").performClick()
        assertEquals(LocalDate(2026, 9, 25), saved.single().due)
    }

    @Test
    fun `nothing is added without a title`() = dialog(FromMessage.Event) {
        onNode(hasSetTextAction() and hasText("Title")).performTextReplacement("  ")
        onNodeWithText("Add").assertIsNotEnabled()
        kotlin.test.assertTrue(onAllNodesWithText("New event").fetchSemanticsNodes().isNotEmpty(), "the dialog stays")
    }
}
