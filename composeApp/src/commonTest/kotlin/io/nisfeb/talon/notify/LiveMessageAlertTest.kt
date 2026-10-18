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
 * it alerted for messages eyre replayed however old they were.
 */
class LiveMessageAlertTest {
    private val now = 10_000_000L

    private fun alerts(
        whom: String = "~bus",
        level: String? = NotifyLevel.DEFAULT,
        mention: Boolean = false,
        replyToUs: Boolean = false,
        ageMs: Long = 1_000L,
        onScreen: Boolean = false,
    ) = liveMessageAlerts(whom, level, mention, replyToUs, now - ageMs, now, onScreen)

    @Test
    fun `a dm or a club at the default level alerts and a channel needs a mention`() {
        assertTrue(alerts(whom = "~bus"), "a DM is addressed to us")
        assertTrue(alerts(whom = "0v4.club"), "so is a club")
        assertFalse(alerts(whom = "chat/~bus/general"))
        assertTrue(alerts(whom = "chat/~bus/general", mention = true))
        assertTrue(alerts(whom = "chat/~bus/general", level = NotifyLevel.ALL))
        assertTrue(alerts(whom = "chat/~bus/general", level = null), "no level is all, as notifyAllowed reads it")
    }

    @Test
    fun `muted is silent and a reply that counts passes mentions only`() {
        assertFalse(alerts(level = NotifyLevel.NONE))
        assertFalse(alerts(whom = "chat/~bus/general", level = NotifyLevel.NONE, mention = true))
        assertTrue(alerts(whom = "chat/~bus/general", replyToUs = true), "following is asking")
        assertFalse(alerts(whom = "chat/~bus/general", level = NotifyLevel.NONE, replyToUs = true))
    }

    @Test
    fun `a replay older than five minutes and a chat on screen do not alert`() {
        assertTrue(alerts(ageMs = ALERT_FRESH_MS))
        assertFalse(alerts(ageMs = ALERT_FRESH_MS + 1))
        assertFalse(alerts(ageMs = 60L * 60_000L, mention = true, replyToUs = true))
        assertTrue(alerts(ageMs = -60_000L), "a sender's clock ahead of ours is still news")
        assertFalse(alerts(onScreen = true))
        assertFalse(alerts(onScreen = true, replyToUs = true, level = NotifyLevel.ALL))
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
}
