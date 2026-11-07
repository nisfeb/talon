package io.nisfeb.talon.ui

import okio.Path.Companion.toPath
import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MessageBubbleTest {
    // sneagan, 2026-11-05: a VIP colour for "the messages of people the
    // user has added as contacts".
    @Test
    fun `ours are ours, the book's are contacts', and the rest are theirs`() {
        val book = setOf("~nec", "~zod")
        assertEquals(BubbleKind.Mine, bubbleKindOf("~zod", "~zod", book), "ours, though we are in our own book")
        assertEquals(BubbleKind.Contact, bubbleKindOf("~nec", "~zod", book))
        assertEquals(BubbleKind.Theirs, bubbleKindOf("~bus", "~zod", book))
    }

    @Test
    fun `text keeps the theme's ink where it reads, and turns black or white where it does not`() {
        val ink = Color(0xFF1C1917)
        val page = Color(0xFFFFFFFF)
        assertEquals(ink, readableInk(Color(0xFFE7E5E4), page, ink), "the stock grey bubble keeps the theme's text")
        assertEquals(Color.White, readableInk(Color(0xFF1E1B4B), page, ink), "a navy bubble a theme set")
        assertEquals(Color.Black, readableInk(Color(0xFFFFF59D), Color(0xFF111111), Color(0xFFF5F5F4)), "a pale bubble on a dark theme")
        // A see-through fill is judged as it is drawn: over what is under it.
        assertEquals(Color.White, readableInk(Color(0xFF000000).copy(alpha = 0.22f), Color(0xFF202020), Color(0xFF303030)))
    }

    @Test
    fun `a picture is behind bubbles only, and none when unset`() {
        assertEquals(ChatLook(bubbles = true, image = "/kept/a.img".toPath()), chatLookOf(true, "/kept/a.img"))
        assertNull(chatLookOf(true, "").image, "none set is the page")
        assertEquals(ChatLook(bubbles = false, image = null), chatLookOf(false, "/kept/a.img"), "rows draw on the page")
    }
}
