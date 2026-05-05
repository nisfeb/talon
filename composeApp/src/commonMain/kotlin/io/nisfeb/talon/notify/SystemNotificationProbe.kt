package io.nisfeb.talon.notify

/**
 * One-call snapshot of the OS-level signals that affect whether
 * Talon's notifications actually reach the user. The Settings →
 * Notification Health panel polls this on render so the user can
 * spot "the OS is going to silently drop my pushes" before missing
 * one.
 *
 * Per-platform impls fill what they know. Desktop leaves everything
 * null today (none of these constraints exist on a desktop OS in
 * the same shape). Android fills [notificationsAllowed],
 * [batteryOptimizationsExempt], and [backgroundRestricted].
 */
data class SystemNotificationState(
    /** Has the user granted POST_NOTIFICATIONS (Android 13+)?
     *  null = pre-Tiramisu (always allowed) or platform doesn't
     *  surface this. */
    val notificationsAllowed: Boolean? = null,

    /** Has the user added Talon to the battery-optimization
     *  exempt list? Required for the foreground service to keep
     *  running through Doze. null when not applicable. */
    val batteryOptimizationsExempt: Boolean? = null,

    /** Has the OS flagged the app as background-restricted
     *  (`ActivityManager.isBackgroundRestricted`)? When true,
     *  EVERY background mechanism is disabled including FCM —
     *  this is the loudest red flag a user can hit. */
    val backgroundRestricted: Boolean? = null,
)

/**
 * Snapshots the OS state on demand and exposes deep-link launchers
 * for the Settings → Notification Health panel to wire to "Fix"
 * buttons.
 */
interface SystemNotificationProbe {
    fun snapshot(): SystemNotificationState

    /** Open the OS Settings page where the user can toggle the
     *  battery-optimization exemption. Returns true if the
     *  intent dispatched. */
    fun openBatteryOptimizationSettings(): Boolean = false

    /** Open the OS Settings page for Talon's per-app permissions
     *  (notifications + background restriction live there on
     *  modern Android). Returns true if the intent dispatched. */
    fun openAppDetailsSettings(): Boolean = false
}

object NoopSystemNotificationProbe : SystemNotificationProbe {
    override fun snapshot() = SystemNotificationState()
}
