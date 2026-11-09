package io.nisfeb.talon.notify

import io.nisfeb.talon.data.ThreadUnreadEntity
import io.nisfeb.talon.data.UnreadEntity

/**
 * The unread count on the app icon (sneagan, 2026-10-06: "add the ability
 * for iOS users to enable badges on the app icon"). Where there is none,
 * [NoopAppIconBadge]; see isAppIconBadgeSupported.
 */
fun interface AppIconBadge {
    fun set(count: Int)
}

val NoopAppIconBadge = AppIconBadge {}

/**
 * The icon's number: what notified, as %activity counts it (DMs, mentions,
 * replies), chats and threads alike. The same things a push adds one for
 * while the app is closed, so the two counts agree.
 */
fun badgeCount(unreads: List<UnreadEntity>, threads: List<ThreadUnreadEntity>): Int =
    unreads.sumOf { it.notifyCount } + threads.sumOf { it.notifyCount }

/**
 * Tell whoever pushes to this iPhone for [ship] the icon's true count, so
 * each alert while the app is closed adds one to it; null for badges off.
 * Its ship's pushes go through the relay's gateway, by handle; the relay's
 * own, by device id. Neither: nothing to tell.
 */
suspend fun reportBadge(ship: String, count: Int?, settings: RelaySettings, relay: RelayClient): Boolean {
    val gateway = settings.gatewayFor(ship)
    return when {
        settings.viaShipPush(ship) && gateway != null -> relay.gatewayBadge(gateway, count)
        settings.deviceIdFor(ship).isNotBlank() -> relay.setBadge(settings.deviceIdFor(ship), count)
        else -> true
    }
}
