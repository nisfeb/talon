package io.nisfeb.talon.notify

/**
 * Source of the platform's push notification token. Android wires
 * `FirebaseMessaging.getInstance().token`; desktop wires a long
 * random opaque string the relay correlates to a webhook channel.
 *
 * The "platform" string is what the relay routes by — Android pushes
 * via FCM, future iOS via APNS, desktop via webhook fan-out.
 */
interface PushTokenProvider {
    val platform: String

    /** Current token. Suspending so Android can wait on the
     *  Firebase task; impls that have it cached return immediately.
     *  Returns null on permanent failure (no Play Services, etc.). */
    suspend fun token(): String?
}

/** Returns null forever. Lets the registration flow render a
 *  user-facing "push not available on this build" rather than
 *  silently doing nothing. */
object NoPushTokenProvider : PushTokenProvider {
    override val platform: String = "none"
    override suspend fun token(): String? = null
}
