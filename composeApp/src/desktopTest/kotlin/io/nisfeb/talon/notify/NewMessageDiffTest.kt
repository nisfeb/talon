package io.nisfeb.talon.notify

import io.nisfeb.talon.data.MessageEntity
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure-function tests for the new-message → notification diff. The
 * load-bearing rules:
 *
 *   - Seeding (first emission) populates the baseline silently.
 *     Without this, signing back in would notify for every existing
 *     chat — a UX disaster.
 *   - The lastSeen map is ALWAYS updated, even when a row is
 *     filtered out. Otherwise a previously-muted chat would fire a
 *     stale notification the moment it was unmuted.
 *   - Self-author / open-chat / muted-whom each suppress on their
 *     own. None of them are commutative ordering — the test pins
 *     the suppression individually so a refactor that combines
 *     them can't lose one filter accidentally.
 */
class NewMessageDiffTest {

    private fun msg(
        whom: String,
        id: String,
        author: String = "~zod",
        contentJson: String = """{"inline":[{"text":"hello"}]}""",
    ) = MessageEntity(
        whom = whom,
        id = id,
        author = author,
        sentMs = 0L,
        contentJson = contentJson,
        kind = "note",
    )

    /** Convenience: a storyText fake that returns the contentJson stripped to its inline text. */
    private val storyText: (String, String) -> String = { _, contentJson ->
        // Pull whatever is between the first `"text":"` and the closing `"`.
        val marker = "\"text\":\""
        val i = contentJson.indexOf(marker)
        if (i == -1) "" else contentJson.substring(i + marker.length).substringBefore('"')
    }

    // ── seedNewMessageBaseline ───────────────────────────────────

    @Test
    fun `seed maps each whom to its latest id`() {
        val baseline = seedNewMessageBaseline(
            rows = listOf(
                msg("~zod", "id-1"),
                msg("~bus", "id-2"),
            ),
        )
        assertEquals(mapOf("~zod" to "id-1", "~bus" to "id-2"), baseline)
    }

    @Test
    fun `seed of empty rows is empty map`() {
        assertEquals(emptyMap(), seedNewMessageBaseline(emptyList()))
    }

    // ── diffNewMessageNotifications: filtering ──────────────────

    @Test
    fun `same id as lastSeen does not fire`() {
        val diff = diffNewMessageNotifications(
            rows = listOf(msg("~zod", "id-1", author = "~bus")),
            lastSeen = mapOf("~zod" to "id-1"),
            ourPatp = "~zod",
            openChat = null,
            mutedWhoms = emptySet(),
            storyText = storyText,
        )
        assertTrue(diff.notifications.isEmpty())
        assertEquals(mapOf("~zod" to "id-1"), diff.newLastSeen)
    }

    @Test
    fun `new whom not in lastSeen fires once`() {
        val diff = diffNewMessageNotifications(
            rows = listOf(msg("~zod", "id-1", author = "~bus")),
            lastSeen = emptyMap(),
            ourPatp = "~me",
            openChat = null,
            mutedWhoms = emptySet(),
            storyText = storyText,
        )
        assertEquals(1, diff.notifications.size)
        val n = diff.notifications[0]
        assertEquals("~zod", n.whom)
        assertEquals("~bus", n.title)
    }

    @Test
    fun `updated id for known whom fires`() {
        val diff = diffNewMessageNotifications(
            rows = listOf(msg("~zod", "id-2", author = "~bus")),
            lastSeen = mapOf("~zod" to "id-1"),
            ourPatp = "~me",
            openChat = null,
            mutedWhoms = emptySet(),
            storyText = storyText,
        )
        assertEquals(1, diff.notifications.size)
        assertEquals("id-2", diff.newLastSeen["~zod"])
    }

    @Test
    fun `row authored by ourPatp does NOT fire (self-notify suppressed)`() {
        val diff = diffNewMessageNotifications(
            rows = listOf(msg("~zod", "id-2", author = "~me")),
            lastSeen = mapOf("~zod" to "id-1"),
            ourPatp = "~me",
            openChat = null,
            mutedWhoms = emptySet(),
            storyText = storyText,
        )
        assertTrue(diff.notifications.isEmpty(),
            "must not notify for messages the local user sent")
        // Critical: lastSeen still updates. Otherwise once the user
        // sends a message, every subsequent message in that chat
        // would re-fire because we'd still be comparing to the
        // pre-self-message id.
        assertEquals("id-2", diff.newLastSeen["~zod"])
    }

    @Test
    fun `row for openChat does NOT fire`() {
        val diff = diffNewMessageNotifications(
            rows = listOf(msg("~zod", "id-2", author = "~bus")),
            lastSeen = mapOf("~zod" to "id-1"),
            ourPatp = "~me",
            openChat = "~zod",  // user is staring at this chat
            mutedWhoms = emptySet(),
            storyText = storyText,
        )
        assertTrue(diff.notifications.isEmpty())
        assertEquals("id-2", diff.newLastSeen["~zod"],
            "lastSeen still advances even when suppressed")
    }

    @Test
    fun `row for muted whom does NOT fire`() {
        val diff = diffNewMessageNotifications(
            rows = listOf(msg("~zod", "id-2", author = "~bus")),
            lastSeen = mapOf("~zod" to "id-1"),
            ourPatp = "~me",
            openChat = null,
            mutedWhoms = setOf("~zod"),
            storyText = storyText,
        )
        assertTrue(diff.notifications.isEmpty())
        // lastSeen MUST advance. Otherwise unmuting wouldn't surface
        // the in-progress message until the next post arrives.
        assertEquals("id-2", diff.newLastSeen["~zod"])
    }

    // ── diffNewMessageNotifications: body formatting ───────────

    @Test
    fun `body is the rendered story text`() {
        val diff = diffNewMessageNotifications(
            rows = listOf(
                msg("~zod", "id-1", author = "~bus",
                    contentJson = """{"inline":[{"text":"hello there"}]}"""),
            ),
            lastSeen = emptyMap(),
            ourPatp = "~me",
            openChat = null,
            mutedWhoms = emptySet(),
            storyText = storyText,
        )
        assertEquals("hello there", diff.notifications[0].body)
    }

    @Test
    fun `newlines in the body are flattened to spaces`() {
        // We render in the OS notification balloon as a single line;
        // newlines from multi-line messages would either be stripped
        // by the OS or render badly. Flatten ahead of time.
        val customStoryText: (String, String) -> String =
            { _, _ -> "first\nsecond\nthird" }
        val diff = diffNewMessageNotifications(
            rows = listOf(msg("~zod", "id-1", author = "~bus")),
            lastSeen = emptyMap(),
            ourPatp = "~me",
            openChat = null,
            mutedWhoms = emptySet(),
            storyText = customStoryText,
        )
        assertEquals("first second third", diff.notifications[0].body)
    }

    @Test
    fun `body is truncated to 200 characters`() {
        val long = "a".repeat(500)
        val customStoryText: (String, String) -> String = { _, _ -> long }
        val diff = diffNewMessageNotifications(
            rows = listOf(msg("~zod", "id-1", author = "~bus")),
            lastSeen = emptyMap(),
            ourPatp = "~me",
            openChat = null,
            mutedWhoms = emptySet(),
            storyText = customStoryText,
        )
        assertEquals(200, diff.notifications[0].body.length)
    }

    @Test
    fun `blank story text falls back to attachment placeholder`() {
        // Image-only / file-only / quote-only messages render to
        // empty plaintext. Don't fire a balloon with an empty body.
        val customStoryText: (String, String) -> String = { _, _ -> "" }
        val diff = diffNewMessageNotifications(
            rows = listOf(msg("~zod", "id-1", author = "~bus")),
            lastSeen = emptyMap(),
            ourPatp = "~me",
            openChat = null,
            mutedWhoms = emptySet(),
            storyText = customStoryText,
        )
        assertEquals("(attachment)", diff.notifications[0].body)
    }

    // ── diffNewMessageNotifications: multi-row emissions ────────

    @Test
    fun `multiple changed whoms in one emission each fire their own notification`() {
        val diff = diffNewMessageNotifications(
            rows = listOf(
                msg("~zod", "id-2", author = "~bus"),
                msg("~bus", "id-2", author = "~zod"),
                msg("0vclub", "id-2", author = "~bar"),
            ),
            lastSeen = mapOf(
                "~zod" to "id-1",
                "~bus" to "id-1",
                "0vclub" to "id-1",
            ),
            ourPatp = "~me",
            openChat = null,
            mutedWhoms = emptySet(),
            storyText = storyText,
        )
        assertEquals(3, diff.notifications.size)
        assertEquals(
            setOf("~zod", "~bus", "0vclub"),
            diff.notifications.map { it.whom }.toSet(),
        )
    }

    @Test
    fun `mixed rows in one emission only fire for the unsuppressed ones`() {
        // ~zod: changed, foreign author → fire
        // ~bus: changed, but openChat → suppress
        // 0vclub: changed, muted → suppress
        // ~self: changed, ourPatp → suppress
        // ~stable: unchanged → suppress
        val diff = diffNewMessageNotifications(
            rows = listOf(
                msg("~zod", "id-new", author = "~external"),
                msg("~bus", "id-new", author = "~external"),
                msg("0vclub", "id-new", author = "~external"),
                msg("~self", "id-new", author = "~me"),
                msg("~stable", "id-old", author = "~external"),
            ),
            lastSeen = mapOf(
                "~zod" to "id-old",
                "~bus" to "id-old",
                "0vclub" to "id-old",
                "~self" to "id-old",
                "~stable" to "id-old",
            ),
            ourPatp = "~me",
            openChat = "~bus",
            mutedWhoms = setOf("0vclub"),
            storyText = storyText,
        )
        assertEquals(1, diff.notifications.size)
        assertEquals("~zod", diff.notifications[0].whom)
        // Every row updates the baseline regardless of suppression.
        assertEquals(
            mapOf(
                "~zod" to "id-new",
                "~bus" to "id-new",
                "0vclub" to "id-new",
                "~self" to "id-new",
                "~stable" to "id-old",
            ),
            diff.newLastSeen,
        )
    }

    @Test
    fun `null ourPatp does not suppress self-authored rows`() {
        // Edge case: signed-out state shouldn't crash the diff path
        // and shouldn't artificially silence things just because we
        // have no patp to compare against.
        val diff = diffNewMessageNotifications(
            rows = listOf(msg("~zod", "id-1", author = "~bus")),
            lastSeen = emptyMap(),
            ourPatp = null,
            openChat = null,
            mutedWhoms = emptySet(),
            storyText = storyText,
        )
        assertEquals(1, diff.notifications.size)
    }
}
