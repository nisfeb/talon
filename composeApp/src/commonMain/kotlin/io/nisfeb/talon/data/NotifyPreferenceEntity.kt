package io.nisfeb.talon.data

import androidx.room.Entity
import androidx.compose.runtime.Immutable
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
@Immutable
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

/**
 * The key a group's own level is kept under, beside the chats' own in
 * the same table and the same synced bucket: "group/~host/name". No chat
 * is keyed so: a channel is "chat/…", a DM a ship, a club an id.
 */
fun groupLevelKey(flag: String): String = "group/$flag"

/**
 * A chat's level: its own where it has one, else its group's, else the
 * default. Every reader of a level comes here, and trunk resolves the
 * same way, so a channel follows its group until it is given its own.
 */
fun effectiveLevel(own: String?, groupLevel: String?): String = own ?: groupLevel ?: NotifyLevel.DEFAULT

/** [levels], each a chat's own, with the group's filled in for a channel in [groupOf] that has none of its own. */
fun withGroupLevels(levels: Map<String, String>, groupOf: Map<String, String>): Map<String, String> =
    levels + groupOf.mapNotNull { (nest, flag) ->
        if (nest in levels) null else levels[groupLevelKey(flag)]?.let { nest to it }
    }

/** [whom]'s level as it applies: its own, else its group's, else the default. */
suspend fun AppDatabase.notifyLevelOf(whom: String): String = effectiveLevel(
    notifyPrefs().levelFor(whom),
    groups().channelGroupFor(whom)?.groupFlag?.let { notifyPrefs().levelFor(groupLevelKey(it)) },
)
