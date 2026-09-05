package io.nisfeb.talon.urbit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TranscriptGemtextTest {

    @Test
    fun mergesSpeakersByTimeAndGroupsRuns() {
        val gmi = TranscriptGemtext.build(
            title = "Standup",
            whenLabel = "Sep 5, 2026",
            participants = listOf("~sampel-palnet", "~ricsul-bilwyt"),
            utterances = listOf(
                TranscriptGemtext.Utterance("~ricsul-bilwyt", 4000, "and then it worked"),
                TranscriptGemtext.Utterance("~sampel-palnet", 0, "hello"),
                TranscriptGemtext.Utterance("~sampel-palnet", 1500, "let's begin"),
            ),
        )
        // Title + participant meta present.
        assertTrue(gmi.startsWith("# Standup"))
        assertTrue(gmi.contains("Sep 5, 2026 · ~sampel-palnet, ~ricsul-bilwyt"))
        // Ordered by time: sampel (0:00) before ricsul (0:04), and the
        // two consecutive sampel lines share one byline.
        val sampelHeads = Regex("## ~sampel-palnet").findAll(gmi).count()
        assertEquals(1, sampelHeads)
        assertTrue(gmi.indexOf("~sampel-palnet") < gmi.indexOf("~ricsul-bilwyt · 0:04"))
        assertTrue(gmi.contains("hello\nlet's begin"))
    }

    @Test
    fun clockFormatsMinutesAndHours() {
        assertEquals("0:00", TranscriptGemtext.clock(0))
        assertEquals("0:05", TranscriptGemtext.clock(5_000))
        assertEquals("2:03", TranscriptGemtext.clock(123_000))
        assertEquals("1:00:00", TranscriptGemtext.clock(3_600_000))
    }

    @Test
    fun emptyTranscriptStillMakesAPage() {
        val gmi = TranscriptGemtext.build("L", "today", listOf("~zod"), emptyList())
        assertTrue(gmi.contains("No speech was transcribed"))
    }
}
