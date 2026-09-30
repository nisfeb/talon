package io.nisfeb.talon.ui

import io.nisfeb.talon.data.RailItemPrefEntity
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pin the row-list → Map<RailItem, Boolean> projection extracted from
 * AndroidUiSettings + DesktopUiSettings. The whole AndroidUiSettings
 * flatMapLatest path is too entangled with platform classes to unit
 * test directly, so we lift the pure mapping out and exercise it
 * here.
 */
class RailVisibilityProjectionTest {

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

}
