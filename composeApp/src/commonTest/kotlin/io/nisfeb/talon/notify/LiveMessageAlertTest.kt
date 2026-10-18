package io.nisfeb.talon.notify

import io.nisfeb.talon.data.NotifyLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Android's in-process alert, decided here so both its sides are tested
 * (TalonApplication calls it; there is no Android harness). It demanded a
 * mention of every chat at the default level, so a DM never alerted, and
 * it alerted again for a message it had already alerted for (A5-11). Then
 * a five-minute age rule silenced what eyre hands back after an outage,
 * on a phone with no push (B6-3): heard before or read is the test now.
 */
class LiveMessageAlertTest {
    private fun alerts(
        whom: String = "~bus",
        level: String? = NotifyLevel.DEFAULT,
        mention: Boolean = false,
        replyToUs: Boolean = false,
        heard: Boolean = false,
        read: Boolean = false,
        onScreen: Boolean = false,
    ) = liveMessageAlerts(whom, level, mention, replyToUs, heard, read, onScreen)

    @Test
    fun `a dm or a club at the default level alerts and a channel needs a mention`() {
        assertTrue(alerts(whom = "~bus"), "a DM is addressed to us")
        assertTrue(alerts(whom = "0v4.club"), "so is a club")
        assertFalse(alerts(whom = "chat/~bus/general"))
        assertTrue(alerts(whom = "chat/~bus/general", mention = true))
        assertTrue(alerts(whom = "chat/~bus/general", level = NotifyLevel.ALL))
        assertTrue(alerts(whom = "chat/~bus/general", level = null), "no level is all, as notifyAllowed reads it")
    }

    // TalonApplication reads a mention off the story for the ship it listens for.
    @Test
    fun `a mention is of our ship only and no ship is mentioned by nothing`() {
        val story = """[{"inline":[{"ship":"~zod"}," hi"]}]"""
        assertTrue(isMentioned(story, "~zod"))
        assertFalse(isMentioned(story, "~bus"))
        assertFalse(isMentioned(story, null))
        assertFalse(isMentioned(story, ""))
    }

    @Test
    fun `muted is silent and a reply that counts passes mentions only`() {
        assertFalse(alerts(level = NotifyLevel.NONE))
        assertFalse(alerts(whom = "chat/~bus/general", level = NotifyLevel.NONE, mention = true))
        assertTrue(alerts(whom = "chat/~bus/general", replyToUs = true), "following is asking")
        assertFalse(alerts(whom = "chat/~bus/general", level = NotifyLevel.NONE, replyToUs = true))
    }

    // A5-11: a message this device had already, alerted for then or held
    // back, does not alert when it comes again, however it is flagged.
    @Test
    fun `a message heard before, one read or a chat on screen does not alert`() {
        assertFalse(alerts(heard = true))
        assertFalse(alerts(heard = true, mention = true, replyToUs = true, level = NotifyLevel.ALL))
        assertFalse(alerts(read = true))
        assertFalse(alerts(whom = "chat/~bus/general", read = true, replyToUs = true, level = NotifyLevel.ALL))
        assertFalse(alerts(onScreen = true))
        assertFalse(alerts(onScreen = true, replyToUs = true, level = NotifyLevel.ALL))
    }

    // B6-3: no push, and what came during an outage arrives only as eyre's
    // replay, with its old time. Unread and new here, it is news.
    @Test
    fun `a chat read only up to an old message still alerts for a newer one`() {
        assertTrue(readPast(count = 0, recencyMs = 5_000, sentMs = 5_000), "read at the message")
        assertTrue(readPast(count = 0, recencyMs = 5_001, sentMs = 5_000), "read past it")
        assertFalse(readPast(count = 0, recencyMs = 4_999, sentMs = 5_000), "read only before it")
        assertFalse(readPast(count = 1, recencyMs = 9_000, sentMs = 5_000), "something unread")
        assertFalse(readPast(count = null, recencyMs = null, sentMs = 5_000), "no row is unread")
        assertFalse(readPast(count = 0, recencyMs = null, sentMs = Long.MIN_VALUE))
        assertTrue(alerts(read = readPast(count = 0, recencyMs = 1_000, sentMs = 5_000)), "an hour old or a second")
    }

    // What a catch-up brought after an outage: one alert a chat, not one
    // a message, and a mention is not hidden behind the chat after it.
    @Test
    fun `a catch-up alerts once a chat and a mention or a reply to us wins`() {
        fun msg(whom: String, id: String, sent: Long, text: String = "hi") =
            io.nisfeb.talon.data.MessageEntity(whom, id, "~bus", sent, text, "/chat")
        val nest = "chat/~bus/general"
        val mention = """[{"inline":[{"ship":"~zod"}]}]"""
        val picked = catchUpAlerts(
            listOf(
                msg("~bus", "1", 1_000) to false, msg("~bus", "3", 3_000) to false, msg("~bus", "2", 2_000) to false,
                msg(nest, "4", 4_000, mention) to false, msg(nest, "5", 5_000) to false,
                msg("~nec", "6", 6_000) to false, msg("~nec", "7", 7_000) to true, msg("~nec", "8", 8_000) to false,
            ),
            ourPatp = "~zod",
        ).map { it.first.id to it.second }.toSet()
        assertEquals(setOf("3" to false, "4" to false, "7" to true), picked)
        val two = catchUpAlerts(listOf(msg(nest, "4", 4_000, mention) to false, msg(nest, "9", 9_000, mention) to false), "~zod")
        assertEquals(listOf("9"), two.map { it.first.id }, "the newest of the mentions")
        assertEquals(listOf("5"), catchUpAlerts(listOf(msg(nest, "4", 4_000, mention) to false, msg(nest, "5", 5_000) to false), "~nec").map { it.first.id })
        assertTrue(catchUpAlerts(emptyList(), "~zod").isEmpty())
    }

    @Test
    fun `a hint over a fresh notification is dropped and the message over a fresh hint is quiet`() {
        assertEquals(ChatAlert.Post, chatAlert(hint = true, shownAgoMs = null, shownIsHint = false))
        assertEquals(ChatAlert.Post, chatAlert(hint = false, shownAgoMs = null, shownIsHint = false))
        assertEquals(ChatAlert.Skip, chatAlert(hint = true, shownAgoMs = 5_000, shownIsHint = false))
        assertEquals(ChatAlert.Skip, chatAlert(hint = true, shownAgoMs = 5_000, shownIsHint = true))
        assertEquals(ChatAlert.Quiet, chatAlert(hint = false, shownAgoMs = 5_000, shownIsHint = true))
        assertEquals(ChatAlert.Post, chatAlert(hint = false, shownAgoMs = 5_000, shownIsHint = false), "a second message alerts")
    }

    @Test
    fun `a notification shown longer ago than the window is old and the next one alerts`() {
        assertEquals(ChatAlert.Skip, chatAlert(hint = true, shownAgoMs = 60_000, shownIsHint = false))
        assertEquals(ChatAlert.Post, chatAlert(hint = true, shownAgoMs = 60_001, shownIsHint = false))
        assertEquals(ChatAlert.Quiet, chatAlert(hint = false, shownAgoMs = 60_000, shownIsHint = true))
        assertEquals(ChatAlert.Post, chatAlert(hint = false, shownAgoMs = 60_001, shownIsHint = true))
    }

    // A message eyre replays after a resume is the one already showing:
    // it is left alone, an hour on or a second.
    @Test
    fun `the very message showing is not posted again`() {
        assertEquals(ChatAlert.Skip, chatAlert(hint = false, shownAgoMs = 3_600_000, shownIsHint = false, samePost = true))
        assertEquals(ChatAlert.Skip, chatAlert(hint = false, shownAgoMs = 1_000, shownIsHint = true, samePost = true))
        assertEquals(ChatAlert.Post, chatAlert(hint = false, shownAgoMs = null, shownIsHint = false, samePost = true), "dismissed, it is news again")
    }
}
