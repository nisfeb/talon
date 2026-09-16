package io.nisfeb.talon.ui

import io.nisfeb.talon.calendar.CalendarAvailability
import io.nisfeb.talon.mail.MailAvailability

/**
 * What the Apps page says about one Grubbery app.
 *
 * The three apps answer differently — mail knows whether the desk is
 * there but out of date, the calendar only knows its route answered,
 * lattice is a probe — so this maps each vocabulary onto one the page
 * can render, rather than teaching the page three of them.
 */
enum class AppState {
    /** Answering. */
    WORKING,

    /** Not on this ship. Installing is the fix. */
    MISSING,

    /** The desk is here, but too old to carry this app. Installing does
     *  nothing: kiln tracks the publisher, so this resolves itself. */
    OUTDATED,

    /** Nothing to say about the app: the session is over. */
    SIGNED_OUT,

    /** Not asked yet, or asked and the ship did not answer. */
    UNKNOWN,
}

data class AppRow(
    val name: String,
    val state: AppState,
    /** The sentence under the name. */
    val detail: String,
    /** Whether to offer the install. Only where installing is the fix. */
    val canInstall: Boolean,
    /** The ship's own words, when it had any. */
    val error: String? = null,
)

/** Mail rides inside the grubbery desk, so it can be missing two ways. */
fun mailRow(availability: MailAvailability, error: String?): AppRow = when (availability) {
    MailAvailability.PRESENT -> AppRow("Mail", AppState.WORKING, "Answering on this ship.", false, error)
    MailAvailability.NO_GRUBBERY ->
        AppRow("Mail", AppState.MISSING, "Mail runs inside Grubbery, which this ship does not have.", true, error)
    MailAvailability.OLD_GRUBBERY ->
        AppRow("Mail", AppState.OUTDATED, "This ship's Grubbery predates Mail. It updates itself from its publisher.", false, error)
    MailAvailability.SIGNED_OUT -> AppRow("Mail", AppState.SIGNED_OUT, "Signed out of the ship.", false, error)
    MailAvailability.UNKNOWN -> AppRow("Mail", AppState.UNKNOWN, "Not asked yet.", false, error)
}

/** The calendar is its own desk, so absent means absent. */
fun calendarRow(availability: CalendarAvailability, error: String?): AppRow = when (availability) {
    CalendarAvailability.PRESENT -> AppRow("Calendar", AppState.WORKING, "Answering on this ship.", false, error)
    CalendarAvailability.ABSENT -> AppRow("Calendar", AppState.MISSING, "This ship has no calendar yet.", true, error)
    CalendarAvailability.SIGNED_OUT -> AppRow("Calendar", AppState.SIGNED_OUT, "Signed out of the ship.", false, error)
    CalendarAvailability.UNKNOWN -> AppRow("Calendar", AppState.UNKNOWN, "Not asked yet.", false, error)
}

/**
 * Lattice is the Grubbery desk itself, probed rather than subscribed:
 * null is "not asked yet", which is not the same as absent.
 */
fun latticeRow(installed: Boolean?, error: String? = null): AppRow = when (installed) {
    true -> AppRow("Lattice", AppState.WORKING, "Answering on this ship.", false, error)
    false -> AppRow("Lattice", AppState.MISSING, "Grubbery is not on this ship. Lattice comes with it.", true, error)
    null -> AppRow("Lattice", AppState.UNKNOWN, "Not asked yet.", false, error)
}

/** Where a ship's Grubbery keeps the permissions its apps ask for. */
fun permitsUrl(shipUrl: String): String = shipUrl.trimEnd('/') + "/grubbery/permits"
