package io.nisfeb.talon.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class WatchwordSanitizerTest {

    @Test fun `lowercases`() {
        assertEquals("mars", sanitizeTerm("Mars"))
        assertEquals("rfp", sanitizeTerm("RFP"))
    }

    @Test fun `replaces single non-alphanumeric with underscore`() {
        assertEquals("mars_society", sanitizeTerm("Mars Society"))
        assertEquals("at_t", sanitizeTerm("AT&T"))
    }

    @Test fun `collapses runs of non-alphanumerics into a single underscore`() {
        assertEquals("mars_society", sanitizeTerm("Mars  Society"))
        assertEquals("mars_society", sanitizeTerm("Mars-Society"))
        assertEquals("mars_society", sanitizeTerm("Mars--..--Society"))
    }

    @Test fun `trims leading and trailing underscores`() {
        assertEquals("mars", sanitizeTerm("  Mars  "))
        assertEquals("mars", sanitizeTerm("--Mars--"))
        assertEquals("mars", sanitizeTerm("..Mars.."))
    }

    @Test fun `pure punctuation collapses to empty`() {
        assertEquals("", sanitizeTerm("---"))
        assertEquals("", sanitizeTerm("   "))
        assertEquals("", sanitizeTerm("!@#"))
    }

    @Test fun `preserves digits`() {
        assertEquals("test123", sanitizeTerm("test123"))
        assertEquals("123_abc", sanitizeTerm("123 abc"))
    }

    @Test fun `collision detection — equivalent shapes produce same key`() {
        assertEquals(
            sanitizeTerm("Mars-Society"),
            sanitizeTerm("mars  society"),
        )
        assertEquals(
            sanitizeTerm("C++"),
            sanitizeTerm("c--"),  // both reduce to "c"
        )
    }
}
