package io.nisfeb.talon.urbit

import android.content.Context

/**
 * Construct a SharedPreferences-backed SessionStore for Android.
 * Pass `applicationContext` from your Activity / Application.
 */
fun createSessionStore(ctx: Context): SessionStore = AndroidSessionStore(ctx)
