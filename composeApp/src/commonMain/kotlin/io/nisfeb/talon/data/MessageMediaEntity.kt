package io.nisfeb.talon.data

import androidx.room.Entity
import androidx.room.Index

/**
 * Derived media index: one row per (message, url) where the message
 * mentions a categorisable URL or first-class image. Populated in the
 * same transaction as [MessageEntity] inserts via [PostIngest], plus a
 * one-shot backfill on first launch after upgrade.
 *
 * The composite PK on (whom, messageId, url) lets a single message
 * contribute multiple rows (a post with three image URLs lands as
 * three rows). Duplicate URLs in the same message are de-duplicated
 * to one row by the PK.
 *
 * The (whom, category, sentMs) index covers both queries the UI
 * issues: the grouped count (`SELECT category, COUNT(*) GROUP BY`)
 * and the per-category drilldown (`WHERE whom AND category ORDER BY
 * sentMs DESC`).
 */
@Entity(
    tableName = "message_media",
    primaryKeys = ["whom", "messageId", "url"],
    indices = [Index(value = ["whom", "category", "sentMs"])],
)
data class MessageMediaEntity(
    val whom: String,
    val messageId: String,
    val url: String,
    val category: String,        // MediaCategory.name
    val displayText: String?,    // e.g. "🎙 Voice 12s"; null if URL is bare
    val sentMs: Long,
    val author: String,
)
