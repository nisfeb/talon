package io.nisfeb.talon.data

import java.util.UUID

/**
 * A globally-unique id for a synced assistant conversation/turn. Local
 * autoincrement PKs collide across devices (device A and B both mint
 * id=1), so sync keys on this instead — a random UUID, hex-encoded with
 * the dashes stripped. Matches the `lower(hex(randomblob(16)))` form the
 * Room migration uses to backfill rows that predate sync, so the two are
 * interchangeable (32 lowercase hex chars; v4 pins 6 of the 128 bits).
 */
fun newGid(): String = UUID.randomUUID().toString().replace("-", "")
