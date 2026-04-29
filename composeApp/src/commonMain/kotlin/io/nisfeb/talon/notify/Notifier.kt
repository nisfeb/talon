package io.nisfeb.talon.notify

/**
 * Platform-agnostic notification surface. Desktop fires native OS
 * balloons via Compose's TrayState; Android composeApp uses the
 * production NotificationManager when wired (currently a no-op).
 *
 * Title: short — typically the sender or chat name.
 * Body: the message preview (truncated to ~200 chars by callers).
 *
 * Implementations must be safe to call from any thread; the desktop
 * impl trampolines onto the AWT EDT internally.
 */
interface Notifier {
    fun notify(title: String, body: String)
}

/** No-op default for tests and platforms without a wired notifier. */
object NoopNotifier : Notifier {
    override fun notify(title: String, body: String) {}
}
