package io.nisfeb.talon.ui

import io.nisfeb.talon.data.ContactEntity
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pin the `sameContactDisplay` predicate that suppresses status-only
 * re-emissions on the contacts flow. The whole point of the rc13
 * change: status pings (every minute or so on busy networks) must
 * NOT trigger ContactMap rebuilds that recompose every chat row.
 *
 * Display fields (the ones the chat list actually reads): ship,
 * nickname, avatarUrl, color. Everything else (status, statusUpdatedMs,
 * bio) is irrelevant to ContactMap and shouldn't tick the flow.
 */
class ContactsTest {

    private fun base(
        ship: String = "~zod",
        nickname: String? = "zod",
        avatarUrl: String? = "https://example.test/avatar.png",
        color: String? = "#abcdef",
        status: String? = null,
        statusUpdatedMs: Long? = null,
        bio: String? = null,
    ) = ContactEntity(
        ship = ship,
        nickname = nickname,
        bio = bio,
        avatarUrl = avatarUrl,
        status = status,
        statusUpdatedMs = statusUpdatedMs,
        color = color,
    )

    @Test
    fun `same instance reference is equal`() {
        val list = listOf(base())
        assertTrue(sameContactDisplay(list, list))
    }

    @Test
    fun `identical content is equal`() {
        assertTrue(sameContactDisplay(listOf(base()), listOf(base())))
    }

    @Test
    fun `different sizes are not equal`() {
        assertFalse(sameContactDisplay(listOf(base()), emptyList()))
        assertFalse(sameContactDisplay(emptyList(), listOf(base())))
        assertFalse(sameContactDisplay(
            listOf(base("~zod"), base("~bus")),
            listOf(base("~zod")),
        ))
    }

    @Test
    fun `status-only diff is suppressed`() {
        // The crucial case: the contacts table re-emits when ANY field
        // changes, but ContactMap doesn't read status. We treat these
        // emissions as no-ops to avoid recomposing the chat list.
        val a = listOf(base(status = "old", statusUpdatedMs = 1L))
        val b = listOf(base(status = "new", statusUpdatedMs = 2L))
        assertTrue(
            sameContactDisplay(a, b),
            "status-only changes must not propagate as ContactMap rebuilds",
        )
    }

    @Test
    fun `bio-only diff is suppressed`() {
        // Bio isn't displayed by the chat list either — only the
        // profile sheet reads it. Bio changes shouldn't tick the
        // contacts flow.
        val a = listOf(base(bio = null))
        val b = listOf(base(bio = "I am a person"))
        assertTrue(sameContactDisplay(a, b))
    }

    @Test
    fun `nickname change is propagated`() {
        val a = listOf(base(nickname = "zod"))
        val b = listOf(base(nickname = "Zorro"))
        assertFalse(sameContactDisplay(a, b))
    }

    @Test
    fun `avatar change is propagated`() {
        val a = listOf(base(avatarUrl = "a.png"))
        val b = listOf(base(avatarUrl = "b.png"))
        assertFalse(sameContactDisplay(a, b))
    }

    @Test
    fun `color change is propagated`() {
        val a = listOf(base(color = "#aabbcc"))
        val b = listOf(base(color = "#ddeeff"))
        assertFalse(sameContactDisplay(a, b))
    }

    @Test
    fun `ship swap at the same index is propagated`() {
        // Comparison is positional. If the DAO returns rows in a
        // different order (e.g., new contact added), we want to
        // re-emit so the new row is added to ContactMap.
        val a = listOf(base("~zod"), base("~bus"))
        val b = listOf(base("~bus"), base("~zod"))
        assertFalse(
            sameContactDisplay(a, b),
            "reordering counts as a change — ContactMap reflects positions",
        )
    }

    @Test
    fun `nickname change in just one row is propagated`() {
        val a = listOf(base("~zod", nickname = "zod"), base("~bus", nickname = "bus"))
        val b = listOf(base("~zod", nickname = "zod"), base("~bus", nickname = "Bussy"))
        assertFalse(sameContactDisplay(a, b))
    }

    @Test
    fun `null vs non-null nickname is propagated`() {
        // Going from "no nickname" to "has nickname" matters for
        // display (renders the nickname instead of the patp).
        val a = listOf(base(nickname = null))
        val b = listOf(base(nickname = "zod"))
        assertFalse(sameContactDisplay(a, b))
    }
}
