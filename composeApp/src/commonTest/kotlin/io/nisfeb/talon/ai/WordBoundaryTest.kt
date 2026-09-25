package io.nisfeb.talon.ai

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WordBoundaryTest {
    @Test
    fun wholeWordsMatchWhateverTheCaseOrPunctuation() {
        assertTrue(matchesWordBoundary("Off to (MARS)!", "mars"))
        assertTrue(matchesWordBoundary("I love C++ a lot", "C++"))
        assertTrue(matchesWordBoundary("the Mars Society", "Mars Society"))
    }

    @Test
    fun partsOfLongerWordsDoNot() {
        assertFalse(matchesWordBoundary("marshmallow", "mars"))
        assertFalse(matchesWordBoundary("mars2", "mars"))
        assertTrue(matchesWordBoundary("marsh and mars", "mars"), "a later whole word still counts")
        assertFalse(matchesWordBoundary("Mars\nSociety", "Mars Society"))
        assertFalse(matchesWordBoundary("anything", ""))
    }
}
