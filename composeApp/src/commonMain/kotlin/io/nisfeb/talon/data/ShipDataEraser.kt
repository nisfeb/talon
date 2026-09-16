package io.nisfeb.talon.data

/**
 * Deleting everything a ship left on this device.
 *
 * Signing out drops the credentials; this drops what was cached under
 * them — the database of that ship's chats, groups and unreads, and
 * the small per-ship preference files beside it. Separate from the
 * session store on purpose: forgetting who you are and forgetting what
 * you read are different things, and somebody removing an account they
 * no longer use means the second.
 *
 * An interface rather than expect/actual because the platforms need
 * different things to do the job — Android wants a Context, the others
 * want a directory — and the hosts already build this sort of thing.
 *
 * Each implementation names the files through the same sanitiser that
 * wrote them. A second copy of that rule, drifted by one character,
 * would delete nothing and report success.
 */
interface ShipDataEraser {

    /**
     * Remove [ship]'s local data. Best effort: a file that will not
     * delete is reported, not thrown, because the session is already
     * gone by the time this runs and failing here would leave the user
     * signed out with no way to finish.
     */
    fun erase(ship: String): Result<Unit>

    companion object {
        /** For hosts and tests that store nothing per ship. */
        val Noop: ShipDataEraser = object : ShipDataEraser {
            override fun erase(ship: String) = Result.success(Unit)
        }
    }
}
