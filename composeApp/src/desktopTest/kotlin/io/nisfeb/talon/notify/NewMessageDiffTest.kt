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
        sentMs: Long = 0L,
    ) = MessageEntity(
        whom = whom,
        id = id,
        author = author,
        sentMs = sentMs,
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

    // ── diffNewMessageNotifications: staleness guard ────────────

    @Test
    fun `stale backlog message does not fire but advances baseline`() {
        val now = 1_700_000_000_000L
        val diff = diffNewMessageNotifications(
            rows = listOf(msg("~zod", "old", author = "~bus", sentMs = now - 60L * 60_000L)),
            lastSeen = emptyMap(),
            ourPatp = "~zod",
            openChat = null,
            levels = emptyMap(),
            storyText = storyText,
            nowMs = now,
            freshnessMaxAgeMs = 5L * 60_000L,
        )
        // 1h-old message with a 5min window → suppressed.
        assertTrue(diff.notifications.isEmpty())
        // But baseline advanced, so it won't fire on a later pass.
        assertEquals(mapOf("~zod" to "old"), diff.newLastSeen)
    }

    @Test
    fun `fresh message within the window fires`() {
        val now = 1_700_000_000_000L
        val diff = diffNewMessageNotifications(
            rows = listOf(msg("~zod", "new", author = "~bus", sentMs = now - 30_000L)),
            lastSeen = emptyMap(),
            ourPatp = "~zod",
            openChat = null,
            levels = emptyMap(),
            storyText = storyText,
            nowMs = now,
            freshnessMaxAgeMs = 5L * 60_000L,
        )
        assertEquals(1, diff.notifications.size)
    }

    // ── diffNewMessageNotifications: filtering ──────────────────

    // A comet's @p is fifty-six characters of key fingerprint. Whoever
    // reads the balloon needs the name the rest of the app gives it.
    @Test
    fun `a message is announced by what its author is called`() {
        val comet = "~dozzod-dozzod-dozzod-dozzod--dozzod-dozzod-dozzod-dozzod"
        val diff = diffNewMessageNotifications(
            rows = listOf(msg("~zod", "id-1", author = comet)),
            lastSeen = emptyMap(),
            ourPatp = "~me",
            openChat = null,
            levels = emptyMap(),
            storyText = storyText,
            nameFor = { if (it == comet) "..lucky.dozen" else it },
        )
        assertEquals("..lucky.dozen", diff.notifications[0].title)
    }

    // ── diffNewMessageNotifications: body formatting ───────────

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
            levels = emptyMap(),
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
            levels = emptyMap(),
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
            levels = emptyMap(),
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
            levels = emptyMap(),
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
            levels = mapOf("0vclub" to "none"),
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
            levels = emptyMap(),
            storyText = storyText,
        )
        assertEquals(1, diff.notifications.size)
    }

    // ── levels: "mentions" is the default and means it ─────────────

    private fun groupRow(contentJson: String) = MessageEntity(
        whom = "chat/~host/general",
        id = "m1",
        author = "~bud",
        sentMs = 0L,
        contentJson = contentJson,
        kind = "note",
    )

    private fun fire(rows: List<MessageEntity>, levels: Map<String, String>) =
        diffNewMessageNotifications(
            rows = rows,
            lastSeen = emptyMap(),
            ourPatp = "~zod",
            openChat = null,
            levels = levels,
            storyText = { _, json -> json },
        ).notifications.size

    @Test
    fun `group channel at the default level fires only on a mention`() {
        assertEquals(0, fire(listOf(groupRow("""{"inline":["hi all"]}""")), emptyMap()))
        assertEquals(1, fire(listOf(groupRow("""{"inline":[{"ship":"~zod"}," hi"]}""")), emptyMap()))
    }

    @Test
    fun `all notifies everything and none nothing`() {
        val plain = groupRow("""{"inline":["hi all"]}""")
        assertEquals(1, fire(listOf(plain), mapOf(plain.whom to "all")))
        val mention = groupRow("""{"inline":[{"ship":"~zod"}]}""")
        assertEquals(0, fire(listOf(mention), mapOf(plain.whom to "none")))
    }

}
