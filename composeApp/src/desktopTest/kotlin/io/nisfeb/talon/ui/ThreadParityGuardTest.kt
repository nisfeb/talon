package io.nisfeb.talon.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A message in a thread does what a message in the chat does.
 *
 * Features added to the chat's message row kept missing from the
 * thread's, which is a separate composable: the names on a reaction's
 * hover were missing because the thread had its own copy of the chip
 * (reported on 1.8.39). This fails the build when the chat's row calls
 * a composable the thread's row does not. Fix it by calling the same
 * composable from the thread, never by copying it. A piece that truly
 * belongs only to the chat is listed below with why, so leaving the
 * thread without it is a decision rather than an accident.
 */
class ThreadParityGuardTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }
    private val screens = "composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens"

    private val chatOnly = mapOf(
        "MessageRow" to "the chat row's own name",
        "ThreadIndicator" to "a reply in a thread has no thread of its own",
    )

    /** Composables called in the body of [fn], up to the next top-level declaration. */
    private fun calls(file: String, fn: String): Set<String> {
        val lines = File(root, "$screens/$file").readLines()
        val start = lines.indexOfFirst { "fun $fn(" in it }
        check(start >= 0) { "$fn not found in $file: update this guard" }
        val top = Regex("^(@Composable|@OptIn|private fun|internal fun|fun )")
        val body = lines.drop(start + 1).takeWhile { !top.containsMatchIn(it) }.joinToString("\n")
        return Regex("""\b([A-Z][A-Za-z0-9]+)\(""").findAll(body).map { it.groupValues[1] }.toSet()
    }

    @Test
    fun `the thread's message row calls everything the chat's does`() {
        val missing = calls("DmChatScreen.kt", "MessageRow") - calls("ThreadList.kt", "ThreadMessage") - chatOnly.keys
        assertTrue(
            missing.isEmpty(),
            "the chat's message row calls $missing and the thread's (ThreadMessage in ThreadList.kt) does not. " +
                "Call the same composable from the thread, or list it in chatOnly with why.",
        )
    }
}
