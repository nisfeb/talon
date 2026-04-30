package io.nisfeb.talon.urbit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Payloads here are abbreviated but structurally identical to what
 * real %channels SSE deltas look like (captured from live adb logcat).
 */
class ChannelEventRouterTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun classify(raw: String): ChannelDeltaIntent =
        classifyChannelDelta(json.parseToJsonElement(raw).jsonObject)

    // ─── top-level post events ──────────────────────────────────

    @Test
    fun `r-post set with tombstone routes to PostTombstone`() {
        // Regression: before we added tombstone detection, this fell
        // into the "ingest as post" branch and silently did nothing.
        val raw = """
            {"post":{"id":"170141184507933044937549665940933705728",
             "r-post":{"set":{"author":"~ricsul","id":"170.141.184.507.933.044.937.549.665.940.933.705.728",
                              "deleted-at":1777055041699,"type":"tombstone","seq":961}}}}
        """.trimIndent()
        val intent = classify(raw)
        assertTrue(intent is ChannelDeltaIntent.PostTombstone)
        assertEquals(
            "170141184507933044937549665940933705728",
            (intent as ChannelDeltaIntent.PostTombstone).id,
        )
    }

    @Test
    fun `r-post set with dotted id is normalized`() {
        // The SSE echo sometimes comes with a dot-grouped id; our DB
        // stores raw digits. Router must normalize.
        val raw = """
            {"post":{"id":"170.141.184.507.933.044.937.549.665.940.933.705.728",
             "r-post":{"set":{"type":"tombstone","author":"~ricsul",
                              "id":"...","deleted-at":0,"seq":0}}}}
        """.trimIndent()
        val intent = classify(raw) as ChannelDeltaIntent.PostTombstone
        assertEquals(
            "170141184507933044937549665940933705728",
            intent.id,
        )
    }

    @Test
    fun `r-post set with full payload routes to PostSet`() {
        val raw = """
            {"post":{"id":"1234567890","r-post":{"set":{
              "revision":"0",
              "seal":{"id":"1234567890","replies":{},"mod-at":"0","reacts":{},"seq":1,
                      "meta":{"replyCount":0,"lastReply":null,"lastRepliers":[]}},
              "essay":{"content":[],"author":"~ricsul","sent":0,"kind":"/chat","blob":null,"meta":null}
            }}}}
        """.trimIndent()
        val intent = classify(raw)
        assertTrue(intent is ChannelDeltaIntent.PostSet)
        intent as ChannelDeltaIntent.PostSet
        assertEquals("1234567890", intent.id)
        assertTrue(intent.post.containsKey("essay"))
    }

    @Test
    fun `r-post set null routes to legacy PostDeleted`() {
        // Older agent versions emit `set: null` for deletes. Router keeps
        // the path alive as PostDeleted.
        val raw = """{"post":{"id":"12345","r-post":{"set":null}}}"""
        val intent = classify(raw)
        assertTrue(intent is ChannelDeltaIntent.PostDeleted)
        assertEquals("12345", (intent as ChannelDeltaIntent.PostDeleted).id)
    }

    @Test
    fun `r-post reacts routes to PostReactions`() {
        val raw = """
            {"post":{"id":"12345","r-post":{"reacts":{"~alice":":fire:","~bob":":eyes:"}}}}
        """.trimIndent()
        val intent = classify(raw) as ChannelDeltaIntent.PostReactions
        assertEquals("12345", intent.id)
        assertEquals(2, intent.reacts.size)
    }

    @Test
    fun `r-post essay edit routes to PostEssay`() {
        val raw = """
            {"post":{"id":"12345","r-post":{"essay":{"content":[],"author":"~x","sent":0,"kind":"/chat","meta":null,"blob":null}}}}
        """.trimIndent()
        val intent = classify(raw) as ChannelDeltaIntent.PostEssay
        assertEquals("12345", intent.id)
    }

    // ─── reply events ────────────────────────────────────────────

    @Test
    fun `reply set with reply-essay routes to Reply Upsert`() {
        val raw = """
            {"post":{"id":"1000","r-post":{"reply":{"id":"2000","r-reply":{
              "set":{"reply-essay":{"content":[],"author":"~x","sent":0,"blob":null}}
            }}}}}
        """.trimIndent()
        val intent = classify(raw) as ChannelDeltaIntent.Reply
        assertEquals("1000", intent.parentId)
        assertEquals("2000", intent.replyId)
        assertTrue(intent.inner is ReplyIntent.Upsert)
    }

    @Test
    fun `reply set with tombstone routes to Reply Tombstone`() {
        val raw = """
            {"post":{"id":"1000","r-post":{"reply":{"id":"2000","r-reply":{
              "set":{"type":"tombstone","id":"2.000","deleted-at":0,"seq":0,"author":"~x"}
            }}}}}
        """.trimIndent()
        val intent = classify(raw) as ChannelDeltaIntent.Reply
        assertEquals(ReplyIntent.Tombstone, intent.inner)
    }

    @Test
    fun `reply set null is Deleted`() {
        val raw = """
            {"post":{"id":"1000","r-post":{"reply":{"id":"2000","r-reply":{"set":null}}}}}
        """.trimIndent()
        val intent = classify(raw) as ChannelDeltaIntent.Reply
        assertEquals(ReplyIntent.Deleted, intent.inner)
    }

    @Test
    fun `reply reacts-only routes to Reply Reactions`() {
        val raw = """
            {"post":{"id":"1000","r-post":{"reply":{"id":"2000","r-reply":{
              "reacts":{"~x":":up:"}
            }}}}}
        """.trimIndent()
        val intent = classify(raw) as ChannelDeltaIntent.Reply
        assertTrue(intent.inner is ReplyIntent.Reactions)
    }

    @Test
    fun `reply parent id is normalized`() {
        val raw = """
            {"post":{"id":"1.000","r-post":{"reply":{"id":"2.000","r-reply":{"set":null}}}}}
        """.trimIndent()
        val intent = classify(raw) as ChannelDeltaIntent.Reply
        assertEquals("1000", intent.parentId)
        assertEquals("2000", intent.replyId)
    }

    // ─── batch / pending / unknown ──────────────────────────────

    @Test
    fun `posts batch routes to PostsBatch`() {
        val raw = """{"posts":{"1":{"seal":{"id":"1"},"essay":{}}}}"""
        val intent = classify(raw)
        assertTrue(intent is ChannelDeltaIntent.PostsBatch)
    }

    @Test
    fun `pending post routes to PendingPost`() {
        val raw = """
            {"pending":{"pending":{"post":{"author":"~x","sent":0,"kind":"/chat",
                                           "blob":null,"content":[],"meta":null}},
                        "id":{"author":"~x","sent":0}}}
        """.trimIndent()
        val intent = classify(raw)
        assertTrue(intent is ChannelDeltaIntent.PendingPost)
    }

    @Test
    fun `unknown response keys produce Unknown intent`() {
        val raw = """{"some-new-thing":{"foo":"bar"}}"""
        val intent = classify(raw)
        assertTrue(intent is ChannelDeltaIntent.Unknown)
    }

    // ─── order / pinned posts ────────────────────────────────────

    @Test
    fun `order response routes to OrderUpdate with undotted ids`() {
        // Tlon's pin feature uses channel.order; SSE emits the new list
        // directly. Ids arrive dot-grouped like every other @ud on the
        // wire — we strip dots so DB lookups match.
        val raw = """
            {"order":["170.141.184.507.933.044.937.549.665.940.933.705.728",
                      "170.141.184.507.933.044.937.549.665.940.933.705.729"]}
        """.trimIndent()
        val intent = classify(raw) as ChannelDeltaIntent.OrderUpdate
        assertEquals(
            listOf(
                "170141184507933044937549665940933705728",
                "170141184507933044937549665940933705729",
            ),
            intent.postIds,
        )
    }

    @Test
    fun `empty order list routes to OrderUpdate with empty list`() {
        // Unpin = set order to []. Caller treats firstOrNull() as the
        // pinned post, so an empty list clears the pinned banner.
        val raw = """{"order":[]}"""
        val intent = classify(raw) as ChannelDeltaIntent.OrderUpdate
        assertEquals(emptyList<String>(), intent.postIds)
    }
}
