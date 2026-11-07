package io.nisfeb.talon.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A notebook's compose screen is handed the last edit's title, image and
 * body. Kept after the edit, they opened a New post as a copy of the last
 * post edited, not dirty, so it could be published twice. Both shells pass
 * them only for an edit. The shells need the whole app to drive, so the
 * wiring is held by its source.
 */
class NotebookComposeGuardTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }
    private fun read(path: String) = File(root, path).readText()

    @Test
    fun `a new notebook post opens empty, whatever was edited before`() {
        for (shell in listOf(
            "composeApp/src/commonMain/kotlin/io/nisfeb/talon/compose/App.kt",
            "composeApp/src/androidMain/kotlin/io/nisfeb/talon/ui/TalonApp.kt",
        )) {
            val source = read(shell)
            for ((param, field, none) in listOf(
                Triple("initialTitle", "notebookEditTitle", "\"\""),
                Triple("initialImage", "notebookEditImage", "\"\""),
                Triple("initialBody", "notebookEditBody", "\"\""),
                Triple("originalSentMs", "notebookEditSentMs", "0L"),
            )) {
                assertTrue("$param = if (notebookEditPostId != null) $field else $none" in source, "$shell: $param is not an edit's only")
                assertFalse("$param = $field," in source, "$shell: $param is handed the last edit's whatever the open")
            }
        }
    }
}
