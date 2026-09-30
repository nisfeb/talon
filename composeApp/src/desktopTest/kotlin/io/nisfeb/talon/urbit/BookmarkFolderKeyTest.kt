package io.nisfeb.talon.urbit

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The %settings entry key for a bookmark-folder membership encodes
 * (folderId, whom, postId) into one string. The encoding has to be
 * lossless because the inbound %settings sync recreates the entity
 * from the key alone — bad parsing on a real key silently drops the
 * row, so an inbound dropped folder grouping wouldn't be obvious
 * until the user notices their organization is gone.
 *
 * The pipe-`|` separator is safe: whom can carry `:` and `/`, postId
 * can carry `~` and `/`, neither carries `|`.
 */
class BookmarkFolderKeyTest {

    @Test
    fun `tolerates extra pipes inside postId`() {
        // postId itself shouldn't contain `|`, but if a future wire
        // shape introduces one we'd rather take the whole tail than
        // truncate. The 3-way split with limit=3 makes the third
        // chunk the rest of the string.
        val out = parseBookmarkFolderMemberKey(
            "5|~peer|weird|tail|with|pipes",
        )
        assertEquals(5L, out!!.first)
        assertEquals("~peer", out.second)
        assertEquals("weird|tail|with|pipes", out.third)
    }
}
