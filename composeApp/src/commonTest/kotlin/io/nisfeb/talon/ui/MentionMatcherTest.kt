package io.nisfeb.talon.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MentionMatcherTest {

    @Test fun `prefix collision with longer continuation does NOT match`() {
        assertFalse(MentionMatcher.containsMention(
            "saw ~mister-botter-dozzod-nisfeb today", "mister-botter"))
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

    @Test fun `mention followed by punctuation matches`() {
        assertTrue(MentionMatcher.containsMention(
            "~mister-foo!", "mister-foo"))
        assertTrue(MentionMatcher.containsMention(
            "(~mister-foo)", "mister-foo"))
    }

    @Test fun `letter glued to the tilde does NOT match`() {
        // x~zod is one token, not a mention of ~zod.
        assertFalse(MentionMatcher.containsMention(
            "see x~mister-foo for details", "mister-foo"))
        assertFalse(MentionMatcher.containsMention(
            "dash-glued-~mister-foo either", "mister-foo"))
        // ...but a later free-standing mention still counts.
        assertTrue(MentionMatcher.containsMention(
            "x~mister-foo then ~mister-foo", "mister-foo"))
    }

    // Read from the story JSON: the words as shown said a nickname
    // where the post named the ship, and the mention was missed.
    @Test
    fun `a mention inline counts however it is shown, a ship in a cite's path does not`() {
        assertTrue(MentionMatcher.mentionsIn("""[{"inline":["hi ",{"ship":"~zod"}]}]""", "zod"))
        assertTrue(MentionMatcher.mentionsIn("""[{"inline":["ping ~zod please"]}]""", "zod"))
        assertFalse(MentionMatcher.mentionsIn("""[{"block":{"cite":{"chan":{"nest":"chat/~zod/general","where":"/msg/~zod/170.141.184"}}}}]""", "zod"))
        assertFalse(MentionMatcher.mentionsIn("""[{"inline":[{"ship":"~zodbus"}]}]""", "zod"))
    }
}

