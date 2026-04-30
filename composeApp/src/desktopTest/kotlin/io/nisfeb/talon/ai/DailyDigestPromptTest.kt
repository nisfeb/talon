package io.nisfeb.talon.ai

import io.nisfeb.talon.data.Bucket
import io.nisfeb.talon.data.DigestItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyDigestPromptTest {

    private fun item(
        bucket: Bucket,
        author: String = "~sampel",
        snippet: String = "hello",
        sentMs: Long = 1_000L,
    ) = DigestItem(
        whom = "~h/s",
        postId = "0v1.abc",
        authorPatp = author,
        sentMs = sentMs,
        bucket = bucket,
        snippet = snippet,
    )

    @Test fun `empty list returns explicit placeholder`() {
        val out = DailyDigestPrompt.format(emptyList()) { it }
        assertEquals("(no messages)", out)
    }

    @Test fun `groups by bucket in priority order`() {
        val out = DailyDigestPrompt.format(
            listOf(
                item(Bucket.UNREAD, snippet = "u1"),
                item(Bucket.MENTION, snippet = "m1"),
                item(Bucket.WATCHWORD, snippet = "w1"),
            )
        ) { it }
        // Bucket headers must appear in MENTION → WATCHWORD → UNREAD order.
        val mentionIdx = out.indexOf("[mentions]")
        val wIdx = out.indexOf("[watchwords]")
        val uIdx = out.indexOf("[unread]")
        assertTrue(mentionIdx in 0 until wIdx)
        assertTrue(wIdx < uIdx)
    }

    @Test fun `truncates long snippets to 200 chars per line`() {
        val long = "x".repeat(500)
        val out = DailyDigestPrompt.format(listOf(item(Bucket.MENTION, snippet = long))) { it }
        // No single line in the output can exceed ~250 chars (200 snippet
        // + author + bucket prefix). Use 260 as the safety bound.
        for (line in out.lines()) {
            assertTrue("line too long: ${line.length}", line.length <= 260)
        }
    }

    @Test fun `display-name resolver is applied`() {
        val out = DailyDigestPrompt.format(listOf(item(Bucket.UNREAD, author = "~sampel"))) {
            if (it == "~sampel") "Sampel" else it
        }
        assertTrue(out.contains("Sampel"))
        assertTrue(!out.contains("~sampel"))
    }

    @Test fun `omits empty bucket headers`() {
        val out = DailyDigestPrompt.format(listOf(item(Bucket.MENTION))) { it }
        assertTrue(out.contains("[mentions]"))
        assertTrue(!out.contains("[watchwords]"))
        assertTrue(!out.contains("[unread]"))
    }
}
