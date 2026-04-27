package io.nisfeb.talon.ai

import io.nisfeb.talon.urbit.Fuzz
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class DailyDigestFuzzTest {

    private val ITERATIONS = 1_000
    private val SEED = 1_000L

    // ─── DailyDigestSchedule ──────────────────────────────────

    @Test
    fun `nextFireMs never throws on arbitrary inputs`() {
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            val now = Instant.ofEpochSecond(rnd.nextLong(0L, 4_000_000_000L))
            val hour = rnd.nextInt(0, 24)
            val minute = rnd.nextInt(0, 60)
            DailyDigestSchedule.nextFireMs(now, hour, minute, ZoneId.of("UTC"))
        }
    }

    @Test
    fun `nextFireMs result is always strictly greater than now`() {
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            val now = Instant.ofEpochSecond(rnd.nextLong(0L, 4_000_000_000L))
            val hour = rnd.nextInt(0, 24)
            val minute = rnd.nextInt(0, 60)
            val next = DailyDigestSchedule.nextFireMs(now, hour, minute, ZoneId.of("UTC"))
            assertTrue("next=$next now=${now.toEpochMilli()}", next > now.toEpochMilli())
        }
    }

    @Test
    fun `nextFireMs result is at most 24h plus 1 hour from now`() {
        // 25h to allow for DST forward (which can stretch the day).
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            val now = Instant.ofEpochSecond(rnd.nextLong(0L, 4_000_000_000L))
            val hour = rnd.nextInt(0, 24)
            val minute = rnd.nextInt(0, 60)
            val next = DailyDigestSchedule.nextFireMs(now, hour, minute, ZoneId.of("UTC"))
            val maxMs = now.toEpochMilli() + 25L * 60 * 60 * 1000
            assertTrue("next=$next maxMs=$maxMs", next <= maxMs)
        }
    }

    // ─── DailyDigestMentionMatcher ────────────────────────────

    @Test
    fun `containsMention never throws`() {
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            DailyDigestMentionMatcher.containsMention(
                Fuzz.randomString(rnd, 200),
                Fuzz.randomString(rnd, 30),
            )
        }
    }

    @Test
    fun `containsMention is deterministic`() {
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            val h = Fuzz.randomString(rnd, 200)
            val p = Fuzz.randomString(rnd, 30)
            assertEquals(
                DailyDigestMentionMatcher.containsMention(h, p),
                DailyDigestMentionMatcher.containsMention(h, p),
            )
        }
    }

    @Test
    fun `containsMention case-insensitivity invariant`() {
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            val h = Fuzz.randomString(rnd, 200)
            val p = Fuzz.randomString(rnd, 30)
            val orig = DailyDigestMentionMatcher.containsMention(h, p)
            assertEquals(orig, DailyDigestMentionMatcher.containsMention(h.uppercase(), p.lowercase()))
            assertEquals(orig, DailyDigestMentionMatcher.containsMention(h.lowercase(), p.uppercase()))
        }
    }

    @Test
    fun `containsMention empty patp returns false`() {
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            assertEquals(false, DailyDigestMentionMatcher.containsMention(
                Fuzz.randomString(rnd, 200), ""))
        }
    }

    // ─── DailyDigestPrompt ────────────────────────────────────

    @Test
    fun `format never throws on empty list`() {
        DailyDigestPrompt.format(emptyList()) { it }
    }

    @Test
    fun `format output line length bounded`() {
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            val items = List(rnd.nextInt(0, 10)) {
                io.nisfeb.talon.data.DigestItem(
                    whom = "~h/s",
                    postId = "0v1.${rnd.nextInt()}",
                    authorPatp = "~sampel",
                    sentMs = rnd.nextLong(),
                    bucket = io.nisfeb.talon.data.Bucket.values().random(rnd),
                    snippet = Fuzz.randomString(rnd, 500),
                )
            }
            val out = DailyDigestPrompt.format(items) { it }
            for (line in out.lines()) {
                assertTrue("line too long: ${line.length}", line.length <= 260)
            }
        }
    }
}
