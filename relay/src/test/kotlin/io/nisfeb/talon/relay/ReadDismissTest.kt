package io.nisfeb.talon.relay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A chat read to the end on any client takes its notifications off the
 * phone, as Tlon's %notify dismisses them: the %activity /v4 read update
 * the relay already hears, in the encoder's own shape (activity-json v8),
 * and the push it becomes.
 */
class ReadDismissTest {

    private fun update(raw: String) = Json.parseToJsonElement(raw).jsonObject

    private fun read(source: String, count: Int = 0, notifyCount: Int = 0) = update(
        """{"read":{"source":$source,"activity":{"recency":1791119109000,"recency-uv":"0v1","count":$count,
            "notify-count":$notifyCount,"notify":false,"unread":null}}}""",
    )

    @Test
    fun `a dm, a club and a channel read to the end name their chat`() {
        assertEquals("~bus", readWhom(read("""{"dm":{"ship":"~bus"}}""")))
        assertEquals("0v4.abcde", readWhom(read("""{"club":{"id":"0v4.abcde"}}""")))
        assertEquals("chat/~bus/general", readWhom(read("""{"channel":{"nest":"chat/~bus/general","group":"~bus/garden"}}""")))
    }

    @Test
    fun `a partial read, a thread's read and other updates dismiss nothing`() {
        assertNull(readWhom(read("""{"dm":{"ship":"~bus"}}""", count = 2)))
        assertNull(readWhom(read("""{"dm":{"ship":"~bus"}}""", notifyCount = 1)))
        assertNull(readWhom(read("""{"thread":{"channel":"chat/~bus/general","group":"~bus/garden","key":{"id":"~bus/1","time":"1"}}}""")))
        assertNull(readWhom(update("""{"add":{"source":{"dm":{"ship":"~bus"}},"event":{"notified":true}}}""")))
        assertNull(readWhom(update("""{"read":{"source":{"dm":{"ship":"~bus"}},"activity":{}}}""")))
    }

    @Test
    fun `the push is hint-only, like a message's`() {
        assertEquals(
            """{"event":"read","patp":"~zod","whom":"chat/~bus/general"}""",
            Push().readBody("~zod", "chat/~bus/general"),
        )
    }
}
