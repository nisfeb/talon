package io.nisfeb.talon.urbit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupEventRouterTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun classify(raw: String): GroupEventIntent? =
        classifyGroupEvent(json.parseToJsonElement(raw).jsonObject)

    // ─── channel.add (the original bug) ─────────────────────────

    @Test
    fun `r-group channel add produces AddChannel with nest and title`() {
        // This is the case the user hit: a new channel lands in an
        // existing group via SSE and must be reflected locally.
        val payload = Fixtures.load("groups/channel-add.json")
        val intent = classify(payload) as GroupEventIntent.AddChannel
        assertEquals("~ricsul-bilwyt-dozzod-nisfeb/v1h84eoe", intent.flag)
        assertEquals("chat/~ricsul-bilwyt-dozzod-nisfeb/random", intent.nest)
        assertEquals("Random", intent.title)
    }

    @Test
    fun `channel add without meta still emits AddChannel with null title`() {
        val raw = """
            {"flag":"~h/f","r-group":{"channel":{
              "nest":"chat/~h/c","r-channel":{"add":{}}
            }}}
        """.trimIndent()
        val intent = classify(raw) as GroupEventIntent.AddChannel
        assertEquals("chat/~h/c", intent.nest)
        assertNull(intent.title)
    }

    // ─── channel.edit / channel.del ─────────────────────────────

    @Test
    fun `r-channel edit emits EditChannel with the new title`() {
        val raw = """
            {"flag":"~h/f","r-group":{"channel":{
              "nest":"chat/~h/c","r-channel":{"edit":{"meta":{"title":"Renamed"}}}
            }}}
        """.trimIndent()
        val intent = classify(raw) as GroupEventIntent.EditChannel
        assertEquals("Renamed", intent.title)
        assertEquals("chat/~h/c", intent.nest)
    }

    @Test
    fun `r-channel del emits DeleteChannel`() {
        val raw = """
            {"flag":"~h/f","r-group":{"channel":{
              "nest":"heap/~h/pics","r-channel":{"del":null}
            }}}
        """.trimIndent()
        val intent = classify(raw) as GroupEventIntent.DeleteChannel
        assertEquals("~h/f", intent.flag)
        assertEquals("heap/~h/pics", intent.nest)
    }

    @Test
    fun `r-channel join is unknown to us and routes to Unknown`() {
        // We don't surface join/leave — activity counts cover presence.
        val raw = """
            {"flag":"~h/f","r-group":{"channel":{
              "nest":"chat/~h/c","r-channel":{"join":true}
            }}}
        """.trimIndent()
        val intent = classify(raw)
        assertTrue(intent is GroupEventIntent.Unknown)
    }

    // ─── group-level variants ──────────────────────────────────

    @Test
    fun `r-group create produces CreateGroup with meta and channel map`() {
        val raw = """
            {"flag":"~h/new","r-group":{"create":{
              "meta":{"title":"New","description":"","image":"","cover":""},
              "channels":{
                "chat/~h/general":{"meta":{"title":"General"}},
                "diary/~h/journal":{"meta":{"title":"Journal"}}
              }
            }}}
        """.trimIndent()
        val intent = classify(raw) as GroupEventIntent.CreateGroup
        assertEquals("New", intent.title)
        assertEquals(2, intent.channels.size)
        assertEquals("General", intent.channels["chat/~h/general"])
        assertEquals("Journal", intent.channels["diary/~h/journal"])
    }

    @Test
    fun `r-group create with no channels map yields empty map not null`() {
        val raw = """
            {"flag":"~h/new","r-group":{"create":{
              "meta":{"title":"Empty","description":"","image":"","cover":""}
            }}}
        """.trimIndent()
        val intent = classify(raw) as GroupEventIntent.CreateGroup
        assertTrue(intent.channels.isEmpty())
    }

    @Test
    fun `r-group delete yields DeleteGroup regardless of delete value`() {
        // Tlon sends `delete: null` — we only care that the key exists.
        val raw = """{"flag":"~h/gone","r-group":{"delete":null}}"""
        val intent = classify(raw) as GroupEventIntent.DeleteGroup
        assertEquals("~h/gone", intent.flag)
    }

    @Test
    fun `r-group meta produces EditGroupMeta with normalized fields`() {
        val raw = """
            {"flag":"~h/f","r-group":{"meta":{
              "title":"Updated","description":"","image":"https://x/y.png","cover":""
            }}}
        """.trimIndent()
        val intent = classify(raw) as GroupEventIntent.EditGroupMeta
        assertEquals("Updated", intent.title)
        assertEquals("https://x/y.png", intent.image)
    }

    @Test
    fun `meta with blank title collapses to null`() {
        val raw = """
            {"flag":"~h/f","r-group":{"meta":{
              "title":"","description":"","image":"","cover":""
            }}}
        """.trimIndent()
        val intent = classify(raw) as GroupEventIntent.EditGroupMeta
        assertNull(intent.title)
        assertNull(intent.image)
    }

    // ─── unknown / malformed ──────────────────────────────────

    @Test
    fun `seat role entry variants are routed as Unknown for now`() {
        // We don't plumb these into the home list; admin screens
        // re-scry when opened, so Unknown is the right placeholder.
        val variants = listOf("seat", "role", "entry")
        for (v in variants) {
            val raw = """{"flag":"~h/f","r-group":{"$v":{}}}"""
            val intent = classify(raw)
            assertTrue("$v should be Unknown", intent is GroupEventIntent.Unknown)
        }
    }

    @Test
    fun `missing flag returns null`() {
        val raw = """{"r-group":{"meta":{"title":"x"}}}"""
        assertNull(classify(raw))
    }

    @Test
    fun `missing r-group returns null`() {
        val raw = """{"flag":"~h/f"}"""
        assertNull(classify(raw))
    }
}
