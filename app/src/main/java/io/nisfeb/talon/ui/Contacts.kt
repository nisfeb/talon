package io.nisfeb.talon.ui

import androidx.compose.runtime.Immutable
import io.nisfeb.talon.data.ChannelGroupEntity
import io.nisfeb.talon.data.ClubEntity
import io.nisfeb.talon.data.ContactEntity
import io.nisfeb.talon.data.GroupEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Synchronous directory built from snapshots of the contacts, clubs,
 * groups, and channel_groups tables. Screens collect the combined flow
 * once and pass the resulting map into rows so each row is an O(1)
 * lookup rather than a DB round-trip.
 *
 * Marked @Immutable so Compose can skip recomposing rows whose params
 * only include this map — we never mutate the internal maps after
 * construction.
 */
@Immutable
class ContactMap(
    contacts: List<ContactEntity> = emptyList(),
    clubs: List<ClubEntity> = emptyList(),
    groups: List<GroupEntity> = emptyList(),
    channelGroups: List<ChannelGroupEntity> = emptyList(),
) {
    private val byShip: Map<String, ContactEntity> =
        contacts.associateBy(ContactEntity::ship)
    private val byClub: Map<String, ClubEntity> = clubs.associateBy(ClubEntity::id)
    private val byGroupFlag: Map<String, GroupEntity> =
        groups.associateBy(GroupEntity::flag)
    private val nestToFlag: Map<String, String> =
        channelGroups.associate { it.nest to it.groupFlag }
    private val flagToNests: Map<String, List<String>> =
        channelGroups.groupBy(ChannelGroupEntity::groupFlag) { it.nest }
    private val nestToTitle: Map<String, String> =
        channelGroups.mapNotNull { ch ->
            ch.title?.takeIf { it.isNotBlank() }?.let { ch.nest to it }
        }.toMap()

    // ───────── ships ─────────

    fun nickname(ship: String): String? = byShip[ship]?.nickname
    fun avatar(ship: String): String? = byShip[ship]?.avatarUrl
    fun displayName(ship: String): String = nickname(ship) ?: ship
    fun contact(ship: String): ContactEntity? = byShip[ship]
    fun shipColor(ship: String): String? = byShip[ship]?.color

    /**
     * Hex color override for a conversation, if any. DMs pick up the
     * peer's profile color; group channels pick up the group's image
     * when that's a hex tint (image field doubles as a tint for groups
     * without a real avatar URL).
     */
    fun conversationColor(whom: String): String? = when {
        whom.startsWith("~") -> byShip[whom]?.color
        whom.startsWith("chat/") -> {
            val img = nestToFlag[whom]?.let { byGroupFlag[it]?.image }
            if (img != null && img.startsWith("#")) img else null
        }
        else -> null
    }

    // ───────── conversations ─────────

    /**
     * Human title for a `whom`:
     *  - "~peer"           → nickname or "~peer"
     *  - "0v..."           → club title or "0v..."
     *  - "chat/~host/name" → group title + "#channel" (group if known)
     */
    fun conversationLabel(whom: String): String = when {
        whom.startsWith("~") -> displayName(whom)
        whom.startsWith("0v") -> byClub[whom]?.title?.takeIf { it.isNotBlank() } ?: whom
        whom.startsWith("chat/") -> {
            // Prefer the channel's meta.title (what users set in Tlon);
            // fall back to a "#slug" derived from the nest's last segment.
            val channelTitle = nestToTitle[whom]
            val channel = channelTitle ?: ("#" + whom.substringAfterLast('/'))
            val groupTitle = nestToFlag[whom]?.let { byGroupFlag[it]?.title }
            if (!groupTitle.isNullOrBlank()) "$groupTitle · $channel" else channel
        }
        else -> whom
    }

    /**
     * Avatar URL to show for a conversation row. For 1:1 DMs this is the
     * peer's contact avatar; for group channels it's the enclosing group's
     * image. Returns null when no URL is set; the Avatar composable
     * falls back to a monogram.
     */
    fun conversationAvatar(whom: String): String? = when {
        whom.startsWith("~") -> byShip[whom]?.avatarUrl
        whom.startsWith("chat/") -> nestToFlag[whom]?.let { byGroupFlag[it]?.image }
        else -> null
    }

    // ───────── groups ─────────

    /** All known groups, sorted by title case-insensitively. */
    fun allGroups(): List<GroupEntity> =
        byGroupFlag.values.sortedBy { (it.title ?: it.flag).lowercase() }

    fun group(flag: String): GroupEntity? = byGroupFlag[flag]

    /** Group flag that owns this channel whom, or null. */
    fun groupOfChannel(whom: String): String? = nestToFlag[whom]

    /** All channel whoms known to belong to a group. */
    fun channelsOfGroup(flag: String): List<String> = flagToNests[flag].orEmpty()

    /**
     * Short channel label for nested rows under their group header —
     * prefers the channel's meta.title, falls back to a "#slug" when no
     * title is set on the ship side.
     */
    fun channelShortName(whom: String): String =
        nestToTitle[whom] ?: ("#" + whom.substringAfterLast('/'))

    companion object {
        val EMPTY = ContactMap()
    }
}

/** Combine every directory DAO flow into one ContactMap flow. */
fun contactMapFlow(
    contactsFlow: Flow<List<ContactEntity>>,
    clubsFlow: Flow<List<ClubEntity>>,
    groupsFlow: Flow<List<GroupEntity>>,
    channelGroupsFlow: Flow<List<ChannelGroupEntity>>,
): Flow<ContactMap> = combine(
    contactsFlow,
    clubsFlow,
    groupsFlow,
    channelGroupsFlow,
) { c, cl, g, cg -> ContactMap(c, cl, g, cg) }
