package io.nisfeb.talon.call

/**
 * What the platforms' own call surfaces are told: Android's ring
 * notification and telecom, iOS's CallKit. Decided here, in plain
 * Kotlin, so commonTest covers what only a phone can show.
 */

/**
 * The extras on the ring notification's intents. Only the Answer action
 * answers. Android fires the full-screen intent by itself over a locked
 * or dark screen, and the body is tapped to look: both carrying the
 * answer extras, a ring in a pocket went live with the mic on. They
 * carry [RINGING_FROM] instead, which shows the ring.
 */
object RingIntents {
    const val ANSWER_FROM = "answer_from"
    const val ANSWER_CALL_ID = "answer_call_id"
    const val RINGING_FROM = "ringing_from"

    enum class Tap { Answer, Show }

    fun extras(tap: Tap, from: String, callId: String): Map<String, String> = when (tap) {
        Tap.Answer -> mapOf(ANSWER_FROM to from, ANSWER_CALL_ID to callId)
        Tap.Show -> mapOf(RINGING_FROM to from)
    }

    /**
     * Each its own: Android takes two intents for one activity, differing
     * only in extras, as the same PendingIntent, so a shared code would
     * hand the full-screen intent the Answer's extras again. The decline
     * broadcast has callId's hash plus one.
     */
    fun requestCode(tap: Tap, callId: String): Int = when (tap) {
        Tap.Answer -> callId.hashCode()
        Tap.Show -> callId.hashCode() + 2
    }

    /** Whether an intent is about a ring, and so may show over the lock screen. */
    fun isRing(has: (String) -> Boolean): Boolean = has(ANSWER_FROM) || has(RINGING_FROM)
}
