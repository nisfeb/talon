package io.nisfeb.talon.urbit

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ThreadUnreadParserTest {

    @Test
    fun `channel thread key splits into nest and bare-da post id`() {
        // `thread/<kind>/~host/<slug>/<author>/<da>` — the trailing
        // <author>/<da> is the wire form; MessageEntity.parentId for
        // channel posts is the bare <da>, so we strip the author here.
        val src = sourceKeyToThreadSource(
            "thread/chat/~host/general/~zod/170.141.184.505.123.456.789",
        )
        assertNotNull(src)
        assertEquals("chat/~host/general", src.whom)
        assertEquals("170.141.184.505.123.456.789", src.parentPostId)
    }

    @Test
    fun `dm-thread key preserves the full author-prefixed writ id`() {
        // DM / club tables key on the full `~author/<da>` form — don't
        // strip the author segment like we do for channels.
        val src = sourceKeyToThreadSource(
            "dm-thread/~peer/~peer/170.141.184.505.111.222.333",
        )
        assertNotNull(src)
        assertEquals("~peer", src.whom)
        assertEquals("~peer/170.141.184.505.111.222.333", src.parentPostId)
    }

    @Test
    fun `dm-thread key with club whom preserves full writ id`() {
        val src = sourceKeyToThreadSource(
            "dm-thread/0v4.abcde/~bus/170.141.184.505.987.654.321",
        )
        assertNotNull(src)
        assertEquals("0v4.abcde", src.whom)
        assertEquals("~bus/170.141.184.505.987.654.321", src.parentPostId)
    }

    @Test
    fun `non-thread source keys return null`() {
        assertNull(sourceKeyToThreadSource("ship/~zod"))
        assertNull(sourceKeyToThreadSource("club/0vfoo"))
        assertNull(sourceKeyToThreadSource("channel/chat/~host/x"))
        assertNull(sourceKeyToThreadSource("group/~host/g"))
        assertNull(sourceKeyToThreadSource("base"))
        assertNull(sourceKeyToThreadSource(""))
    }

    @Test
    fun `malformed thread key returns null`() {
        // Missing the post-id tail past the 3-segment nest.
        assertNull(sourceKeyToThreadSource("thread/chat/~host/general"))
        assertNull(sourceKeyToThreadSource("thread/chat/~host"))
    }

    @Test
    fun `malformed dm-thread key returns null`() {
        assertNull(sourceKeyToThreadSource("dm-thread/~peer"))
        assertNull(sourceKeyToThreadSource("dm-thread/"))
    }

    @Test
    fun `toThreadUnread populates count notifyCount and recency`() {
        val summary = buildJsonObject {
            put("count", 5)
            put("notify-count", 2)
            put("recency", 1_700_000_111_222L)
        }
        val entity = assertNotNull(
            toThreadUnread(
                "thread/chat/~host/g/~zod/170.141.184.505.123.456.789",
                summary,
            ),
        )
        assertEquals("chat/~host/g", entity.whom)
        assertEquals("170.141.184.505.123.456.789", entity.parentPostId)
        assertEquals(5, entity.count)
        assertEquals(2, entity.notifyCount)
        assertEquals(1_700_000_111_222L, entity.recencyMs)
    }

    @Test
    fun `toThreadUnread returns null for non-thread source`() {
        val summary = buildJsonObject { put("count", 1) }
        assertNull(toThreadUnread("ship/~zod", summary))
        assertNull(toThreadUnread("channel/chat/~host/g", summary))
    }
}
