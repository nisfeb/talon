package io.nisfeb.talon.ui

import androidx.compose.runtime.Immutable
import io.nisfeb.talon.data.ChannelGroupEntity
import io.nisfeb.talon.data.ClubEntity
import io.nisfeb.talon.data.ContactEntity
import io.nisfeb.talon.data.GroupEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn

/**
 * Synchronous directory built from snapshots of the contacts, clubs,
 * groups, and channel_groups tables. Screens collect the combined flow
 * once and pass the resulting map into rows so each row is an O(1)
 * lookup rather than a DB round-trip.
 */
@Immutable
data class ContactMap(
    val contacts: List<ContactEntity> = emptyList(),
    val clubs: List<ClubEntity> = emptyList(),
    val groups: List<GroupEntity> = emptyList(),
    val channelGroups: List<ChannelGroupEntity> = emptyList(),
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

    fun nickname(ship: String): String? = byShip[ship]?.nickname
    fun avatar(ship: String): String? = byShip[ship]?.avatarUrl
    fun displayName(ship: String): String = nickname(ship) ?: ship
    fun contact(ship: String): ContactEntity? = byShip[ship]
    fun shipColor(ship: String): String? = byShip[ship]?.color

    fun conversationColor(whom: String): String? = when {
        whom.startsWith("~") -> byShip[whom]?.color
        whom.startsWith("chat/") -> {
            val img = nestToFlag[whom]?.let { byGroupFlag[it]?.image }
            if (img != null && img.startsWith("#")) img else null
        }
        else -> null
    }

    fun conversationLabel(whom: String): String = when {
        whom.startsWith("~") -> displayName(whom)
        whom.startsWith("0v") -> byClub[whom]?.title?.takeIf { it.isNotBlank() } ?: whom
        whom.startsWith("chat/") ||
            whom.startsWith("diary/") ||
            whom.startsWith("heap/") -> {
            val channelTitle = nestToTitle[whom]
            val channel = channelTitle ?: ("#" + whom.substringAfterLast('/'))
            val groupTitle = nestToFlag[whom]?.let { byGroupFlag[it]?.title }
            if (!groupTitle.isNullOrBlank()) "$groupTitle · $channel" else channel
        }
        else -> whom
    }

    fun conversationAvatar(whom: String): String? = when {
        whom.startsWith("~") -> byShip[whom]?.avatarUrl
        whom.startsWith("chat/") ||
            whom.startsWith("diary/") ||
            whom.startsWith("heap/") -> nestToFlag[whom]?.let { byGroupFlag[it]?.image }
        else -> null
    }

    fun allGroups(): List<GroupEntity> =
        byGroupFlag.values.sortedBy { (it.title ?: it.flag).lowercase() }

    fun group(flag: String): GroupEntity? = byGroupFlag[flag]

    fun groupOfChannel(whom: String): String? = nestToFlag[whom]

    fun channelsOfGroup(flag: String): List<String> = flagToNests[flag].orEmpty()

    fun channelShortName(whom: String): String =
        nestToTitle[whom] ?: ("#" + whom.substringAfterLast('/'))

    companion object {
        val EMPTY = ContactMap()
    }
}

/**
 * Combine every directory DAO flow into one ContactMap flow.
 */
fun contactMapFlow(
    contactsFlow: Flow<List<ContactEntity>>,
    clubsFlow: Flow<List<ClubEntity>>,
    groupsFlow: Flow<List<GroupEntity>>,
    channelGroupsFlow: Flow<List<ChannelGroupEntity>>,
): Flow<ContactMap> = combine(
    contactsFlow.distinctUntilChanged(),
    clubsFlow.distinctUntilChanged(),
    groupsFlow.distinctUntilChanged(),
    channelGroupsFlow.distinctUntilChanged(),
) { c, cl, g, cg -> ContactMap(c, cl, g, cg) }
    .flowOn(Dispatchers.Default)
    // Conflate so cascading bootstrap emissions (e.g. all four DAOs
    // streaming initial values within a frame of each other) collapse
    // into one ContactMap rebuild instead of four. Building 5 maps
    // via associateBy/groupBy on every input tick was the single
    // biggest non-Main-thread allocation cost during login.
    .conflate()
