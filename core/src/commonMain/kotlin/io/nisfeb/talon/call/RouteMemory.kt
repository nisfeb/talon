package io.nisfeb.talon.call

/**
 * Whether the audio route the owner picked should be put back when a
 * session starts.
 *
 * A reconnect is indistinguishable from the end of a call where the
 * routing is done: losing the network closes every link, and new ones
 * open a moment later. Forgetting the pick in between put a call that
 * dropped for a few seconds back on the earpiece with the speaker still
 * ticked in the pane. Keeping it forever is the other failure: a
 * speakerphone pick must not open the next call, hours later, in
 * public.
 *
 * So it is kept for [REMEMBER_MS], which is longer than a reconnect
 * (six tries, doubling from a second) and shorter than a walk to the
 * next conversation.
 */
fun routeSurvives(quietSinceMs: Long, nowMs: Long, rememberMs: Long = REMEMBER_MS): Boolean =
    quietSinceMs == 0L || nowMs - quietSinceMs <= rememberMs

/** How long a pick outlives the session it was made in. */
const val REMEMBER_MS = 2 * 60 * 1000L
