package io.nisfeb.talon.notify

import platform.Foundation.NSUUID
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNUserNotificationCenter

/**
 * Chat alerts are APNs pushes the system shows by itself (the relay
 * puts the whom in `whom` and in the thread id), so a chat key posts
 * nothing here — that would double-notify. Everything else that
 * routes through [notify] — loop results, mail, invites — has no push
 * path and would be silently dropped, so it goes out as a local
 * UNNotificationRequest with the key as the thread id. Clearing
 * removes the delivered ones whose payload or thread names the key.
 */
class IosNotifier : Notifier {
    override fun notify(title: String, body: String, key: String?) {
        // Chat alerts are APNs pushes the system shows itself, keyed by the
        // chat whom — posting here would double-notify, so those are skipped.
        // Everything with no push path posts locally: a keyless alert, a mail
        // thread, an orrery action proposal ("action:"), Armillary credit
        // ("armillary"). The old guard dropped every keyed non-mail alert, so
        // action and armillary alerts silently never showed.
        // A DM request ("dm:") and a group invite ("invite:") have no push
        // either, though "dm:" names a ship.
        val postsLocally = key == null ||
            key.startsWith("mail:") || key.startsWith("action:") || key == "armillary" ||
            key.startsWith("dm:") || key.startsWith("invite:")
        if (!postsLocally) return
        val content = UNMutableNotificationContent()
        // The platform lib maps the properties read-only (the parent's
        // declaration wins over the mutable subclass's), so set them
        // through the Objective-C accessors it does export.
        content.setTitle(title)
        content.setBody(body)
        if (key != null) content.setThreadIdentifier(key)
        // A tap on one with a place to open goes there: CallPush.swift
        // routes a "notice" event's whom to openNotice, as for a ship's push.
        if (key != null && noticeLanding(key) != null) content.setUserInfo(mapOf<Any?, Any?>("event" to "notice", "whom" to key))
        // A stable identifier per key replaces a still-pending repeat
        // rather than stacking duplicates.
        val request = UNNotificationRequest.requestWithIdentifier(
            identifier = key ?: NSUUID().UUIDString,
            content = content,
            trigger = null,
        )
        UNUserNotificationCenter.currentNotificationCenter()
            .addNotificationRequest(request, null)
    }

    override fun clear(key: String) {
        val center = UNUserNotificationCenter.currentNotificationCenter()
        center.getDeliveredNotificationsWithCompletionHandler { delivered ->
            val ids = delivered.orEmpty().mapNotNull { n ->
                val req = (n as? UNNotification)?.request ?: return@mapNotNull null
                val whom = req.content.userInfo["whom"] as? String
                req.identifier.takeIf { whom == key || req.content.threadIdentifier == key }
            }
            if (ids.isNotEmpty()) center.removeDeliveredNotificationsWithIdentifiers(ids)
        }
    }
}
