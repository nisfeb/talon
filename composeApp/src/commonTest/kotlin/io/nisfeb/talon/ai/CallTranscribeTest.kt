package io.nisfeb.talon.ai

import kotlin.test.Test
import kotlin.test.assertEquals

class CallTranscribeTest {

    @Test
    fun parsesVerboseJsonSegments() {
        val body = """
            {"task":"transcribe","language":"english","duration":6.0,
             "text":"Hello there. General Kenobi.",
             "segments":[
               {"id":0,"start":0.0,"end":2.5,"text":" Hello there."},
               {"id":1,"start":2.5,"end":6.0,"text":" General Kenobi."}
             ]}
        """.trimIndent()
        val segs = CallTranscribe.parse(body)
        assertEquals(2, segs.size)
        assertEquals(0L, segs[0].startMs)
        assertEquals("Hello there.", segs[0].text)
        assertEquals(2500L, segs[1].startMs)
        assertEquals("General Kenobi.", segs[1].text)
    }

    @Test
    fun fallsBackToWholeTextWhenNoSegments() {
        val segs = CallTranscribe.parse("""{"text":"just the whole thing"}""")
        assertEquals(1, segs.size)
        assertEquals(0L, segs[0].startMs)
        assertEquals("just the whole thing", segs[0].text)
    }

    @Test
    fun emptyOnGarbageOrNoText() {
        assertEquals(0, CallTranscribe.parse("not json").size)
        assertEquals(0, CallTranscribe.parse("""{"text":"   "}""").size)
    }
}
