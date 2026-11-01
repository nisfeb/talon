package io.nisfeb.talon.urbit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * A reference to a post in a group this ship is not in, asked of its
 * host (a user's report, 2026-11-01: such a reference showed only the
 * channel's id). The bodies are what two ships on %groups 12.2.0 said,
 * verbatim (~lyx, not a member, asking ~rud, 2026-11-01).
 */
class OutsideReferenceTest {
    private val post = "170.141.184.508.237.593.667.035.039.476.012.285.952"
    private val reply = "170.141.184.508.237.594.114.540.282.999.262.412.800"

    @Test
    fun `the said watch is v2 on the ship's own channels, the post dotted, a reply after it`() {
        assertEquals("/v2/said/chat/~rud/pubchat/post/$post", saidPath(NEST, post, null))
        assertEquals("/v2/said/chat/~rud/pubchat/post/$post/$reply", saidPath(NEST, post, reply))
        assertEquals("/v1/channels/chat/~rud/pubchat/preview", channelPreviewPath(NEST))
    }

    @Test
    fun `a post the host shows is that post`() {
        val got = assertIs<Said.Post>(parseSaid(NEST, Json.parseToJsonElement(SAID_POST))).message
        assertEquals(listOf(NEST, "170141184508237593667035039476012285952", "~rud", 1_793_564_521_584L), listOf(got.whom, got.id, got.author, got.sentMs))
        assertEquals("""[{"inline":["hello from the public channel"]}]""", got.contentJson)
    }

    @Test
    fun `a reply the host shows is that reply, under its post`() {
        val got = assertIs<Said.Post>(parseSaid(NEST, Json.parseToJsonElement(SAID_REPLY))).message
        assertEquals(
            listOf("170141184508237594114540282999262412800", "170141184508237593667035039476012285952", "~rud"),
            listOf(got.id, got.parentId, got.author),
        )
    }

    // The host answers for a post it lacks with a stand-in: ~zod, 2000,
    // nothing said. channel-utils' said-2 makes a deleted one the same way.
    @Test
    fun `a post or reply the host lacks is gone, not a post by zod in 2000`() {
        assertEquals(Said.Gone, parseSaid(NEST, Json.parseToJsonElement(SAID_MISSING)))
        assertEquals(Said.Gone, parseSaid(NEST, Json.parseToJsonElement(SAID_MISSING.replace("/chat/unknown", "/chat/deleted"))))
        assertEquals(
            Said.Gone,
            parseSaid(NEST, Json.parseToJsonElement("""{"nest":"$NEST","reference":{"reply":{"id-post":"0","reply":{"memo":{"author":"~zod","sent":0,"content":[{"inline":["Comment on unknown post"]}]},"seal":{"id":"0","parent-id":"0","reacts":{}}}}}}""")),
        )
    }

    @Test
    fun `channel-denied, whose JSON is null, is denied, and no fact or a strange one is no answer`() {
        assertEquals(Said.Denied, parseSaid(NEST, JsonNull))
        assertEquals(Said.NoAnswer, parseSaid(NEST, null))
        assertEquals(Said.NoAnswer, parseSaid(NEST, Json.parseToJsonElement("""{"nest":"$NEST","reference":{"other":{}}}""")))
        assertEquals(Said.NoAnswer, parseSaid(NEST, Json.parseToJsonElement("""{"nest":"$NEST","reference":{"post":{"essay":{}}}}""")))
    }

    @Test
    fun `a channel's preview gives its title and its group's flag and title`() {
        assertEquals(ChannelPreview("Public chat", "~rud/pubref", "Public Ref"), parseChannelPreview(Json.parseToJsonElement(PREVIEW)))
        // An older host's preview (cordon and secret, no privacy) reads the same.
        assertEquals(
            ChannelPreview(null, "~rud/pubref", null),
            parseChannelPreview(Json.parseToJsonElement("""{"nest":"$NEST","meta":{"title":""},"group":{"flag":"~rud/pubref","meta":{"title":""},"cordon":{},"secret":false}}""")),
        )
        assertNull(parseChannelPreview(Json.parseToJsonElement("""{"nest":"$NEST","meta":{"title":"Public chat"}}""")))
        assertNull(parseChannelPreview(JsonNull))
    }

    @Test
    fun `only channels are asked of a host`() {
        assertEquals(listOf(true, true, true, false, false), listOf("chat/~bus/a", "heap/~bus/b", "diary/~bus/c", "~bus", "group:~bus/crew").map(::isChannelNest))
    }

    companion object {
        const val NEST = "chat/~rud/pubchat"
        /** The said watch's path as the repo asks it, app first, as FakeShip names a watch. */
        const val SAID_WATCH = "channels/v2/said/chat/~rud/pubchat/post/170.141.184.508.237.593.667.035.039.476.012.285.952"
        const val PREVIEW_WATCH = "groups/v1/channels/chat/~rud/pubchat/preview"
        const val SAID_POST = """{"nest":"chat/~rud/pubchat","reference":{"post":{"seal":{"id":"170141184508237593667035039476012285952","replies":{},"reacts":{},"meta":{"replyCount":1,"lastReply":1793564545853,"lastRepliers":["~rud"]}},"essay":{"author":"~rud","sent":1793564521584,"kind":"/chat","blob":null,"content":[{"inline":["hello from the public channel"]}],"meta":null},"type":"post"}}}"""
        const val SAID_REPLY = """{"nest":"chat/~rud/pubchat","reference":{"reply":{"id-post":"170141184508237593667035039476012285952","reply":{"memo":{"author":"~rud","sent":1793564545842,"content":[{"inline":["a reply in the public channel"]}]},"seal":{"id":"170141184508237594114540282999262412800","parent-id":"170141184508237593667035039476012285952","reacts":{}}}}}}"""
        const val SAID_MISSING = """{"nest":"chat/~rud/pubchat","reference":{"post":{"seal":{"id":"170141184492615420181573981275213004800","replies":{},"reacts":{},"meta":{"replyCount":0,"lastReply":null,"lastRepliers":[]}},"essay":{"author":"~zod","sent":946684800000,"kind":"/chat/unknown","blob":null,"content":[],"meta":null},"type":"post"}}}"""
        const val PREVIEW = """{"nest":"chat/~rud/pubchat","group":{"member-count":1,"flag":"~rud/pubref","privacy":"public","time":1793564842897,"meta":{"image":"","title":"Public Ref","cover":"","description":"public test group"}},"meta":{"image":"","title":"Public chat","cover":"","description":""}}"""
    }
}
