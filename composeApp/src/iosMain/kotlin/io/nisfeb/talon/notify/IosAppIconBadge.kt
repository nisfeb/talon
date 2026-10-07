package io.nisfeb.talon.notify

import platform.UIKit.UIApplication

/** The number on Talon's home-screen icon. The permission was asked for
 *  with the alerts' (CallPush.swift); the owner can still turn Badges off
 *  for Talon in the iPhone's Settings, and then this shows nothing.
 *  ponytail: applicationIconBadgeNumber is deprecated from iOS 17 but
 *  works back to the app's iOS 15; UNUserNotificationCenter.setBadgeCount
 *  once the floor is 16. */
object IosAppIconBadge : AppIconBadge {
    @Suppress("DEPRECATION")
    override fun set(count: Int) {
        UIApplication.sharedApplication.applicationIconBadgeNumber = count.toLong()
    }
}
