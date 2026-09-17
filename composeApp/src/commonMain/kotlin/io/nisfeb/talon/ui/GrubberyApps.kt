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

/** Which desk an install would fetch, and from whom. */
enum class AppInstall { GRUBBERY, GROUPS }

data class AppRow(
    val name: String,
    val state: AppState,
    /** The sentence under the name. */
    val detail: String,
    /** What installing would fetch, or null where installing is not the fix. */
    val install: AppInstall?,
    /** The ship's own words, when it had any. */
    val error: String? = null,
) {
    val canInstall: Boolean get() = install != null
}

/** Mail rides inside the grubbery desk, so it can be missing two ways. */
fun mailRow(availability: MailAvailability, error: String?): AppRow = when (availability) {
    MailAvailability.PRESENT -> AppRow("Mail", AppState.WORKING, "Answering on this ship.", null, error)
    MailAvailability.NO_GRUBBERY ->
        AppRow("Mail", AppState.MISSING, "Mail runs inside Grubbery, which this ship does not have.", AppInstall.GRUBBERY, error)
    MailAvailability.OLD_GRUBBERY ->
        AppRow("Mail", AppState.OUTDATED, "This ship's Grubbery predates Mail. It updates itself from its publisher.", null, error)
    MailAvailability.SIGNED_OUT -> AppRow("Mail", AppState.SIGNED_OUT, "Signed out of the ship.", null, error)
    MailAvailability.UNKNOWN -> AppRow("Mail", AppState.UNKNOWN, "Not asked yet.", null, error)
}

/**
 * The calendar ships inside Grubbery now, so a calendar that does not
 * answer is a statement about Grubbery: absent where the desk is absent,
 * out of date where the desk is here and predates it. [grubbery] is
 * whether the desk answered, or null before we have asked — and before
 * we know, nothing is offered, because installing on a guess is how
 * somebody installs a desk they already have.
 */
fun calendarRow(availability: CalendarAvailability, error: String?, grubbery: Boolean?): AppRow = when {
    availability == CalendarAvailability.PRESENT ->
        AppRow("Calendar", AppState.WORKING, "Answering on this ship.", null, error)
    availability == CalendarAvailability.SIGNED_OUT ->
        AppRow("Calendar", AppState.SIGNED_OUT, "Signed out of the ship.", null, error)
    availability == CalendarAvailability.UNKNOWN ->
        AppRow("Calendar", AppState.UNKNOWN, "Not asked yet.", null, error)
    grubbery == false ->
        AppRow("Calendar", AppState.MISSING, "The calendar comes with Grubbery, which this ship does not have.", AppInstall.GRUBBERY, error)
    grubbery == true ->
        AppRow("Calendar", AppState.OUTDATED, "This ship's Grubbery predates the calendar. It updates itself from its publisher.", null, error)
    else -> AppRow("Calendar", AppState.UNKNOWN, "Not answering. Checking whether Grubbery is here.", null, error)
}

/**
 * Lattice is the Grubbery desk itself, probed rather than subscribed:
 * null is "not asked yet", which is not the same as absent.
 */
fun latticeRow(installed: Boolean?, error: String? = null): AppRow = when (installed) {
    true -> AppRow("Lattice", AppState.WORKING, "Answering on this ship.", null, error)
    false -> AppRow("Lattice", AppState.MISSING, "Grubbery is not on this ship. Lattice comes with it.", AppInstall.GRUBBERY, error)
    null -> AppRow("Lattice", AppState.UNKNOWN, "Not asked yet.", null, error)
}

/**
 * Groups is its own desk, from its own publisher, and it is what chat
 * itself runs on: without it there are no groups, channels or DMs.
 */
fun groupsRow(installed: Boolean?, error: String? = null): AppRow = when (installed) {
    true -> AppRow("Groups", AppState.WORKING, "Answering on this ship.", null, error)
    false -> AppRow("Groups", AppState.MISSING, "Chat runs on Groups, which this ship does not have.", AppInstall.GROUPS, error)
    null -> AppRow("Groups", AppState.UNKNOWN, "Not asked yet.", null, error)
}

/**
 * Orrery is its own desk app from the same publisher, installed from
 * the ship's Grubbery shell rather than by kiln, so there is no install
 * to offer here: the row says where to go.
 */
fun orreryRow(availability: io.nisfeb.talon.orrery.OrreryAvailability, error: String? = null): AppRow = when (availability) {
    io.nisfeb.talon.orrery.OrreryAvailability.PRESENT -> AppRow("Orrery", AppState.WORKING, "Answering on this ship.", null, error)
    io.nisfeb.talon.orrery.OrreryAvailability.MISSING -> AppRow("Orrery", AppState.MISSING, "Not on this ship. Install it from the Grubbery shell on your ship.", null, error)
    io.nisfeb.talon.orrery.OrreryAvailability.SIGNED_OUT -> AppRow("Orrery", AppState.SIGNED_OUT, "Signed out of the ship.", null, error)
    io.nisfeb.talon.orrery.OrreryAvailability.UNKNOWN -> AppRow("Orrery", AppState.UNKNOWN, "Not asked yet.", null, error)
}

/**
 * Where a ship's Grubbery keeps the permissions its apps ask for.
 * Under /apps like any installed app's own pages, not under /grubbery,
 * which is the framework's internal nexuses.
 */
fun permitsUrl(shipUrl: String): String = shipUrl.trimEnd('/') + "/apps/grubbery/permits"
