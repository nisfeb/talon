package io.nisfeb.talon.urbit

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ThreadUnreadParserTest {

    @Test
    fun `channel thread key splits into nest and UNDOTTED da post id`() {
        // Tlon shape: `thread/<kind>/~host/<slug>/<dotted-da>`. The
        // @da is emitted via `scot %ud` (dotted), but MessageEntity
        // stores channel post ids UNDOTTED — so the parser must
        // strip dots, otherwise threadUnreadByPost[m.id] never
        // matches at the call site and the indicator stays untinted.
        val src = sourceKeyToThreadSource(
            "thread/chat/~host/general/170.141.184.505.123.456.789",
        )
        assertNotNull(src)
        assertEquals("chat/~host/general", src.whom)
        assertEquals("170141184505123456789", src.parentPostId)
    }

    @Test
    fun `dm-thread key preserves author prefix and undots only the da`() {
        // Tlon shape: `dm-thread/<whom>/<author>/<dotted-da>`. DM /
        // club MessageEntity rows key on `~author/<undotted-da>`, so
        // we undot just the @da half.
        val src = sourceKeyToThreadSource(
            "dm-thread/~peer/~peer/170.141.184.505.111.222.333",
        )
        assertNotNull(src)
        assertEquals("~peer", src.whom)
        assertEquals("~peer/170141184505111222333", src.parentPostId)
    }

    @Test
    fun `dm-thread key with club whom undots only the da`() {
        val src = sourceKeyToThreadSource(
            "dm-thread/0v4.abcde/~bus/170.141.184.505.987.654.321",
        )
        assertNotNull(src)
        assertEquals("0v4.abcde", src.whom)
        assertEquals("~bus/170141184505987654321", src.parentPostId)
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
        // No author/da tail — only the whom segment.
        assertNull(sourceKeyToThreadSource("dm-thread/~peer/onlyonepart"))
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
                "thread/chat/~host/g/170.141.184.505.123.456.789",
                summary,
            ),
        )
        assertEquals("chat/~host/g", entity.whom)
        assertEquals("170141184505123456789", entity.parentPostId)
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
