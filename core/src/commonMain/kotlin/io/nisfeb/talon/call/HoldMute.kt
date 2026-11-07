package io.nisfeb.talon.call

/**
 * Hold has no wire in %trunk, so being put on hold mutes us and being
 * taken off it unmutes, but only where the hold is what muted. A flag
 * alone outlived its call: a line that ended while held left it set, the
 * next line joined muted, and the next hold and unhold turned on a mic
 * its owner never had. [ended] is the call or line going.
 */
class HoldMute {
    private var muted = false

    /** Put on hold: mute, if [unmuted], and remember the hold did it. */
    fun hold(unmuted: Boolean, mute: () -> Unit) {
        if (!unmuted) return
        mute()
        muted = true
    }

    /** Taken off hold: unmute only what the hold muted. */
    fun unhold(unmute: () -> Unit) {
        if (!muted) return
        muted = false
        unmute()
    }

    /** The call or line ended: a hold it had is over with it. */
    fun ended() {
        muted = false
    }
}
