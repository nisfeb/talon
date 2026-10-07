package io.nisfeb.talon.update

/**
 * Either channel produces an UpdateManifest or null. Implementations
 * MUST NOT throw — return null on any failure (network, parse,
 * subscription drop). Logging is the implementation's responsibility.
 */
interface UpdateChecker {
    suspend fun check(): UpdateManifest?
}

/**
 * Ask [checker] now, and every [everyMs] after for as long as the caller
 * runs, handing each answer to [onManifest]. The checker's own throttle
 * decides how often that reaches the network. Desktop asked only at
 * launch: Talon lives in the tray for days, so a release that came out
 * after a launch was never offered ("the desktop client is not prompting
 * them upgrade").
 */
suspend fun keepCheckingForUpdates(checker: UpdateChecker, everyMs: Long, onManifest: (UpdateManifest) -> Unit): Nothing {
    while (true) {
        checker.check()?.let(onManifest)
        kotlinx.coroutines.delay(everyMs)
    }
}

/** How often a running app looks again; [HttpUpdateChecker]'s throttle spaces the requests. */
const val UPDATE_RECHECK_MS = 60L * 60L * 1000L

/** How long after a check the next one may go to the network. */
const val UPDATE_MIN_INTERVAL_MS = 6L * 60L * 60L * 1000L

/**
 * Surface state for the banner. Idle when nothing to show. Available
 * holds the manifest waiting for user action. Downloading carries
 * progress 0..99 — the 100 mark is reserved for the transition to
 * Ready, which means the APK is on disk and verified. Failed flips
 * when a download or hash check broke; the banner shows the message
 * and lets the user retry.
 */
sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data class Available(val manifest: UpdateManifest) : UpdateStatus
    data class Downloading(val manifest: UpdateManifest, val progress: Int) : UpdateStatus
    data class Ready(val manifest: UpdateManifest, val apkPath: String, val hint: String) : UpdateStatus
    data class Failed(val manifest: UpdateManifest?, val message: String) : UpdateStatus
}
