package io.nisfeb.talon.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyDigestMentionMatcherTest {

    @Test fun `simple mention matches`() {
        assertTrue(DailyDigestMentionMatcher.containsMention(
            "hey ~mister-foo, check this", "mister-foo"))
    }

    @Test fun `no mention returns false`() {
        assertFalse(DailyDigestMentionMatcher.containsMention(
            "no mentions here", "mister-foo"))
    }

    @Test fun `prefix collision does NOT match`() {
        // ~mister-foo is a prefix of ~mister-foo-bar — must not match.
        assertFalse(DailyDigestMentionMatcher.containsMention(
            "ping ~mister-foo-bar please", "mister-foo"))
    }

    @Test fun `prefix collision with longer continuation does NOT match`() {
        assertFalse(DailyDigestMentionMatcher.containsMention(
            "saw ~mister-botter-dozzod-nisfeb today", "mister-botter"))
    }

    @Test fun `mention at start of haystack matches`() {
        assertTrue(DailyDigestMentionMatcher.containsMention(
            "~mister-foo: hello", "mister-foo"))
    }

    @Test fun `mention at end of haystack matches`() {
        assertTrue(DailyDigestMentionMatcher.containsMention(
            "tagging ~mister-foo", "mister-foo"))
    }

    @Test fun `multiple mentions matches once`() {
        assertTrue(DailyDigestMentionMatcher.containsMention(
            "~mister-foo and ~mister-foo again", "mister-foo"))
    }

    @Test fun `mention without leading tilde does NOT match`() {
        assertFalse(DailyDigestMentionMatcher.containsMention(
            "user mister-foo signed up", "mister-foo"))
    }

    @Test fun `case insensitive`() {
        assertTrue(DailyDigestMentionMatcher.containsMention(
            "ping ~MISTER-FOO", "mister-foo"))
    }

    @Test fun `empty patp returns false`() {
        assertFalse(DailyDigestMentionMatcher.containsMention(
            "anything ~", ""))
    }

    @Test fun `empty haystack returns false`() {
        assertFalse(DailyDigestMentionMatcher.containsMention("", "mister-foo"))
    }

    @Test fun `mention followed by punctuation matches`() {
        assertTrue(DailyDigestMentionMatcher.containsMention(
            "~mister-foo!", "mister-foo"))
        assertTrue(DailyDigestMentionMatcher.containsMention(
            "(~mister-foo)", "mister-foo"))
    }
}
