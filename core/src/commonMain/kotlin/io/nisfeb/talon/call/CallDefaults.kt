package io.nisfeb.talon.call

/**
 * What a build ships as its fallback calling infrastructure.
 *
 * Passed in rather than read from generated build constants, because
 * this code is shared with processes that have no such constants — a
 * bridge, a recorder — and because a function that reads them can only
 * be exercised by a build configured to carry them, which is to say
 * never in a test.
 *
 * Empty means "ship nothing", which is a valid choice: a ship that has
 * been configured by hand keeps what it was given, and one that hasn't
 * is left alone rather than pointed at somebody's server by default.
 */
data class CallDefaults(
    /** `url|user|cred` entries separated by `;`. */
    val iceSpec: String = "",
    val sfuBase: String = "",
    val sfuGroup: String = "talon",
    val sfuKey: String = "",
) {
    companion object {
        /** Ship nothing; adopt nothing. */
        val None = CallDefaults()
    }
}
