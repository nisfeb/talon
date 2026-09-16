package io.nisfeb.talon.calendar

import io.nisfeb.talon.mail.AuspexApi
import kotlin.test.Test
import kotlin.test.assertEquals

class CalendarSharesTest {
    @Test fun `shares json decodes and read-only is the accepted calendars without edit`() {
        val s = AuspexApi.json.decodeFromString<Shares>(
            """{"shares":{"default":{"~sampel-palnet":"edit","~wex":"read"}},
                "offers":{"~feb/work":{"host":"~feb","cal":"work","name":"Work","color":"#101541","mode":"read","base":"/x","at_ms":1}},
                "accepted":{"s-abc":{"key":"~wex/home","mode":"read","last_ms":0,"error":""},"s-def":{"key":"~wex/team","mode":"edit","last_ms":5,"error":"host down"}}}""",
        )
        assertEquals(setOf("s-abc"), s.readOnly)
        assertEquals("~wex", s.accepted["s-def"]!!.host)
        assertEquals("edit", s.shares["default"]!!["~sampel-palnet"])
        assertEquals("Work", s.offers["~feb/work"]!!.name)
        assertEquals(emptySet(), AuspexApi.json.decodeFromString<Shares>("{}").readOnly, "an empty answer is no sharing at all")
    }
}
