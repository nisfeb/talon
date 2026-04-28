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
 * Surface state for the banner. Idle when nothing to show. Available
 * holds the manifest waiting for user action. Downloading carries
 * progress 0..100. Ready means the APK is on disk and verified.
 * Failed flips when a download or hash check broke; the banner shows
 * the message and lets the user retry.
 */
sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data class Available(val manifest: UpdateManifest) : UpdateStatus
    data class Downloading(val manifest: UpdateManifest, val progress: Int) : UpdateStatus
    data class Ready(val manifest: UpdateManifest, val apkPath: String) : UpdateStatus
    data class Failed(val manifest: UpdateManifest?, val message: String) : UpdateStatus
}
