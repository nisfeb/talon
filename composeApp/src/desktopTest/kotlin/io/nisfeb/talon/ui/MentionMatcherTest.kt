package io.nisfeb.talon.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MentionMatcherTest {

    @Test fun `simple mention matches`() {
        assertTrue(MentionMatcher.containsMention(
            "hey ~mister-foo, check this", "mister-foo"))
    }

    @Test fun `no mention returns false`() {
        assertFalse(MentionMatcher.containsMention(
            "no mentions here", "mister-foo"))
    }

    @Test fun `prefix collision does NOT match`() {
        // ~mister-foo is a prefix of ~mister-foo-bar — must not match.
        assertFalse(MentionMatcher.containsMention(
            "ping ~mister-foo-bar please", "mister-foo"))
    }

    @Test fun `prefix collision with longer continuation does NOT match`() {
        assertFalse(MentionMatcher.containsMention(
            "saw ~mister-botter-dozzod-nisfeb today", "mister-botter"))
    }

    @Test fun `mention at start of haystack matches`() {
        assertTrue(MentionMatcher.containsMention(
            "~mister-foo: hello", "mister-foo"))
    }

    @Test fun `mention at end of haystack matches`() {
        assertTrue(MentionMatcher.containsMention(
            "tagging ~mister-foo", "mister-foo"))
    }

    @Test fun `multiple mentions matches once`() {
        assertTrue(MentionMatcher.containsMention(
            "~mister-foo and ~mister-foo again", "mister-foo"))
    }

    @Test fun `mention without leading tilde does NOT match`() {
        assertFalse(MentionMatcher.containsMention(
            "user mister-foo signed up", "mister-foo"))
    }

    @Test fun `case insensitive`() {
        assertTrue(MentionMatcher.containsMention(
            "ping ~MISTER-FOO", "mister-foo"))
    }

    @Test fun `empty patp returns false`() {
        assertFalse(MentionMatcher.containsMention(
            "anything ~", ""))
    }

    @Test fun `empty haystack returns false`() {
        assertFalse(MentionMatcher.containsMention("", "mister-foo"))
    }

    @Test fun `mention followed by punctuation matches`() {
        assertTrue(MentionMatcher.containsMention(
            "~mister-foo!", "mister-foo"))
        assertTrue(MentionMatcher.containsMention(
            "(~mister-foo)", "mister-foo"))
    }
}
