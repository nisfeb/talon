package io.nisfeb.talon.notify

/**
 * Whether a ring-cancel for [cancelId] is this device's to act on: a ring
 * it showed, or a call it is in ([ours]; nulls for what it has none of).
 *
 * The ship's own %trunk sends a cancel to every device it has (gwbtc/
 * trunk#1), so a device registered during a ring, or during an answered
 * call, hears the cancel for a call it never got. Acted on, a "hangup"
 * one ended whatever call the device was in.
 */
fun ringCancelIsOurs(cancelId: String, vararg ours: String?): Boolean =
    cancelId.isNotBlank() && ours.any { it == cancelId }
