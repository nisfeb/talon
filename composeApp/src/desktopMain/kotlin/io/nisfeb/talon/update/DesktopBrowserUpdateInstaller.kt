package io.nisfeb.talon.update

import io.nisfeb.talon.ui.DesktopUriHandler

/**
 * Desktop [UpdateInstallerHook]. Talon Desktop isn't shipped through
 * an auto-updating channel (no Sparkle / Squirrel / MSIX yet), so
 * "download + install" doesn't apply the way it does on Android. When
 * the user taps the update banner we open the GitHub Releases page in
 * their browser — they pick the artifact matching their OS (DMG /
 * MSI / AppImage), download, and replace manually.
 *
 * Reports the outcome back via [onFailure] so the existing
 * [UpdateBanner] renders a clear message; we don't add a bespoke
 * "OpenedInBrowser" state to keep the cross-platform banner surface
 * narrow. The message wording explains the actual situation.
 */
class DesktopBrowserUpdateInstaller(
    private val releasesPageUrl: String = "https://github.com/nisfeb/talon/releases/latest",
) : UpdateInstallerHook {

    override suspend fun download(
        manifest: UpdateManifest,
        onProgress: (Int) -> Unit,
        onReady: (String) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        runCatching { DesktopUriHandler.openUri(releasesPageUrl) }
            .onFailure {
                onFailure("Couldn't open browser. Visit $releasesPageUrl manually.")
                return
            }
        // The banner's "Failed" state is the closest match — it shows
        // the message and exposes a tap-to-retry that re-opens the
        // browser. Calling onFailure is a deliberate UX choice (over
        // a fake Ready state) so the user isn't promised an install
        // that's actually a manual swap of the AppImage / DMG / MSI.
        onFailure("Opened the releases page in your browser — download and replace your install.")
    }

    override fun install(apkPath: String) {
        // Never reached: desktop never transitions to Ready (download()
        // resolves via onFailure). Left a no-op so any future code that
        // arrives at this path doesn't crash.
    }
}
