package io.nisfeb.talon.orrery

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Brief.SYSTEM is common/brief-prompt.md from orrery-utils, word for
 * word, and must not drift from it. Where orrery-utils is checked out
 * beside this repo the two are compared; on CI, where it is not, what
 * the prompt must say is checked instead.
 */
class BriefPromptDriftTest {
    @Test
    fun `the brief's prompt is orrery-utils' own`() {
        val file = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "orrery-utils/common/brief-prompt.md") }
            .firstOrNull { it.isFile }
        if (file == null) {
            println("BriefPromptDriftTest: no orrery-utils beside this repo; the clauses are checked instead")
            listOf(
                "the brief is about today",
                "ahead in the coming week as titles and starts only",
                "what yesterday's brief said",
                "one to three short lines",
                "nothing to add",
            ).forEach { assertTrue(it in Brief.SYSTEM.lowercase(), "the brief prompt no longer says \"$it\"") }
            return
        }
        assertEquals(file.readText().trimEnd(), Brief.SYSTEM, "copy ${file.path} into Brief.SYSTEM again")
    }
}
