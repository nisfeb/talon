package io.nisfeb.talon.call

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * No video sink in Talon is made bare: each goes through [releasingSink],
 * which lets every frame go. A bare one that only noted the time leaked
 * a native copy of every frame (2026-10-09). Read from the sources, as
 * SectionFlagsGuardTest reads them.
 */
class WebRtcSinksGuardTest {
    @Test
    fun `every video sink is a releasing one`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
        val bare = Regex("""\bVideoTrackSink\s*(\{|\()""")
        val offenders = listOf("core/src", "composeApp/src", "bridge/src").map { File(root, it) }.filter { it.exists() }
            .flatMap { dir -> dir.walk().filter { it.isFile && it.extension == "kt" && "Test" !in it.path }.toList() }
            .filter { it.name != "ReleasingSink.kt" }
            .flatMap { f -> f.readLines().mapIndexedNotNull { i, line -> if (bare.containsMatchIn(line)) "${f.relativeTo(root)}:${i + 1}" else null } }
        assertEquals(emptyList(), offenders, "make these with releasingSink")
    }
}
