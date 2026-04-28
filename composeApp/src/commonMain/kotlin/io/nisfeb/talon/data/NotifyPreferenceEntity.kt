// TEMPORARY DUPLICATE of app/src/main/java/io/nisfeb/talon/data/NotifyPreferenceEntity.kt
package io.nisfeb.talon.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Per-conversation notification level. Purely local — mirrors the
 * shape of Tlon's `%activity` volume settings (all / soft / loud /
 * hush) but kept client-side so the filter fires before we create a
 * system notification.
 *
 * Levels:
 *   "all"      — every incoming message fires a notification
 *   "mentions" — only when we're @-mentioned in the content
 *   "none"     — never notify (messages still sync)
 */
@Entity(tableName = "notify_preferences")
data class NotifyPreferenceEntity(
    @PrimaryKey val whom: String,
    val level: String,
)

object NotifyLevel {
    const val ALL = "all"
    const val MENTIONS = "mentions"
    const val NONE = "none"
    const val DEFAULT = MENTIONS
}
