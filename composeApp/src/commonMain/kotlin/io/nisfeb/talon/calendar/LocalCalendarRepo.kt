package io.nisfeb.talon.calendar

import androidx.compose.runtime.staticCompositionLocalOf

/** The ship's calendar, for adding an event seen elsewhere: a card in a chat, an invite in a mail. Null where there is none. */
val LocalCalendarRepo = staticCompositionLocalOf<CalendarRepo?> { null }
