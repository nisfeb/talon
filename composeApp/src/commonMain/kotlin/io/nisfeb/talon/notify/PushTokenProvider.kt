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

    /** Diagnostic snapshot for the Settings UI. The Android impl
     *  surfaces what `pm.queryBroadcastReceivers` and
     *  `UnifiedPush.getDistributors` actually returned — they should
     *  agree, but the connector library applies a filter that can
     *  silently drop visible-but-not-exported receivers, and a
     *  manifest `<queries>` mismatch can leave both empty. The
     *  default impl returns an empty report. */
    suspend fun diagnose(): DistributorReport = DistributorReport()
}

/** Snapshot of distributor discovery state. Intentionally flat
 *  strings so the panel can render them with `Text(...)` in any
 *  composition without per-platform glue. */
data class DistributorReport(
    /** Receivers handling `org.unifiedpush.android.distributor.REGISTER`
     *  visible to this app, as reported by the platform. Each entry
     *  is `<package>/<receiver-class>  exported=<bool>`. */
    val byPackageManager: List<String> = emptyList(),
    /** Distributor packages the UnifiedPush connector library
     *  considers usable (after its own export-filter and embedded-
     *  FCM exclusion). */
    val byConnector: List<String> = emptyList(),
    /** The endpoint URL that arrived via the connector's
     *  NEW_ENDPOINT broadcast, if any. Truncated for display. */
    val cachedEndpoint: String? = null,
    /** Free-form note for the panel — explains a non-obvious result
     *  ("query returned 0; <queries> block missing?"). Empty when
     *  there's nothing notable. */
    val note: String = "",
)

/** Returns null forever. Lets the registration flow render a
 *  user-facing "no push transport available" rather than silently
 *  doing nothing. */
object NoPushTokenProvider : PushTokenProvider {
    override val platform: String = "none"
    override suspend fun token(): String? = null
    override suspend fun diagnose(): DistributorReport = DistributorReport(
        note = "No push transport on this platform.",
    )
}
