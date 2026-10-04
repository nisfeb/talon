package io.nisfeb.talon.urbit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which threads count, read the way Tlon keeps it: a thread's reply
 * volume on the ship, unread and notifying when followed, neither when
 * unfollowed (desk/sur/activity.hoon default-volumes: a channel's reply
 * is unread and quiet, a DM's reply both).
 */
class ThreadFollowRulesTest {
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test
    fun `a reply volume reads as followed, unfollowed, or the defaults`() {
        assertEquals(true, followOf(obj("""{"reply":{"unreads":true,"notify":true}}""")))
        assertEquals(true, followOf(obj("""{"dm-reply":{"unreads":false,"notify":true}}""")))
        assertEquals(false, followOf(obj("""{"reply":{"unreads":false,"notify":false}}""")))
        assertNull(followOf(obj("""{"reply":{"unreads":true,"notify":false}}""")), "the channel default: not a choice")
        assertNull(followOf(obj("""{"post":{"unreads":true,"notify":true}}""")))
    }

    @Test
    fun `only threads are taken from the settings, channels and the base are not`() {
        val got = followedThreadsOf(obj("""{
            "base":{"reply":{"unreads":false,"notify":false}},
            "channel/chat/~bus/general":{"reply":{"unreads":true,"notify":true}},
            "thread/chat/~bus/general/170.141.184.506":{"reply":{"unreads":true,"notify":true}},
            "dm-thread/0v4.abcde/~bus/170.141.184.507":{"dm-reply":{"unreads":false,"notify":false}}}"""))
        assertEquals(
            mapOf(
                ThreadSource("chat/~bus/general", "170141184506") to true,
                ThreadSource("0v4.abcde", "~bus/170141184507") to false,
            ),
            got,
        )
    }

    @Test
    fun `a follow names a channel's replies or a DM's`() {
        assertEquals("""{"reply":{"unreads":true,"notify":true}}""", followVolume("chat/~bus/general", true).toString())
        assertEquals("""{"dm-reply":{"unreads":false,"notify":false}}""", followVolume("~bus", false).toString())
        assertEquals("""{"dm-reply":{"unreads":true,"notify":true}}""", followVolume("0v4.abcde", true).toString())
    }

    @Test
    fun `a thread counts when followed, a DM's or the owner's, never once unfollowed`() {
        assertTrue(threadCounts(true, isDm = false, ours = false))
        assertFalse(threadCounts(false, isDm = true, ours = true), "an unfollow stands")
        assertTrue(threadCounts(null, isDm = true, ours = false))
        assertTrue(threadCounts(null, isDm = false, ours = true))
        assertFalse(threadCounts(null, isDm = false, ours = false), "a channel thread the owner is not in")
    }
}
