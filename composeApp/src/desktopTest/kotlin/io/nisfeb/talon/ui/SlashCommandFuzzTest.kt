package io.nisfeb.talon.ui

import io.nisfeb.talon.urbit.Fuzz
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Property-style fuzzing for the new slash-command parsers + helpers.
 * The invariants matter more than the test count: any uncaught throw
 * crashes the composer when the user types unusual input, so "never
 * throws on arbitrary strings" is the load-bearing property.
 *
 * Seeds are logged on failure — copy a seed into the test to reproduce.
 */
class SlashCommandFuzzTest {

    private val ITERATIONS = 1_000
    // Deterministic — change to System.currentTimeMillis() to explore.
    private val SEED = 1_000L

    // ─── parseSlash + detectSlashTrigger never throw ────────────────

    @Test
    fun `parseSlash never throws on arbitrary strings`() {
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            parseSlash(Fuzz.randomString(rnd, maxLen = 200))
        }
    }

    @Test
    fun `detectSlashTrigger never throws on arbitrary cursor positions`() {
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            val text = Fuzz.randomString(rnd, maxLen = 100)
            // Probe boundary conditions plus interior + out-of-bounds.
            val cursors = listOf(
                -1, 0, 1, text.length / 2, text.length - 1,
                text.length, text.length + 1, Int.MAX_VALUE,
            )
            for (c in cursors) detectSlashTrigger(text, c)
        }
    }

    // ─── parseCalText invariants ────────────────────────────────────

    @Test
    fun `parseCalText Ok result always has end greater than or equal to start`() {
        // The screen renders start..end and would crash / show garbage
        // if end < start. Pin that on every successful parse.
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            val r = parseCalText(Fuzz.randomString(rnd, maxLen = 200))
            if (r is CalParseResult.Ok) {
                assertTrue(
                    "end (${r.endMs}) must be >= start (${r.startMs}) — title=${r.title}",
                    r.endMs >= r.startMs,
                )
            }
        }
    }

    @Test
    fun `parseTimeToken result always has clamped hour and minute`() {
        // clampHM enforces h in 0..23 and m in 0..59 — verify no
        // out-of-range value escapes.
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            val tr = parseTimeToken(Fuzz.randomString(rnd, maxLen = 50)) ?: return@run
            assertTrue("start.h=${tr.start.h}", tr.start.h in 0..23)
            assertTrue("start.m=${tr.start.m}", tr.start.m in 0..59)
            tr.end?.let {
                assertTrue("end.h=${it.h}", it.h in 0..23)
                assertTrue("end.m=${it.m}", it.m in 0..59)
            }
        }
        // Random strings rarely make an out-of-range time, so name some.
        for (t in listOf("25", "9:75", "30-31", "9-25")) assertNull(t, parseTimeToken(t))
    }

    // ─── parseTzInput invariants ────────────────────────────────────

    @Test
    fun `resolveZoneToken never throws on arbitrary strings`() {
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            resolveZoneToken(Fuzz.randomString(rnd, maxLen = 100))
        }
    }

    @Test
    fun `parseTzInput Ok result is anchored to today or tomorrow`() {
        // The impl bumps to tomorrow if the resolved instant is before
        // now. The result must therefore be >= now within a day window.
        // Allow a tiny clock-skew slack since parseTzInput captures
        // its own `now` snapshot internally.
        val now = io.nisfeb.talon.util.nowMs()
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            val r = parseTzInput(Fuzz.randomString(rnd, maxLen = 100), now)
            if (r is TzParseResult.Ok) {
                assertFalse(
                    "instant ${r.instantMs} must not be before now ($now)",
                    r.instantMs < now,
                )
                val msInTwoDays = 2L * 24L * 60L * 60L * 1000L
                assertTrue(
                    "instant ${r.instantMs} more than 2 days past now",
                    r.instantMs - now < msInTwoDays,
                )
            }
        }
    }

    // ─── parsePollInput invariants ──────────────────────────────────

    @Test
    fun `parsePollInput never throws on arbitrary strings`() {
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            parsePollInput(Fuzz.randomString(rnd, maxLen = 600))
        }
    }

    // ─── slash-command spec: filter never crashes ──────────────────

}
