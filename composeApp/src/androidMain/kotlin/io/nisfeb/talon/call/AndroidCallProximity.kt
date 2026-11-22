package io.nisfeb.talon.call

import android.content.Context
import android.os.PowerManager
import io.nisfeb.talon.util.Log

/**
 * The screen off at the ear, as a phone call does it: a proximity wake
 * lock, which blanks the screen only while something covers the
 * sensor. iOS has its own ([IosCallProximity]); desktop has no sensor
 * and wires [CallProximity.Noop].
 *
 * Held only while [CallProximityEffect] says so, and let go when it
 * leaves the composition, so a call that ends by any road releases it.
 * The timeout is the backstop: a lock that outlived its call would blank
 * the screen in every pocket and against every cheek until a restart.
 */
class AndroidCallProximity(context: Context) : CallProximity {
    private val lock: PowerManager.WakeLock? = runCatching {
        val pm = context.applicationContext.getSystemService(PowerManager::class.java)
        if (pm?.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) == true) {
            pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "talon:call-proximity")
                .apply { setReferenceCounted(false) }
        } else {
            null
        }
    }.onFailure { Log.w(TAG, "no proximity lock", it) }.getOrNull()

    override fun screenOffNearFace(on: Boolean) {
        val l = lock ?: return
        runCatching {
            if (on) {
                if (!l.isHeld) l.acquire(MAX_HOLD_MS)
            } else if (l.isHeld) {
                // At the ear when the call ends, or as a headset takes the
                // sound: the screen comes back as the phone comes away,
                // not against the cheek, where a touch would land.
                l.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY)
            }
        }.onFailure { Log.w(TAG, "proximity lock", it) }
    }

    private companion object {
        const val TAG = "CallProximity"
        // ponytail: a call longer than this keeps its screen on at the ear; re-acquire on a timer if one ever does.
        const val MAX_HOLD_MS = 4L * 60 * 60 * 1000
    }
}
