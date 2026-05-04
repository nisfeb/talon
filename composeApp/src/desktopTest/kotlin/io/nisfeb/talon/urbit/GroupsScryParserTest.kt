package io.nisfeb.talon.urbit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wire-shape contract pin for the `%groups /v2/groups` parse. The
 * iteration-order-as-ordinal invariant is what the home list's
 * "host order" sort depends on — a refactor that swapped
 * `forEachIndexed` for `forEach`, or that reordered the parse, would
 * silently break that sort and these tests would catch it.
 */
class GroupsScryParserTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun parse(payload: String): GroupsScryResult =
        parseGroupsScry(json.parseToJsonElement(payload) as JsonObject)

    @Test
    fun `empty groups payload yields empty result`() {
        val out = parse("{}")
        assertTrue(out.groups.isEmpty())
        assertTrue(out.channelGroups.isEmpty())
    }

    @Test
    fun `single group with single channel parses both rows`() {
        val out = parse(
            """
            {
              "~host/g": {
                "meta": {"title": "G", "image": "https://example.com/g.png"},
                "channels": {
                  "chat/~host/general": { "meta": {"title": "general"} }
                }
              }
            }
            """,
        )
        assertEquals(1, out.groups.size)
        val g = out.groups[0]
        assertEquals("~host/g", g.flag)
        assertEquals("G", g.title)
        assertEquals("https://example.com/g.png", g.image)
        assertEquals(1, out.channelGroups.size)
        val ch = out.channelGroups[0]
        assertEquals("chat/~host/general", ch.nest)
        assertEquals("~host/g", ch.groupFlag)
        assertEquals("general", ch.title)
        assertEquals(0, ch.ordinal)
    }

    @Test
    fun `channel ordinals follow JSON iteration order`() {
        // The host-order sort key. Order in the wire (a, b, c) must
        // produce ordinals 0, 1, 2 — not alphabetic, not random.
        val out = parse(
            """
            {
              "~host/g": {
                "meta": {"title": "G"},
                "channels": {
                  "chat/~host/zebra":   { "meta": {"title": "Zebra"} },
                  "chat/~host/apple":   { "meta": {"title": "Apple"} },
                  "chat/~host/mango":   { "meta": {"title": "Mango"} }
                }
              }
            }
            """,
        )
        // Keep insertion order — kotlinx.serialization preserves it
        // for JsonObject. If a future refactor swaps the underlying
        // map type, this test will go red.
        assertEquals(
            listOf("chat/~host/zebra", "chat/~host/apple", "chat/~host/mango"),
            out.channelGroups.map { it.nest },
        )
        assertEquals(listOf(0, 1, 2), out.channelGroups.map { it.ordinal })
    }

    @Test
    fun `channels under different groups each get their own ordinal sequence`() {
        // Each group's ordinal count restarts from 0 — the field
        // is "ordinal within this group", not "global ordinal".
        val out = parse(
            """
            {
              "~host-a/g": {
                "meta": {"title": "A"},
                "channels": {
                  "chat/~host-a/x": {"meta": {"title": "X"}},
                  "chat/~host-a/y": {"meta": {"title": "Y"}}
                }
              },
              "~host-b/g": {
                "meta": {"title": "B"},
                "channels": {
                  "chat/~host-b/p": {"meta": {"title": "P"}},
                  "chat/~host-b/q": {"meta": {"title": "Q"}}
                }
              }
            }
            """,
        )
        val byGroup = out.channelGroups.groupBy { it.groupFlag }
        assertEquals(listOf(0, 1), byGroup.getValue("~host-a/g").map { it.ordinal })
        assertEquals(listOf(0, 1), byGroup.getValue("~host-b/g").map { it.ordinal })
    }

    @Test
    fun `missing group meta drops to nulls without crashing`() {
        // Older / partial wire shapes occasionally omit meta; the
        // parser must skip the field rather than throw.
        val out = parse(
            """
            {
              "~host/g": {
                "channels": {
                  "chat/~host/general": {}
                }
              }
            }
            """,
        )
        assertEquals(1, out.groups.size)
        assertNull(out.groups[0].title)
        assertNull(out.groups[0].image)
        assertEquals(1, out.channelGroups.size)
        assertNull(out.channelGroups[0].title)
    }

    @Test
    fun `blank meta strings are normalized to null`() {
        // Tlon sometimes writes empty title/image strings to clear
        // the field. The parser nulls them so downstream UIs don't
        // render an empty title row.
        val out = parse(
            """
            {
              "~host/g": {
                "meta": {"title": "", "image": ""},
                "channels": {
                  "chat/~host/c": {"meta": {"title": ""}}
                }
              }
            }
            """,
        )
        assertNull(out.groups[0].title)
        assertNull(out.groups[0].image)
        assertNull(out.channelGroups[0].title)
    }

    @Test
    fun `group with no channels still emits the group row`() {
        // Empty group case — surface it in the home list (with no
        // children). The bootstrap reconciler relies on this.
        val out = parse(
            """
            {
              "~host/empty": {
                "meta": {"title": "Empty"},
                "channels": {}
              }
            }
            """,
        )
        assertEquals(1, out.groups.size)
        assertEquals("Empty", out.groups[0].title)
        assertTrue(out.channelGroups.isEmpty())
    }
}
