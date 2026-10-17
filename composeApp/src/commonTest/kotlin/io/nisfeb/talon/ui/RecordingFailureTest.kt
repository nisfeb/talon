package io.nisfeb.talon.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/** The recording dialog says why a publish or transcription failed in words, never the exception's text. */
class RecordingFailureTest {
    @Test
    fun aFailureReadsAsWords() {
        assertEquals(
            "transcription failed for every speaker (~bus).",
            failedWhy(IllegalStateException("transcription failed for every speaker (~bus)")),
        )
        assertEquals(
            "something went wrong.",
            failedWhy(RuntimeException("Request timeout has expired [url=https://ship.test/~/scry/x, request_timeout=30000 ms]")),
        )
        assertEquals("something went wrong.", failedWhy(RuntimeException("io.ktor.client.plugins.ClientRequestException: {\"error\":1}")))
        assertEquals("something went wrong.", failedWhy(RuntimeException()))
    }
}
