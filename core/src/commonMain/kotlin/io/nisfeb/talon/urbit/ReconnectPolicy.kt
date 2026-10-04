package io.nisfeb.talon.urbit

/**
 * How a client of an Urbit ship is allowed to reconnect. Both rules
 * here exist because a ship runs its events one at a time, so a
 * handful of clients retrying in step can consume the whole thing
 * while every metric still looks healthy.
 */

/**
 * [ms] scaled by 0.5 to 1.5. A pier restart drops every client at the
 * same instant; without jitter a fixed backoff marches them all back
 * on the same tick and the ship meets the entire fleet at once.
 */
fun jittered(ms: Long): Long =
    (ms * kotlin.random.Random.nextDouble(0.5, 1.5)).toLong().coerceAtLeast(1L)

/** A reconnect inside this window re-registers subscriptions only. */
const val BOOTSTRAP_MIN_GAP_MS = 60_000L

/** A stream that heard something this recently missed little: a reconnect after it re-subscribes only. */
const val SHORT_OUTAGE_MS = 2 * 60_000L

/** But the counts are reconciled at least this often, so what short gaps missed does not drift for long. */
const val RECONCILE_EVERY_MS = 15 * 60_000L

/**
 * Whether a session that has just (re)connected should run the
 * reconciliation scries, or only re-subscribe.
 *
 * A reconnect must be cheap. The first connect of a session always
 * reconciles; a reconnect that lands right after the previous pass
 * does not, because that pass already covered the window and the
 * subscriptions carry everything live. Nor does one after a short
 * outage ([lastHeardMs], when the old stream last heard anything, is
 * recent): ~ricsul broke its streams every few minutes, and each break
 * re-ran the whole pass (forty requests, the unread scry among them)
 * until the ship could do nothing else. A long outage, or none for
 * [RECONCILE_EVERY_MS], still reconciles.
 */
fun shouldBootstrap(firstRun: Boolean, lastBootstrapMs: Long, nowMs: Long, lastHeardMs: Long = 0L): Boolean {
    if (firstRun || lastBootstrapMs == 0L) return true
    val since = nowMs - lastBootstrapMs
    if (since < BOOTSTRAP_MIN_GAP_MS) return false
    val shortOutage = lastHeardMs != 0L && nowMs - lastHeardMs < SHORT_OUTAGE_MS
    return !shortOutage || since >= RECONCILE_EVERY_MS
}

/**
 * The pause before opening a stream again, given the one so far and how
 * long the stream that just ended lived ([livedMs]; null if it never
 * opened). Back to [first] only after a stream that lived [healthyMs]:
 * reset on being let in, a stream accepted and then broken at once came
 * back every few seconds for as long as the ship kept breaking it.
 */
fun pauseAfterStream(pause: Long, livedMs: Long?, first: Long, healthyMs: Long): Long =
    if (livedMs != null && livedMs >= healthyMs) first else pause

/**
 * How old the last full read of posts may be for a catch-up to read only
 * what changed since: Tlon's client reads everything again past three days.
 */
const val CHANGES_MAX_AGE_MS = 3 * 24 * 60 * 60_000L

/** How far before the last read a /changes read starts: the device's clock and the ship's need not agree. */
const val CHANGES_OVERLAP_MS = 5 * 60_000L

/**
 * Whether a catch-up reads groups-ui's /changes since [readMs], when the
 * last full read of posts began (0: none yet), instead of init-posts'
 * newest ten of every chat again. Not on a ship that serves no /changes.
 */
fun useChanges(readMs: Long, nowMs: Long, unserved: Boolean): Boolean =
    !unserved && readMs > 0L && nowMs - readMs < CHANGES_MAX_AGE_MS

/** A path the ship drops again this soon after it was watched again is in a loop: open a new channel instead. */
const val QUIT_AGAIN_MS = 60_000L

/**
 * Whether a subscription the ship dropped ([lastQuitMs]: when it last
 * dropped that path, null if never) is watched again on the same channel.
 */
fun resubscribeAfterQuit(lastQuitMs: Long?, nowMs: Long): Boolean =
    lastQuitMs == null || nowMs - lastQuitMs >= QUIT_AGAIN_MS

/** The deep history pass: nothing kept here, or the newest is over a day old. */
fun needsDeepHistory(newestSentMs: Long?, nowMs: Long): Boolean =
    newestSentMs == null || nowMs - newestSentMs > 24 * 60 * 60 * 1000L
