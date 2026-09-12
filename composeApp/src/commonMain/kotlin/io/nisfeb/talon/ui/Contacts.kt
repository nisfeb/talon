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
    /** Ignore nicknames and mnemonyms; show the raw @p everywhere. */
    val alwaysPatp: Boolean = false,
    /** Whether a planet or a moon may show a looked-up word name. A
     *  comet's own name is never gated on this. */
    val nonCometNames: Boolean = false,
    /** Bumped as looked-up names arrive, so a map built before an
     *  answer landed is not equal to one built after. */
    val namesGeneration: Int = 0,
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

    /**
     * Changes exactly when [displayName] would start giving different
     * answers, so caches that store rendered names can key on it. Only
     * nicknames and the two naming flags matter — an avatar or colour
     * change rebuilds the ContactMap but must not invalidate rendered
     * message text.
     */
    val namesVersion: Int by lazy {
        var h = if (alwaysPatp) 1 else 0
        h = h * 31 + if (nonCometNames) 1 else 0
        h = h * 31 + namesGeneration
        for (c in contacts) {
            h = h * 31 + c.ship.hashCode()
            h = h * 31 + (c.nickname?.hashCode() ?: 0)
        }
        h
    }

    fun nickname(ship: String): String? = byShip[ship]?.nickname

    /** The ship behind a name we showed somewhere outside the app —
     *  a Recents entry hands back whatever it was given. */
    fun shipForDisplayName(name: String): String? {
        if (name.startsWith("~")) return name
        val n = name.trim()
        return byShip.keys.firstOrNull { displayName(it).equals(n, ignoreCase = true) }
    }
    fun avatar(ship: String): String? = byShip[ship]?.avatarUrl
    fun displayName(ship: String): String =
        if (alwaysPatp) {
            ship
        } else {
            nickname(ship) ?: handle(ship)
        }
    /**
     * What a ship is called with nicknames set aside.
     *
     * Its word name if it has one, otherwise its @p. This is the line
     * that goes under a nickname, and for a comet it is the only thing
     * ever shown: a comet's @p is the fifty-six characters its name
     * exists to replace, so no surface puts one in front of anybody.
     */
    fun handle(ship: String): String =
        // A comet spells its own fingerprint, so it needs nothing
        // fetched and nobody's permission.
        Mnemonym.display(ship)
            ?: (if (nonCometNames) AzimuthNames.nameFor(ship) else null)
            ?: ship

    fun contact(ship: String): ContactEntity? = byShip[ship]
    fun shipColor(ship: String): String? = byShip[ship]?.color

    fun conversationColor(whom: String): String? = when {
        whom.startsWith("~") -> byShip[whom]?.color
        whom.startsWith("chat/") || whom.startsWith("heap/") || whom.startsWith("diary/") -> {
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
 *
 * Status updates land via %contacts /v1/news roughly every minute on
 * an active network. They change `status` + `statusUpdatedMs` but
 * none of the fields ContactMap actually uses (ship, nickname,
 * avatarUrl, color). The default `distinctUntilChanged()` would still
 * pass those re-emissions through because the entity equals differs,
 * which then rebuilds ContactMap and recomposes every consumer
 * (DmListScreen, every chat row's avatar/label, etc.) for nothing.
 *
 * The custom equivalence below treats two contact lists as equal
 * when their *display* projection matches — status / bio / mod-at
 * differences pass silently. Consumers that need fresh status (only
 * ContactProfileSheet today) read directly from
 * [io.nisfeb.talon.data.ContactDao.streamOne].
 */
fun contactMapFlow(
    contactsFlow: Flow<List<ContactEntity>>,
    clubsFlow: Flow<List<ClubEntity>>,
    groupsFlow: Flow<List<GroupEntity>>,
    channelGroupsFlow: Flow<List<ChannelGroupEntity>>,
    // Defaulted to the app-wide switch so the ~17 call sites don't
    // each have to thread a UiSettings reference through; flipping
    // it re-emits every ContactMap and re-renders names.
    alwaysPatpFlow: Flow<Boolean> = ShipNames.alwaysPatp,
    nonCometNamesFlow: Flow<Boolean> = AzimuthNames.enabled,
    /** Ticks as looked-up names arrive, so a row drawn before the
     *  answer landed is redrawn once it has. */
    namesGenerationFlow: Flow<Int> = AzimuthNames.generation,
): Flow<ContactMap> = combine(
    contactsFlow.distinctUntilChanged(::sameContactDisplay),
    clubsFlow.distinctUntilChanged(),
    groupsFlow.distinctUntilChanged(),
    channelGroupsFlow.distinctUntilChanged(),
    // The three naming inputs ride one slot: `combine` only types five.
    combine(
        alwaysPatpFlow.distinctUntilChanged(),
        nonCometNamesFlow.distinctUntilChanged(),
        namesGenerationFlow.distinctUntilChanged(),
    ) { patp, nonComet, gen -> Triple(patp, nonComet, gen) },
) { c, cl, g, cg, naming ->
    ContactMap(c, cl, g, cg, naming.first, naming.second, naming.third)
}
    .flowOn(Dispatchers.Default)
    // Conflate so cascading bootstrap emissions (e.g. all four DAOs
    // streaming initial values within a frame of each other) collapse
    // into one ContactMap rebuild instead of four. Building 5 maps
    // via associateBy/groupBy on every input tick was the single
    // biggest non-Main-thread allocation cost during login.
    .conflate()

// `internal` so the test source set can drive the predicate without
// bringing up a full Room DAO. Behaviour pinned in
// commonTest/.../ContactsTest.kt.
internal fun sameContactDisplay(a: List<ContactEntity>, b: List<ContactEntity>): Boolean {
    if (a === b) return true
    if (a.size != b.size) return false
    for (i in a.indices) {
        val x = a[i]
        val y = b[i]
        if (x.ship != y.ship ||
            x.nickname != y.nickname ||
            x.avatarUrl != y.avatarUrl ||
            x.color != y.color
        ) {
            return false
        }
    }
    return true
}
