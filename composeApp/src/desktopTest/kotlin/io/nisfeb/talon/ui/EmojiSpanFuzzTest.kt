package io.nisfeb.talon.ui

import io.nisfeb.talon.urbit.Fuzz
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Property-style fuzz coverage for the rc17 emoji-span builder.
 * The example tests pin known surrogate-pair / ZWJ / VS cases; this
 * sweeps random Unicode strings — including supplementary-plane
 * codepoints — to surface boundary bugs the example suite would miss.
 *
 * Invariants we lock in:
 *   - never throws
 *   - output `.text` equals input
 *   - every span covers exactly one codepoint (length 1 for BMP,
 *     length 2 for supplementary)
 *   - spans never overlap
 *   - no span starts inside a low-surrogate (the bug mode
 *     surrogate-pair handling regressions produce)
 */
class EmojiSpanFuzzTest {

    private val ITERATIONS = 1_000
    private val SEED = 1_000L

    /** Random Unicode string with a high mix of emoji / supplementary
     *  codepoints. Tighter than the urbit Fuzz.randomString harness,
     *  which biases toward markdown-y ASCII. */
    private fun randomUnicodeString(rnd: Random, maxLen: Int = 80): String {
        val len = rnd.nextInt(0, maxLen)
        return buildString {
            repeat(len) {
                when (rnd.nextInt(8)) {
                    // Plain ASCII
                    0, 1, 2 -> append(rnd.nextInt(0x20, 0x7F).toChar())
                    // BMP emoji range (☀ etc.)
                    3 -> appendCodePoint(rnd.nextInt(0x2300, 0x2BFF))
                    // Supplementary-plane emoji (👍 🎉 etc.)
                    4, 5 -> appendCodePoint(rnd.nextInt(0x1F000, 0x1FFFF))
                    // Combining marks / variation selectors / ZWJ
                    6 -> {
                        val choice = rnd.nextInt(4)
                        appendCodePoint(when (choice) {
                            0 -> 0x200D
                            1 -> rnd.nextInt(0xFE00, 0xFE10)
                            2 -> rnd.nextInt(0x1F1E6, 0x1F200)  // regional indicators
                            else -> rnd.nextInt(0x20E0, 0x2100)  // combining keycap
                        })
                    }
                    // High BMP that's NOT in our emoji range — must NOT get spanned
                    else -> appendCodePoint(rnd.nextInt(0x4E00, 0x9FFF))  // CJK
                }
            }
        }
    }

    private fun StringBuilder.appendCodePoint(cp: Int) {
        if (cp >= 0x10000) {
            // Surrogate pair
            val adj = cp - 0x10000
            append(((adj ushr 10) + 0xD800).toChar())
            append(((adj and 0x3FF) + 0xDC00).toChar())
        } else {
            append(cp.toChar())
        }
    }

    @Test
    fun `applyEmojiSpans never throws on random Unicode strings`() {
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            val input = randomUnicodeString(rnd)
            input.applyEmojiSpans()
        }
    }

    @Test
    fun `applyEmojiSpans preserves input text exactly`() {
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            val input = randomUnicodeString(rnd)
            val out = input.applyEmojiSpans()
            assertEquals("text content must be unchanged", input, out.text)
        }
    }

    @Test
    fun `applyEmojiSpans spans are codepoint-aligned`() {
        // Each emoji span must cover exactly one codepoint:
        //   - 1 char wide for BMP code points
        //   - 2 chars wide for supplementary (surrogate-pair) code points
        // Spans must never start inside a surrogate pair or in the
        // middle of a multi-char codepoint.
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            val input = randomUnicodeString(rnd)
            val out = input.applyEmojiSpans()
            for (span in out.spanStyles) {
                val width = span.end - span.start
                assertTrue(
                    "span width must be 1 or 2 (got $width); input=$input span=[${span.start},${span.end})",
                    width == 1 || width == 2,
                )
                if (width == 2) {
                    // Confirm span covers a high+low surrogate pair
                    val high = input[span.start]
                    val low = input[span.start + 1]
                    assertTrue(
                        "2-char span must start with a high surrogate; " +
                            "input=$input start=${span.start} char=${high.code.toString(16)}",
                        high.isHighSurrogate(),
                    )
                    assertTrue(
                        "2-char span must end with a low surrogate; " +
                            "input=$input end=${span.end - 1} char=${low.code.toString(16)}",
                        low.isLowSurrogate(),
                    )
                } else {
                    // Width 1 — must NOT start inside a surrogate pair
                    val c = input[span.start]
                    assertFalse(
                        "1-char span must not target a surrogate; " +
                            "input=$input start=${span.start} char=${c.code.toString(16)}",
                        c.isLowSurrogate() || c.isHighSurrogate(),
                    )
                }
            }
        }
    }

    @Test
    fun `applyEmojiSpans spans never overlap`() {
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            val input = randomUnicodeString(rnd)
            val spans = input.applyEmojiSpans().spanStyles.sortedBy { it.start }
            for (i in 1 until spans.size) {
                val prev = spans[i - 1]
                val curr = spans[i]
                assertTrue(
                    "overlapping spans: prev=[${prev.start},${prev.end}) curr=[${curr.start},${curr.end})",
                    prev.end <= curr.start,
                )
            }
        }
    }

    @Test
    fun `applyEmojiSpans on AnnotatedString preserves input text`() {
        // The AnnotatedString variant should also leave the text
        // alone — only span styles are added.
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            val input = randomUnicodeString(rnd)
            val annotated = androidx.compose.ui.text.AnnotatedString(input)
            val out = annotated.applyEmojiSpans()
            assertEquals(input, out.text)
        }
    }

    @Test
    fun `applyEmojiSpans is idempotent at the text level`() {
        // Running applyEmojiSpans twice on its output should yield
        // the same .text. (It re-adds spans, so spans count may grow,
        // but the visible text is invariant.)
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            val input = randomUnicodeString(rnd)
            val once = input.applyEmojiSpans()
            val twice = once.applyEmojiSpans()
            assertEquals(once.text, twice.text)
        }
    }
}
