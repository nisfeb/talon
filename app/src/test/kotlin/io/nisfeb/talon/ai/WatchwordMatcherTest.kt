package io.nisfeb.talon.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchwordMatcherTest {

    @Test fun `matches simple word in middle of sentence`() {
        assertTrue(matchesWordBoundary("Hello Mars Society!", "Mars"))
    }

    @Test fun `does not match prefix collision`() {
        assertFalse(matchesWordBoundary("Marshmallow soft", "Mars"))
        assertFalse(matchesWordBoundary("Marshall plan", "Mars"))
        assertFalse(matchesWordBoundary("Marsupials are cute", "Mars"))
    }

    @Test fun `does not match suffix collision`() {
        assertFalse(matchesWordBoundary("preMars rocket", "Mars"))
    }

    @Test fun `case insensitive`() {
        assertTrue(matchesWordBoundary("hello MARS", "Mars"))
        assertTrue(matchesWordBoundary("Hello mars", "MARS"))
        assertTrue(matchesWordBoundary("MaRs", "mArS"))
    }

    @Test fun `multi-word phrase`() {
        assertTrue(matchesWordBoundary("the Mars Society announced", "Mars Society"))
    }

    @Test fun `phrase across newline does not match`() {
        assertFalse(matchesWordBoundary("Mars\nSociety announced", "Mars Society"))
    }

    @Test fun `punctuation around term`() {
        assertTrue(matchesWordBoundary("(Mars)", "Mars"))
        assertTrue(matchesWordBoundary("Mars!", "Mars"))
        assertTrue(matchesWordBoundary(",Mars,", "Mars"))
        assertTrue(matchesWordBoundary("[Mars]", "Mars"))
    }

    @Test fun `punctuation-heavy term matches when surrounded by non-letters`() {
        assertTrue(matchesWordBoundary("I love C++", "C++"))
        assertTrue(matchesWordBoundary("C++ is fun", "C++"))
        assertTrue(matchesWordBoundary("(C++)", "C++"))
    }

    @Test fun `punctuation-heavy term does not match when followed by letter or digit`() {
        assertFalse(matchesWordBoundary("C++14 standard", "C++"))
        assertFalse(matchesWordBoundary("C++abc", "C++"))
    }

    @Test fun `empty needle returns false`() {
        assertFalse(matchesWordBoundary("anything goes here", ""))
    }

    @Test fun `at start of haystack`() {
        assertTrue(matchesWordBoundary("Mars rocks", "Mars"))
    }

    @Test fun `at end of haystack`() {
        assertTrue(matchesWordBoundary("rocks Mars", "Mars"))
    }

    @Test fun `whole haystack is the term`() {
        assertTrue(matchesWordBoundary("Mars", "Mars"))
    }

    @Test fun `empty haystack`() {
        assertFalse(matchesWordBoundary("", "Mars"))
    }

    @Test fun `multiple occurrences only need first to match`() {
        assertTrue(matchesWordBoundary("Marsupials but also Mars", "Mars"))
    }

    @Test fun `unicode input does not crash`() {
        // No correctness guarantee for non-ASCII — just must not throw.
        // Spec §Testing/WatchwordMatcherTest checklist: "Unicode sanity
        // (does not crash): café, 日本 — no v1 commitment to correctness,
        // only stability."
        matchesWordBoundary("café au lait", "café")
        matchesWordBoundary("日本語テスト", "日本")
    }

    @Test fun `single-character term respects word boundaries`() {
        // 'a' at index 2 of "I am a person" sits between two spaces — match.
        assertTrue(matchesWordBoundary("I am a person", "a"))
        // 'a' inside "area" only ever has a letter on at least one side — no match.
        assertFalse(matchesWordBoundary("area is wide", "a"))
    }
}
