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
    /**
     * The sidecar an earlier build adopted for ships, now gone. A ship
     * still pointed at it is moved to [sfuBase], and ICE on its host is
     * moved too. Given at build time, so the old address stays out of
     * the source; empty retires nothing.
     */
    val retiredSfuBase: String = "",
) {
    /** A ship whose sidecar is the one an earlier build gave it. */
    fun retires(base: String): Boolean = retiredSfuBase.isNotEmpty() && base == retiredSfuBase

    /**
     * ICE an earlier build adopted: every server on our old or new host,
     * and not what this build ships. A ship that set its own servers has
     * at least one elsewhere and keeps them.
     */
    fun retiresIce(servers: List<IceServer>): Boolean {
        val ours = setOf(host(retiredSfuBase), host(sfuBase)) - ""
        val now = TrunkWire.defaultIce(this)
        return servers.isNotEmpty() && now.isNotEmpty() && servers != now &&
            servers.all { host(it.url) in ours }
    }

    companion object {
        /** Ship nothing; adopt nothing. */
        val None = CallDefaults()

        /** `http://h:1/x` or `turn:h:1?transport=tcp` → `h`. */
        internal fun host(url: String): String =
            url.substringAfter("://", url.substringAfter(':'))
                .substringBefore('/').substringBefore('?').substringBeforeLast(':')
    }
}
