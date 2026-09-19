package io.nisfeb.talon.orrery

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

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
            println("AnalystPromptDriftTest: no orrery-utils beside this repo; skipped")
            return
        }
        assertEquals(file.readText().trimEnd(), Brief.ANALYST, "copy ${file.path} into Brief.ANALYST again")
    }
}
