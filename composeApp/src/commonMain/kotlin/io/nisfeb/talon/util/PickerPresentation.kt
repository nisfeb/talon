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
 * How long a presentation has to show before it counts as dropped. One
 * turn of the main queue, as before, was too soon: a picker that opened
 * a moment later took the photo picked in it nowhere, since the wait for
 * it had already been called off.
 */
internal const val PRESENT_CONFIRM_MS = 1_500L

internal fun presentStep(busy: Boolean, attempt: Int, attempts: Int = PRESENT_ATTEMPTS): PresentStep = when {
    !busy -> PresentStep.PRESENT
    attempt + 1 < attempts -> PresentStep.RETRY
    else -> PresentStep.GIVE_UP
}
