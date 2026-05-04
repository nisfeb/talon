package io.nisfeb.talon.ui.screens

import io.nisfeb.talon.data.ChannelGroupEntity
import io.nisfeb.talon.data.FolderMemberEntity
import io.nisfeb.talon.data.GroupEntity
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.ui.ContactMap
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behavioral pin for the home-screen list builders. These pure
 * functions decide what the user sees in the DM list and folder
 * views; a regression here would either hide conversations or — as
 * happened recently — let group chat channels leak into the DMs
 * section. The channel-nest filter is the one the bug-fix audit
 * specifically called out.
 */
class HomeRowsTest {

    private fun msg(whom: String, sentMs: Long, id: String = "id-$whom-$sentMs") =
        MessageEntity(
            whom = whom, id = id, author = "~zod",
            sentMs = sentMs, contentJson = "{}", kind = "note",
        )

    private fun contactMapWith(
        groups: List<GroupEntity> = emptyList(),
        channelGroups: List<ChannelGroupEntity> = emptyList(),
    ) = ContactMap(
        contacts = emptyList(),
        clubs = emptyList(),
        groups = groups,
        channelGroups = channelGroups,
    )

    @Test
    fun `empty input produces empty output`() {
        val rows = buildHomeRows(
            allConvs = emptyList(),
            contactMap = contactMapWith(),
            expandedGroups = emptySet(),
            allUnreads = emptyMap(),
        )
        assertTrue(rows.isEmpty())
    }

    @Test
    fun `pure DMs land in DirectMessages section sorted by recency`() {
        val convs = listOf(
            msg("~zod", 100L) to 0,
            msg("~bus", 200L) to 0,
            msg("0vfoo", 150L) to 0,
        )
        val rows = buildHomeRows(
            allConvs = convs,
            contactMap = contactMapWith(),
            expandedGroups = emptySet(),
            allUnreads = emptyMap(),
        )
        // No groups → no Groups header.
        val header = rows.first()
        assertTrue(header is HomeRow.Header && header.tag == "dms")
        val flats = rows.filterIsInstance<HomeRow.Flat>()
        assertEquals(3, flats.size)
        // Sorted by sentMs descending.
        assertEquals("~bus", flats[0].m.whom)
        assertEquals("0vfoo", flats[1].m.whom)
        assertEquals("~zod", flats[2].m.whom)
    }

    @Test
    fun `channel-nest whoms never appear in DMs section even without group state`() {
        // The bug fix: a chat/host/name whom whose group hasn't synced yet
        // (so contactMap.groupOfChannel returns null) used to fall through
        // into the DMs section. Test that the shape-based filter excludes it.
        val convs = listOf(
            msg("~zod", 100L) to 0,
            msg("chat/~host/general", 200L) to 0,
            msg("diary/~host/notes", 150L) to 0,
            msg("heap/~host/pix", 175L) to 0,
        )
        val rows = buildHomeRows(
            allConvs = convs,
            contactMap = contactMapWith(),  // no groups synced yet
            expandedGroups = emptySet(),
            allUnreads = emptyMap(),
        )
        val flats = rows.filterIsInstance<HomeRow.Flat>()
        assertEquals(1, flats.size, "only the bare patp should be a Flat row")
        assertEquals("~zod", flats[0].m.whom)
    }

    @Test
    fun `group with no synced channels still emits a GroupHead`() {
        val rows = buildHomeRows(
            allConvs = emptyList(),
            contactMap = contactMapWith(
                groups = listOf(GroupEntity("~host/g1", title = "Group 1", image = null)),
            ),
            expandedGroups = emptySet(),
            allUnreads = emptyMap(),
        )
        val heads = rows.filterIsInstance<HomeRow.GroupHead>()
        assertEquals(1, heads.size)
        assertEquals("~host/g1", heads[0].flag)
        assertEquals(0, heads[0].childCount)
    }

    @Test
    fun `GroupHead totalUnread sums its channels' unread counts`() {
        val rows = buildHomeRows(
            allConvs = emptyList(),
            contactMap = contactMapWith(
                groups = listOf(GroupEntity("~host/g1", title = "G1", image = null)),
                channelGroups = listOf(
                    ChannelGroupEntity(nest = "chat/~host/a", groupFlag = "~host/g1"),
                    ChannelGroupEntity(nest = "chat/~host/b", groupFlag = "~host/g1"),
                ),
            ),
            expandedGroups = emptySet(),
            allUnreads = mapOf(
                "chat/~host/a" to 3,
                "chat/~host/b" to 2,
            ),
        )
        val head = rows.filterIsInstance<HomeRow.GroupHead>().single()
        assertEquals(5, head.totalUnread)
        assertEquals(2, head.childCount)
    }

    @Test
    fun `GroupHead expanded flag mirrors the input set`() {
        val convs = emptyList<Pair<MessageEntity, Int>>()
        val groups = listOf(
            GroupEntity("~host/g1", title = "G1", image = null),
            GroupEntity("~host/g2", title = "G2", image = null),
        )
        val rows = buildHomeRows(
            allConvs = convs,
            contactMap = contactMapWith(groups = groups),
            expandedGroups = setOf("~host/g2"),
            allUnreads = emptyMap(),
        )
        val heads = rows.filterIsInstance<HomeRow.GroupHead>().associateBy { it.flag }
        assertEquals(false, heads["~host/g1"]?.expanded)
        assertEquals(true, heads["~host/g2"]?.expanded)
    }

    @Test
    fun `groups follow user-defined order before alphabetical fallback`() {
        val groups = listOf(
            GroupEntity("~host/zebra", title = "Zebra", image = null),
            GroupEntity("~host/aardvark", title = "Aardvark", image = null),
            GroupEntity("~host/middle", title = "Middle", image = null),
        )
        val rows = buildHomeRows(
            allConvs = emptyList(),
            contactMap = contactMapWith(groups = groups),
            expandedGroups = emptySet(),
            allUnreads = emptyMap(),
            // User pinned Zebra first, then Middle. Aardvark gets the
            // alphabetical fallback at the end.
            groupOrderFlags = listOf("~host/zebra", "~host/middle"),
        )
        val heads = rows.filterIsInstance<HomeRow.GroupHead>().map { it.flag }
        assertEquals(
            listOf("~host/zebra", "~host/middle", "~host/aardvark"),
            heads,
        )
    }

    @Test
    fun `channels within a group are sorted unread-first then recency`() {
        val convs = listOf(
            msg("chat/~host/oldest", 100L) to 0,
            msg("chat/~host/recent-unread", 200L) to 1,
            msg("chat/~host/old-unread", 90L) to 1,
            msg("chat/~host/newest", 300L) to 0,
        )
        val rows = buildHomeRows(
            allConvs = convs,
            contactMap = contactMapWith(
                groups = listOf(GroupEntity("~host/g", title = "G", image = null)),
                channelGroups = listOf(
                    ChannelGroupEntity(nest = "chat/~host/oldest", groupFlag = "~host/g"),
                    ChannelGroupEntity(nest = "chat/~host/recent-unread", groupFlag = "~host/g"),
                    ChannelGroupEntity(nest = "chat/~host/old-unread", groupFlag = "~host/g"),
                    ChannelGroupEntity(nest = "chat/~host/newest", groupFlag = "~host/g"),
                ),
            ),
            expandedGroups = setOf("~host/g"),
            allUnreads = mapOf(
                "chat/~host/recent-unread" to 1,
                "chat/~host/old-unread" to 1,
            ),
        )
        val children = rows.filterIsInstance<HomeRow.GroupChild>().map { it.whom }
        assertEquals(
            listOf(
                // unread group first, sorted by recency descending
                "chat/~host/recent-unread",
                "chat/~host/old-unread",
                // then read group, sorted by recency descending
                "chat/~host/newest",
                "chat/~host/oldest",
            ),
            children,
        )
    }

    @Test
    fun `host-order mode sorts read channels by ChannelGroupEntity ordinal`() {
        val convs = listOf(
            msg("chat/~host/c", 300L) to 0,
            msg("chat/~host/a", 100L) to 0,
            msg("chat/~host/b", 200L) to 0,
        )
        val rows = buildHomeRows(
            allConvs = convs,
            contactMap = contactMapWith(
                groups = listOf(GroupEntity("~host/g", title = "G", image = null)),
                channelGroups = listOf(
                    // Host put `a` first, `b` second, `c` third —
                    // recency order is the inverse, so the test
                    // disambiguates Recent vs HostOrder unambiguously.
                    ChannelGroupEntity(nest = "chat/~host/a", groupFlag = "~host/g", ordinal = 0),
                    ChannelGroupEntity(nest = "chat/~host/b", groupFlag = "~host/g", ordinal = 1),
                    ChannelGroupEntity(nest = "chat/~host/c", groupFlag = "~host/g", ordinal = 2),
                ),
            ),
            expandedGroups = setOf("~host/g"),
            allUnreads = emptyMap(),
            groupChannelOrder = io.nisfeb.talon.ui.GroupChannelOrder.HostOrder,
        )
        val children = rows.filterIsInstance<HomeRow.GroupChild>().map { it.whom }
        assertEquals(
            listOf("chat/~host/a", "chat/~host/b", "chat/~host/c"),
            children,
        )
    }

    @Test
    fun `host-order mode keeps unread channels first regardless of host ordinal`() {
        // Unread-first floats over the secondary sort in either mode —
        // a host-ordered channel with unread=0 should still sit below
        // an unread channel that the host put later in the order.
        val convs = listOf(
            msg("chat/~host/early-read", 100L) to 0,
            msg("chat/~host/middle-read", 200L) to 0,
            msg("chat/~host/late-unread", 300L) to 1,
        )
        val rows = buildHomeRows(
            allConvs = convs,
            contactMap = contactMapWith(
                groups = listOf(GroupEntity("~host/g", title = "G", image = null)),
                channelGroups = listOf(
                    ChannelGroupEntity(nest = "chat/~host/early-read", groupFlag = "~host/g", ordinal = 0),
                    ChannelGroupEntity(nest = "chat/~host/middle-read", groupFlag = "~host/g", ordinal = 1),
                    ChannelGroupEntity(nest = "chat/~host/late-unread", groupFlag = "~host/g", ordinal = 2),
                ),
            ),
            expandedGroups = setOf("~host/g"),
            allUnreads = mapOf("chat/~host/late-unread" to 1),
            groupChannelOrder = io.nisfeb.talon.ui.GroupChannelOrder.HostOrder,
        )
        val children = rows.filterIsInstance<HomeRow.GroupChild>().map { it.whom }
        assertEquals(
            listOf(
                "chat/~host/late-unread",   // unread, floats up
                "chat/~host/early-read",    // ordinal 0
                "chat/~host/middle-read",   // ordinal 1
            ),
            children,
        )
    }

    @Test
    fun `recent mode is the default and ignores ordinal`() {
        // Default param (Recent). Ordinals are set high-to-low to
        // verify they don't influence the sort: recency wins.
        val convs = listOf(
            msg("chat/~host/oldest", 100L) to 0,
            msg("chat/~host/newest", 300L) to 0,
        )
        val rows = buildHomeRows(
            allConvs = convs,
            contactMap = contactMapWith(
                groups = listOf(GroupEntity("~host/g", title = "G", image = null)),
                channelGroups = listOf(
                    // newest gets a higher ordinal — would push it
                    // last in HostOrder. In Recent mode it stays first
                    // because it's actually the most-recent.
                    ChannelGroupEntity(nest = "chat/~host/oldest", groupFlag = "~host/g", ordinal = 0),
                    ChannelGroupEntity(nest = "chat/~host/newest", groupFlag = "~host/g", ordinal = 99),
                ),
            ),
            expandedGroups = setOf("~host/g"),
            allUnreads = emptyMap(),
        )
        val children = rows.filterIsInstance<HomeRow.GroupChild>().map { it.whom }
        assertEquals(
            listOf("chat/~host/newest", "chat/~host/oldest"),
            children,
        )
    }

    @Test
    fun `folder view groups members in user-defined ordinal order`() {
        val convs = listOf(
            msg("~zod", 100L) to 0,
            msg("~bus", 200L) to 0,
        )
        val rows = buildFolderRows(
            members = listOf(
                FolderMemberEntity(folderId = 1L, whom = "~bus", ordinal = 0),
                FolderMemberEntity(folderId = 1L, whom = "~zod", ordinal = 1),
            ),
            allConvs = convs,
            contactMap = contactMapWith(),
            expandedGroups = emptySet(),
            allUnreads = emptyMap(),
        )
        val flats = rows.filterIsInstance<HomeRow.Flat>().map { it.m.whom }
        // Ordinal-driven (NOT recency-sorted like the home view).
        assertEquals(listOf("~bus", "~zod"), flats)
    }

    @Test
    fun `folder view skips a whom that is also covered by a group member in the same folder`() {
        // User pinned both group ~host/g (KIND_GROUP) and the channel
        // chat/~host/a (KIND_WHOM) into folder 1. The group already
        // covers the channel — show the channel only as a GroupChild,
        // not as a separate Flat row.
        val convs = listOf(
            msg("chat/~host/a", 100L) to 0,
        )
        val rows = buildFolderRows(
            members = listOf(
                FolderMemberEntity(
                    folderId = 1L, whom = "~host/g", ordinal = 0,
                    kind = FolderMemberEntity.KIND_GROUP,
                ),
                FolderMemberEntity(
                    folderId = 1L, whom = "chat/~host/a", ordinal = 1,
                    kind = FolderMemberEntity.KIND_WHOM,
                ),
            ),
            allConvs = convs,
            contactMap = contactMapWith(
                groups = listOf(GroupEntity("~host/g", title = "G", image = null)),
                channelGroups = listOf(
                    ChannelGroupEntity(nest = "chat/~host/a", groupFlag = "~host/g"),
                ),
            ),
            expandedGroups = setOf("~host/g"),
            allUnreads = emptyMap(),
        )
        // The Flat row for chat/~host/a must NOT exist — the GroupChild
        // under the GroupHead is the only place it appears.
        val flats = rows.filterIsInstance<HomeRow.Flat>()
        assertTrue(flats.isEmpty(), "covered whom must not duplicate as Flat")
        // Confirm the GroupChild path does carry it.
        val children = rows.filterIsInstance<HomeRow.GroupChild>().map { it.whom }
        assertEquals(listOf("chat/~host/a"), children)
    }

    @Test
    fun `folder view returns empty when there are no members`() {
        val rows = buildFolderRows(
            members = emptyList(),
            allConvs = listOf(msg("~zod", 100L) to 0),
            contactMap = contactMapWith(),
            expandedGroups = emptySet(),
            allUnreads = emptyMap(),
        )
        assertTrue(rows.isEmpty())
    }

    @Test
    fun `folder Flat row carries the message and unread from allConvs`() {
        val rows = buildFolderRows(
            members = listOf(
                FolderMemberEntity(folderId = 1L, whom = "~zod", ordinal = 0),
            ),
            allConvs = listOf(msg("~zod", 100L) to 7),
            contactMap = contactMapWith(),
            expandedGroups = emptySet(),
            allUnreads = mapOf("~zod" to 7),
        )
        val flat = rows.filterIsInstance<HomeRow.Flat>().single()
        assertEquals("~zod", flat.m.whom)
        assertEquals(7, flat.unread)
    }

    @Test
    fun `folder view drops a member that has no message yet`() {
        // A folder member can be added before its first message arrives.
        // The current builder skips it (Flat requires a non-null
        // MessageEntity). Pin that — if it changes (e.g. emit a stub
        // row), tests should be the first to know.
        val rows = buildFolderRows(
            members = listOf(
                FolderMemberEntity(folderId = 1L, whom = "~zod", ordinal = 0),
            ),
            allConvs = emptyList(),  // no messages
            contactMap = contactMapWith(),
            expandedGroups = emptySet(),
            allUnreads = emptyMap(),
        )
        assertTrue(rows.isEmpty())
    }
}
