package io.nisfeb.talon.notify

/**
 * Source of the per-device push endpoint the relay POSTs to.
 *
 * Today the only supported transport is UnifiedPush — the Android
 * impl asks the user's local UnifiedPush distributor (ntfy / NextPush
 * / Conversations / …) for an endpoint URL. The relay treats that URL
 * as opaque; anything POSTed to it gets routed to Talon on the device.
 *
 * Zero Google dependency by design — no FCM, no Play Services. Users
 * who don't have a UnifiedPush distributor installed get a
 * registration-failed message that links them to install one.
 */
interface PushTokenProvider {
    /** Routing tag stored alongside the endpoint on the relay.
     *  "unifiedpush" today; reserved for future transports
     *  (e.g. desktop webhook). */
    val platform: String

    /** Current push endpoint. Suspending because Android impls may
     *  block briefly on the distributor's bind/registration call.
     *  Returns null when no transport is available (e.g. no
     *  distributor installed) — the registration flow shows a
     *  user-facing "install ntfy or NextPush" message in that case. */
    suspend fun token(): String?
}

/** Returns null forever. Lets the registration flow render a
 *  user-facing "no push transport available" rather than silently
 *  doing nothing. */
object NoPushTokenProvider : PushTokenProvider {
    override val platform: String = "none"
    override suspend fun token(): String? = null
}
