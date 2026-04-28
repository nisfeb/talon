package io.nisfeb.talon.urbit

/**
 * Construct a JSON-file-backed SessionStore for desktop.
 */
fun createSessionStore(): SessionStore = DesktopSessionStore()
