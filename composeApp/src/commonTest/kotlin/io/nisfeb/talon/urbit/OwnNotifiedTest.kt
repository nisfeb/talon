package io.nisfeb.talon.urbit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A user's report, 2026-10-19: the iPhone's icon kept 4 with every chat
 * read here. %activity's own numbers (his /v4/activity scry): two
 * reactions in a channel and one in each of two DMs, all notifying and
 * none unread. What a chat's own stream notified is its notify-count
 * less its threads', which roll up into it (desk/lib/activity.hoon,
 * +stream-to-unreads).
 */
class OwnNotifiedTest {
    private val summaries = Json.parseToJsonElement(
        """{
          "base":{"notify-count":4,"count":0,"unread":null},
          "group/~rus/federation":{"notify-count":2,"count":0,"unread":null},
          "channel/chat/~rus/member-salon":{"notify-count":2,"count":0,"unread":null},
          "ship/~fidzod-fidfes":{"notify-count":1,"count":0,"unread":null},
          "channel/chat/~bus/general":{"notify-count":3,"count":1,"unread":null},
          "thread/chat/~bus/general/170.141.184.506":{"notify-count":1,"count":1,"unread":null},
          "thread/chat/~bus/general/170.141.184.507":{"notify-count":1,"count":1,"unread":null},
          "dm-thread/~nec/~nec/170.141.184.508":{"notify-count":2,"count":2,"unread":null},
          "ship/~nec":{"notify-count":2,"count":2,"unread":null},
          "channel/chat/~bus/quiet":{"count":0}
        }""",
    ).jsonObject

    private val counts = notifyCounts(summaries)

    @Test
    fun `the ship's counts are kept by chat and by thread, and nothing else`() {
        assertEquals(
            mapOf(
                "chat/~rus/member-salon" to 2, "~fidzod-fidfes" to 1, "chat/~bus/general" to 3,
                "chat/~bus/general#170141184506" to 1, "chat/~bus/general#170141184507" to 1,
                "~nec#~nec/170141184508" to 2, "~nec" to 2,
            ),
            counts,
            "base and groups are not chats; a summary with no notify-count says nothing",
        )
    }

    @Test
    fun `a reaction in a chat with nothing unread is the chat's own`() {
        assertEquals(2, ownNotified(counts, "chat/~rus/member-salon"))
        assertEquals(1, ownNotified(counts, "~fidzod-fidfes"))
    }

    @Test
    fun `what the threads notified is theirs, not the chat's`() {
        assertEquals(1, ownNotified(counts, "chat/~bus/general"), "3 less the threads' 2")
        assertEquals(0, ownNotified(counts, "~nec"), "all of it the thread's")
    }

    @Test
    fun `a chat the ship has not counted, or counted less than its threads, owes nothing`() {
        assertEquals(0, ownNotified(counts, "chat/~bus/quiet"))
        assertEquals(0, ownNotified(counts, "~zod"))
        assertEquals(0, ownNotified(mapOf("~a#~a/1" to 2, "~a" to 1), "~a"), "a thread newer than its chat's count")
        assertEquals(1, ownNotified(mapOf("~a" to 1, "~ab#~ab/1" to 5), "~a"), "another chat's threads are not this one's")
    }
}
