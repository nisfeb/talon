package io.nisfeb.talon.urbit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `parses a channel-bookmark key`() {
        // Real-world shape: folder 7, channel post under chat/~host/slug,
        // raw @ud post id (channels don't have author prefix).
        val out = parseBookmarkFolderMemberKey(
            "7|chat/~host/slug|170141184507933044937549665940933705728",
        )
        assertEquals(7L, out!!.first)
        assertEquals("chat/~host/slug", out.second)
        assertEquals("170141184507933044937549665940933705728", out.third)
    }

    @Test
    fun `parses a DM-bookmark key with author-prefixed post id`() {
        // DM writs use `~author/<da>` post ids; the author and slash
        // both have to ride through the parser intact.
        val out = parseBookmarkFolderMemberKey(
            "42|~sampel-palnet|~ricsul-bilwyt-dozzod-nisfeb/170141184507",
        )
        assertEquals(42L, out!!.first)
        assertEquals("~sampel-palnet", out.second)
        assertEquals("~ricsul-bilwyt-dozzod-nisfeb/170141184507", out.third)
    }

    @Test
    fun `parses a club-bookmark key with dot-grouped club id`() {
        // Club ids carry dots; they survive because we split on `|`,
        // not `.`. Locks against a future regression where someone
        // helpfully tightens the parser to disallow dots in whom.
        val out = parseBookmarkFolderMemberKey(
            "1|0v4.abcde.fghij|~author/170141184507",
        )
        assertEquals(1L, out!!.first)
        assertEquals("0v4.abcde.fghij", out.second)
        assertEquals("~author/170141184507", out.third)
    }

    @Test
    fun `rejects a key with too few segments`() {
        assertNull(parseBookmarkFolderMemberKey("7|chat/~host/slug"))
        assertNull(parseBookmarkFolderMemberKey("7|"))
        assertNull(parseBookmarkFolderMemberKey("only-one"))
    }

    @Test
    fun `rejects a key whose folderId is non-numeric`() {
        assertNull(parseBookmarkFolderMemberKey("not-a-number|whom|post"))
    }

    @Test
    fun `rejects a key with an empty whom`() {
        // Empty whom would silently dedupe with itself across folders;
        // safer to drop the row. Same reasoning for empty postId.
        assertNull(parseBookmarkFolderMemberKey("7||~author/170141"))
        assertNull(parseBookmarkFolderMemberKey("7|~author|"))
    }

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
