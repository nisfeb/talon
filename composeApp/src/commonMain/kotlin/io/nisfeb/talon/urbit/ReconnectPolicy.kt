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

/**
 * Whether a session that has just (re)connected should run the
 * reconciliation scries, or only re-subscribe.
 *
 * A reconnect must be cheap. The first connect of a session always
 * reconciles; a reconnect that lands right after the previous pass
 * does not, because that pass already covered the window and the
 * subscriptions carry everything live. This bounds the cost to the
 * ship if anything ever reconnects in a loop again.
 */
fun shouldBootstrap(firstRun: Boolean, lastBootstrapMs: Long, nowMs: Long): Boolean =
    firstRun || lastBootstrapMs == 0L || nowMs - lastBootstrapMs >= BOOTSTRAP_MIN_GAP_MS
