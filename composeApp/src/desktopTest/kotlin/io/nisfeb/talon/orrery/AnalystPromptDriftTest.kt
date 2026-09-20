package io.nisfeb.talon.orrery

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Brief.ANALYST is common/analyst-prompt.md from orrery-utils, word for
 * word, and must not drift from it. Where orrery-utils is checked out
 * beside this repo the two are compared; on CI, where it is not, this
 * says so and passes.
 */
class AnalystPromptDriftTest {
    @Test
    fun `the reply's analyst prompt is orrery-utils' own`() {
        val file = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "orrery-utils/common/analyst-prompt.md") }
            .firstOrNull { it.isFile }
        if (file == null) {
            // Nothing to compare against, so what the copy must carry is
            // checked instead: the whole test used to pass on CI having
            // done nothing at all, which is where it always runs.
            println("AnalystPromptDriftTest: no orrery-utils beside this repo; the clauses are checked instead")
            listOf(
                "never invent",
                "kind \"calendar\"",
                "kind \"message\"",
                "in the owner's own voice",
            ).forEach { assertTrue(it in Brief.ANALYST.lowercase(), "the analyst prompt no longer says \"$it\"") }
            assertTrue(Brief.ANALYST.length > 2_000, "the analyst prompt is ${Brief.ANALYST.length} characters; it is a page")
            return
        }
        assertEquals(file.readText().trimEnd(), Brief.ANALYST, "copy ${file.path} into Brief.ANALYST again")
    }
}
