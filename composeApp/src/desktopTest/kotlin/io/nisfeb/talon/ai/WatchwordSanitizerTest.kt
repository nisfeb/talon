package io.nisfeb.talon.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class WatchwordSanitizerTest {

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

    @Test fun `preserves digits`() {
        assertEquals("test123", sanitizeTerm("test123"))
        assertEquals("123_abc", sanitizeTerm("123 abc"))
    }

}
