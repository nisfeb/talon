package io.nisfeb.talon.notifications

import android.content.Context

fun createNotifications(ctx: Context): Notifications = AndroidNotifications(ctx)
