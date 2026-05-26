package io.nisfeb.talon.urbit

/**
 * Hands a `urb://` link off to Lattice (the Urbit gemtext browser), if
 * it's installed. Same-shape interface in commonMain with a platform
 * impl per leaf, mirroring [io.nisfeb.talon.notify.Notifier].
 *
 *  - Android: resolves an `ACTION_VIEW` intent for the urb:// scheme.
 *    A null resolution means no app registered the scheme → NotInstalled.
 *  - Desktop: queries the OS scheme handler (xdg-mime on Linux) and
 *    shells out to the platform opener (xdg-open / open / rundll32).
 *
 * Callers render urb:// links tappable unconditionally and resolve
 * installed-or-not at tap time via [open] — there's no pre-detection
 * styling pass, so a freshly-installed Lattice works without Talon
 * re-rendering.
 */
interface UrbLinkLauncher {
    fun open(url: String): UrbLaunchResult

    companion object {
        /** Where to send users who tap a urb:// link with no Lattice
         *  installed. The install-prompt dialog links here. */
        const val INSTALL_URL = "https://github.com/nisfeb/lattice/releases/latest"
    }
}

enum class UrbLaunchResult {
    /** Lattice (or some urb:// handler) accepted the link. */
    Opened,

    /** No app on the device handles the urb:// scheme. */
    NotInstalled,

    /** A handler exists but the launch attempt errored. */
    Failed,
}

/**
 * Default no-op used by tests and any leaf that hasn't wired a real
 * launcher. Always reports [UrbLaunchResult.NotInstalled] so the UI
 * degrades to the install prompt rather than silently swallowing taps.
 */
object NoopUrbLinkLauncher : UrbLinkLauncher {
    override fun open(url: String): UrbLaunchResult = UrbLaunchResult.NotInstalled
}
