package io.nisfeb.talon.notifications

import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import io.nisfeb.talon.util.Log

/**
 * SystemTray-backed notifications for desktop. Falls back to no-op
 * on hosts where SystemTray isn't supported (e.g. some Linux DEs
 * without an AppIndicator service running).
 *
 * The tray icon is currently a transparent 16x16 placeholder. Stage E
 * replaces it with the branded launcher icon when packaging is wired.
 */
class DesktopNotifications : Notifications {

    private val trayIcon: TrayIcon? by lazy {
        if (!SystemTray.isSupported()) {
            Log.w(TAG, "SystemTray not supported on this host; notifications will no-op")
            return@lazy null
        }
        val img = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        val icon = TrayIcon(img, "Talon").apply { isImageAutoSize = true }
        runCatching {
            SystemTray.getSystemTray().add(icon)
        }.onFailure {
            Log.w(TAG, "Failed to register tray icon: ${it.message}")
            return@lazy null
        }
        icon
    }

    override fun showMessage(
        whom: String,
        postId: String?,
        parentId: String?,
        title: String,
        body: String,
        sentMs: Long,
    ) {
        trayIcon?.displayMessage(title, body, TrayIcon.MessageType.INFO)
    }

    override fun showWatchwordHit(
        whom: String,
        term: String,
        postId: String,
        parentId: String?,
        title: String,
        body: String,
        sentMs: Long,
    ) {
        // Compose the user-facing title here since desktop's
        // displayMessage doesn't have the "$terms in $label" template
        // the Android side uses.
        trayIcon?.displayMessage("$term in $title", body, TrayIcon.MessageType.INFO)
    }

    override fun showDailyDigest(title: String, body: String) {
        trayIcon?.displayMessage(title, body, TrayIcon.MessageType.INFO)
    }

    override fun cancelAllForChat(whom: String) {
        // Tray notifications can't be cancelled after display.
    }

    private companion object {
        private const val TAG = "DesktopNotifications"
    }
}
