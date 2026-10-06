package io.nisfeb.talon.relay

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * %activity /v4's message events and sources, as landscape's
 * sur/activity.hoon has them. The relay read only dm-post, chan-post and
 * club-post, and wanted club.id: of v4's events it pushed only a 1:1
 * DM's top-level post, never a channel post, a reply or a club DM
 * (found 2026-10-06 by the trunk session).
 */
class ActivityEventsTest {
    private fun obj(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    private val key = """{"id":"~nec/170.141.184.507","time":"170.141.184.507"}"""
    private val parent = """{"id":"~bus/170.141.184.506","time":"170.141.184.506"}"""

    @Test
    fun `every message event names its post`() {
        for (tag in listOf("post", "reply", "dm-post", "dm-reply", "chan-post", "club-post")) {
            assertEquals("~nec/170.141.184.507", activityPostId(obj("""{"notified":true,"$tag":{"key":$key,"content":[]}}""")), tag)
        }
        assertNull(activityPostId(obj("""{"notified":true,"flag-post":{"key":$key}}""")), "not a message")
    }

    @Test
    fun `a reply names its parent, a post has none`() {
        assertEquals("~bus/170.141.184.506", activityParentId(obj("""{"reply":{"key":$key,"parent":$parent}}""")))
        assertEquals("~bus/170.141.184.506", activityParentId(obj("""{"dm-reply":{"key":$key,"parent":$parent}}""")))
        assertNull(activityParentId(obj("""{"post":{"key":$key}}""")))
    }

    @Test
    fun `every source names its chat`() {
        assertEquals("~bus", activityWhom(obj("""{"dm":{"ship":"~bus"}}""")))
        assertEquals("0v4.club", activityWhom(obj("""{"dm":{"club":"0v4.club"}}""")), "a club DM, v4's form")
        assertEquals("chat/~zod/general", activityWhom(obj("""{"channel":{"nest":"chat/~zod/general","group":"~zod/crew"}}""")))
        assertEquals("chat/~zod/general", activityWhom(obj("""{"thread":{"key":$parent,"channel":"chat/~zod/general","group":"~zod/crew"}}""")), "a reply's thread is its channel's")
        assertEquals("~bus", activityWhom(obj("""{"dm-thread":{"key":$parent,"whom":{"ship":"~bus"}}}""")))
        assertEquals("0v4.club", activityWhom(obj("""{"dm-thread":{"key":$parent,"whom":{"club":"0v4.club"}}}""")))
        assertEquals("0v4.club", activityWhom(obj("""{"club":{"id":"0v4.club"}}""")), "before v4")
        assertNull(activityWhom(obj("""{"group":"~zod/crew"}""")))
    }

    @Test
    fun `a channel post and a reply have previews`() {
        val content = """[{"inline":["hello ",{"bold":["there"]}]}]"""
        assertEquals("hello there", ActivityPreview.of(obj("""{"post":{"key":$key,"content":$content}}""")))
        assertEquals("hello there", ActivityPreview.of(obj("""{"reply":{"key":$key,"parent":$parent,"content":$content}}""")))
    }

    @Test
    fun `a club DM read to the end is a read push`() {
        assertEquals("0v4.club", readWhom(obj("""{"read":{"source":{"dm":{"club":"0v4.club"}},"activity":{"count":0,"notify-count":0}}}""")))
    }

    // The Android receiver opens the thread with "parent"; a top-level
    // post's body is byte for byte what it was.
    @Test
    fun `a reply's push carries its parent, a post's is unchanged`() {
        val bodies = LinkedBlockingQueue<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/push") { ex ->
            bodies.put(ex.requestBody.readBytes().decodeToString())
            ex.sendResponseHeaders(200, -1)
            ex.close()
        }
        server.start()
        try {
            val endpoint = "http://127.0.0.1:${server.address.port}/push"
            Push().send(endpoint, "~zod", "chat/~zod/general", "~nec/170.141.184.507", "unifiedpush", parent = "~bus/170.141.184.506")
            assertEquals(
                """{"event":"new-message","patp":"~zod","whom":"chat/~zod/general","id":"~nec/170.141.184.507","parent":"~bus/170.141.184.506"}""",
                bodies.poll(10, TimeUnit.SECONDS),
            )
            Push().send(endpoint, "~zod", "~bus", "~bus/170.141.184.508", "unifiedpush")
            assertEquals(
                """{"event":"new-message","patp":"~zod","whom":"~bus","id":"~bus/170.141.184.508"}""",
                bodies.poll(10, TimeUnit.SECONDS),
            )
        } finally {
            server.stop(0)
        }
    }
}
