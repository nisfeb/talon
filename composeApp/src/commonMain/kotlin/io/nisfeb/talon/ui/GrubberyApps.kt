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

    /** Nothing to say about the app: the session is over. */
    SIGNED_OUT,

    /** Not asked yet, or asked and the ship did not answer. */
    UNKNOWN,
}

/** Which desk an install would fetch, and from whom. */
enum class AppInstall {
    /** Grubbery where it is missing, and the stock desks its shell fetches: lattice, mail, the calendar. */
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

/** Mail is a stock desk of the Grubbery shell, so it can be missing two ways; one install fixes both. */
fun mailRow(availability: MailAvailability, error: String?): AppRow = when (availability) {
    MailAvailability.PRESENT -> AppRow("Mail", AppState.WORKING, "Answering on this ship.", null, error)
    MailAvailability.NO_GRUBBERY ->
        AppRow("Mail", AppState.MISSING, "Mail runs in Grubbery, which this ship does not have. Talon can add both.", AppInstall.GRUBBERY, error)
    MailAvailability.NOT_FETCHED ->
        AppRow("Mail", AppState.MISSING, "Grubbery is here, but not its Mail yet. Talon can fetch it.", AppInstall.GRUBBERY, error)
    MailAvailability.SIGNED_OUT -> AppRow("Mail", AppState.SIGNED_OUT, "Signed out of the ship.", null, error)
    MailAvailability.UNKNOWN -> AppRow("Mail", AppState.UNKNOWN, "Not asked yet.", null, error)
}

/**
 * The calendar is a stock desk of the Grubbery shell. One that does not
 * answer is missing, whether the shell is or only the desk, and the
 * install does what either needs, so there is nothing to tell apart.
 */
fun calendarRow(availability: CalendarAvailability, error: String?): AppRow = when (availability) {
    CalendarAvailability.PRESENT -> AppRow("Calendar", AppState.WORKING, "Answering on this ship.", null, error)
    CalendarAvailability.SIGNED_OUT -> AppRow("Calendar", AppState.SIGNED_OUT, "Signed out of the ship.", null, error)
    CalendarAvailability.UNKNOWN -> AppRow("Calendar", AppState.UNKNOWN, "Not asked yet.", null, error)
    CalendarAvailability.ABSENT ->
        AppRow("Calendar", AppState.MISSING, "Not on this ship yet. Talon can fetch it with Grubbery.", AppInstall.GRUBBERY, error)
}

/**
 * Lattice is a stock desk of the Grubbery shell, probed rather than
 * subscribed: null is "not asked yet", which is not the same as absent.
 */
fun latticeRow(installed: Boolean?, error: String? = null): AppRow = when (installed) {
    true -> AppRow("Lattice", AppState.WORKING, "Answering on this ship.", null, error)
    false -> AppRow("Lattice", AppState.MISSING, "Not on this ship yet. Talon can fetch it with Grubbery.", AppInstall.GRUBBERY, error)
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
