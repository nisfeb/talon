package io.nisfeb.talon.urbit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A folder's id is its key on the ship (review H7, 2026-10-17): unique
 * across devices, still a number every build reads, ordered by when it
 * was made, and short of 2^53 for any clock this side of 2200.
 */
class FolderIdTest {
    @Test
    fun `a folder id is the time and three digits, and reads back as a number`() {
        val t = 1_784_592_399_123L
        repeat(200) {
            val id = newFolderId(t)
            assertEquals(t, id / 1000, "$id")
            assertEquals(id, id.toString().toLongOrNull(), "rc10 reads a key with toLongOrNull")
        }
        assertTrue(newFolderId(t) < newFolderId(t + 1), "ordered by when it was made")
        val year2200 = 7_258_118_400_000L
        assertTrue(newFolderId(year2200) < (1L shl 53))
        assertTrue((1..200).map { newFolderId(t) }.toSet().size > 1, "not one fixed number")
    }
}
