package io.nisfeb.talon.notify

import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNUserNotificationCenter

/**
 * iOS message alerts are APNs pushes the system shows by itself, so
 * there is nothing to post here. Clearing removes the delivered ones
 * whose payload names the key (the relay puts the whom in `whom` and
 * in the thread id).
 */
class IosNotifier : Notifier {
    override fun notify(title: String, body: String, key: String?) {}

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
