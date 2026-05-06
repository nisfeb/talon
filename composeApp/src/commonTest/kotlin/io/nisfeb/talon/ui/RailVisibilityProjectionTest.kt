package io.nisfeb.talon.ui

import io.nisfeb.talon.data.RailItemPrefEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pin the row-list → Map<RailItem, Boolean> projection extracted from
 * AndroidUiSettings + DesktopUiSettings. The whole AndroidUiSettings
 * flatMapLatest path is too entangled with platform classes to unit
 * test directly, so we lift the pure mapping out and exercise it
 * here.
 */
class RailVisibilityProjectionTest {

    @Test
    fun `empty rows map to empty visibility`() {
        assertEquals(emptyMap(), railVisibilityFromRows(emptyList()))
    }

    @Test
    fun `each known row becomes a Map entry preserving visibility`() {
        val rows = listOf(
            RailItemPrefEntity("Settings", false),
            RailItemPrefEntity("Statuses", false),
        )
        val out = railVisibilityFromRows(rows)
        assertEquals(false, out[RailItem.Settings])
        assertEquals(false, out[RailItem.Statuses])
        assertEquals(2, out.size)
    }

    @Test
    fun `rows with unknown itemName are dropped silently`() {
        // Future-proofing: if a peer device serializes a value this
        // version doesn't know, we don't crash — we drop it.
        val rows = listOf(
            RailItemPrefEntity("Statuses", false),
            RailItemPrefEntity("ThisIsNotAValidEnum", false),
            RailItemPrefEntity("Bookmarks", false),
        )
        val out = railVisibilityFromRows(rows)
        assertEquals(setOf(RailItem.Statuses, RailItem.Bookmarks), out.keys)
    }

    @Test
    fun `visible-true rows pass through (sparse semantics handled by isVisible)`() {
        // The projection itself doesn't filter on visibility — it just
        // copies rows as-is. The sparse "absent → true" contract is
        // enforced by Map.isVisible at the read site.
        val rows = listOf(RailItemPrefEntity("Statuses", true))
        val out = railVisibilityFromRows(rows)
        assertEquals(true, out[RailItem.Statuses])
    }

    @Test
    fun `duplicated itemName takes the last row's visibility`() {
        // The DAO has a primary key on itemName so this shouldn't
        // happen in practice, but the projection's behavior is
        // pinned anyway via toMap()'s last-wins.
        val rows = listOf(
            RailItemPrefEntity("Settings", true),
            RailItemPrefEntity("Settings", false),
        )
        val out = railVisibilityFromRows(rows)
        assertEquals(false, out[RailItem.Settings])
        assertEquals(1, out.size)
    }

    @Test
    fun `result is a finite Map keyed by RailItem`() {
        // Type-check via use: ensures a future refactor doesn't accidentally
        // change the keys to String.
        val rows = listOf(RailItemPrefEntity("Settings", false))
        val out: Map<RailItem, Boolean> = railVisibilityFromRows(rows)
        assertTrue(out.keys.all { it is RailItem })
    }
}
