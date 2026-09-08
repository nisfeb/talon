package io.nisfeb.talon.ai

import io.nisfeb.talon.urbit.TranscriptGemtext
import kotlin.test.Test
import kotlin.test.assertEquals

class TranscriptTextTest {
    @Test
    fun linesCarryTimeAndSpeakerInOrder() {
        val t = CallRecordingPublisher.Transcript(
            utterances = listOf(
                TranscriptGemtext.Utterance("Bus", 4_000, "second"),
                TranscriptGemtext.Utterance("Zod", 1_000, "first "),
            ).sortedBy { it.startMs },
            failed = listOf("Nec"),
        )
        val text = CallRecordingPublisher.transcriptText("Standup", "Sep 8", listOf("Zod", "Bus", "Nec"), t)
        assertEquals(
            "Standup · Sep 8\nSpeakers: Zod, Bus, Nec\nCould not transcribe: Nec\n\n" +
                "[${TranscriptGemtext.clock(1_000)}] Zod: first\n[${TranscriptGemtext.clock(4_000)}] Bus: second\n",
            text,
        )
    }
}
