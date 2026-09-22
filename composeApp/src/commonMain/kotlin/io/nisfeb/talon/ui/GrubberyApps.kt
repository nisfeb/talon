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
enum class AppInstall {
    GRUBBERY,
    GROUPS,

    /**
     * A desk of the Grubbery shell rather than of kiln: orrery and
     * armillary are published by the same ship but are not part of
     * grubbery, so `|install` never brings them. The shell's own
     * desks/add route does, which is an authenticated call Talon can
     * make, and the owner then approves what the desk reaches on the
     * ship's own page.
     */
    ORRERY,
    ARMILLARY,
}

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
 * Orrery is its own desk app from the same publisher, added to the
 * ship's Grubbery shell rather than installed by kiln. That is an
 * authenticated call, so the row offers it, and the shell then syncs
 * the desk and asks the owner to approve what it reaches.
 */
fun orreryRow(availability: io.nisfeb.talon.orrery.OrreryAvailability, error: String? = null): AppRow = when (availability) {
    io.nisfeb.talon.orrery.OrreryAvailability.PRESENT -> AppRow("Orrery", AppState.WORKING, "Answering on this ship.", null, error)
    io.nisfeb.talon.orrery.OrreryAvailability.MISSING ->
        AppRow("Orrery", AppState.MISSING, "Not on this ship. Talon can add it to your Grubbery shell.", AppInstall.ORRERY, error)
    io.nisfeb.talon.orrery.OrreryAvailability.SIGNED_OUT -> AppRow("Orrery", AppState.SIGNED_OUT, "Signed out of the ship.", null, error)
    io.nisfeb.talon.orrery.OrreryAvailability.UNKNOWN -> AppRow("Orrery", AppState.UNKNOWN, "Not asked yet.", null, error)
}

/**
 * Armillary is its own desk app from the same publisher, added to the
 * shell the same way orrery is.
 */
fun armillaryRow(availability: io.nisfeb.talon.armillary.ArmillaryAvailability, error: String? = null): AppRow = when (availability) {
    io.nisfeb.talon.armillary.ArmillaryAvailability.PRESENT -> AppRow("Armillary", AppState.WORKING, "Answering on this ship.", null, error)
    io.nisfeb.talon.armillary.ArmillaryAvailability.MISSING ->
        AppRow("Armillary", AppState.MISSING, "Not on this ship. Talon can add it to your Grubbery shell.", AppInstall.ARMILLARY, error)
    io.nisfeb.talon.armillary.ArmillaryAvailability.SIGNED_OUT -> AppRow("Armillary", AppState.SIGNED_OUT, "Signed out of the ship.", null, error)
    io.nisfeb.talon.armillary.ArmillaryAvailability.UNKNOWN -> AppRow("Armillary", AppState.UNKNOWN, "Not asked yet.", null, error)
}

/**
 * Where a ship's Grubbery keeps the permissions its apps ask for.
 * Under /apps like any installed app's own pages, not under /grubbery,
 * which is the framework's internal nexuses.
 */
fun permitsUrl(shipUrl: String): String = shipUrl.trimEnd('/') + "/apps/grubbery/permits"
