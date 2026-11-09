package io.nisfeb.talon.util

/**
 * Showing a system picker on iOS, decided here so it can be tested.
 * UIKit drops a presentation asked for while the screen is mid
 * transition (the keyboard going down, a sheet closing), so that is
 * tried again a little later, a few times, before giving up.
 */
internal enum class PresentStep { PRESENT, RETRY, GIVE_UP }

/** Tries, [PRESENT_RETRY_MS] apart: a keyboard or a sheet has finished moving well inside them. */
internal const val PRESENT_ATTEMPTS = 5
internal const val PRESENT_RETRY_MS = 300L

/**
 * How long a presentation has to show before it counts as dropped,
 * looked at every [PRESENT_WATCH_MS]. One turn of the main queue was too
 * soon, and so was one look at 1.5 s: iOS's photo library can take about
 * four seconds to come up (iOS 26.6), and a picker that opened after the
 * look said "The picker did not open", then took the photo picked in it
 * nowhere.
 */
internal const val PRESENT_CONFIRM_MS = 10_000L
internal const val PRESENT_WATCH_MS = 500L

internal enum class PresentWatch { UP, WAIT, DROPPED }

/** A presented picker, [waitedMs] after it was asked for: [up] once UIKit shows it or has begun to. */
internal fun presentWatch(up: Boolean, waitedMs: Long, limitMs: Long = PRESENT_CONFIRM_MS): PresentWatch = when {
    up -> PresentWatch.UP
    waitedMs < limitMs -> PresentWatch.WAIT
    else -> PresentWatch.DROPPED
}

/**
 * A picker UIKit puts up after Talon stopped waiting for it, counted
 * dropped or its caller gone: nothing would take the photo picked in
 * it, so it closes at once instead of being left on screen.
 */
internal fun closeOnArrival(droppedAlready: Boolean, wanted: Boolean): Boolean = droppedAlready || !wanted

internal fun presentStep(busy: Boolean, attempt: Int, attempts: Int = PRESENT_ATTEMPTS): PresentStep = when {
    !busy -> PresentStep.PRESENT
    attempt + 1 < attempts -> PresentStep.RETRY
    else -> PresentStep.GIVE_UP
}
