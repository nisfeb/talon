package io.nisfeb.talon.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The stuck-section bug, caught at the source.
 *
 * It shipped with nearly every new section: a `var fooOpen` flag made
 * the old way had to be added by hand to the reset list and the back
 * handlers, one of them got missed, and the section could not be left.
 * [Sections] makes that impossible, but only for flags made with it.
 * This fails the build for a section flag made any other way.
 *
 * The few booleans named like sections that are not sections are listed
 * here by name, each with why, so adding one is a decision rather than
 * an accident.
 */
class SectionFlagsGuardTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    /** Not full-screen sections: a sheet, an overlay or a composer inside one. */
    private val notSections = setOf(
        "calendarPageOpen", // a page inside the calendar section, not a section
        "notebookComposeOpen", // a composer inside a notebook, with its own back
        "galleryComposeOpen", // a composer inside a gallery, with its own back
        "showNewDmRequest", // a one-shot request, not a screen
        "localShipSetupOpen", // only while signed out, where there is no drawer to leave it by
        "meetingOpen", // an overlay over a live party line, closed with the line and by its own close
    )

    /**
     * Both shapes a section flag is named, in both shells: the guard
     * read only `.*Open` in one and only `show[A-Z].*` in the other, so
     * a section named the other way in either went unseen.
     */
    private val sectionName = Regex("(show[A-Z].*|.*Open)")

    private fun offenders(path: String, name: Regex = sectionName): List<String> =
        Regex("""var (\w+) by remember \{ mutableStateOf\(false\) \}""")
            .findAll(File(root, path).readText())
            .map { it.groupValues[1] }
            .filter { name.matches(it) && it !in notSections }
            .toList()

    private val shells = listOf(
        "composeApp/src/androidMain/kotlin/io/nisfeb/talon/ui/TalonApp.kt",
        "composeApp/src/commonMain/kotlin/io/nisfeb/talon/compose/App.kt",
    )

    /**
     * A section with a back handler of its own outranked the newest one:
     * Contacts, then Mail from a profile, and back closed the Contacts
     * hidden under Mail. The registry's one handler closes the newest.
     */
    @Test
    fun `no section has a back handler of its own`() {
        for (path in shells) {
            val src = File(root, path).readText()
            val made = Regex("""var (\w+) by (?:remember \{\s*sections\.(?:flag|page)|\w+Section\b)""")
                .findAll(src).map { it.groupValues[1] }.toSet()
            assertTrue(made.size > 10, "$path: the guard no longer finds the sections ($made)")
            val own = Regex("""BackHandler\(\s*enabled = (\w+)""").findAll(src)
                .map { it.groupValues[1] }.filter { it in made }.toList()
            assertTrue(own.isEmpty(), "$path: sections with a back handler of their own: $own. Let sections.closeLast() decide.")
        }
    }

    @Test
    fun `every android section is made by the registry`() {
        val bad = offenders("composeApp/src/androidMain/kotlin/io/nisfeb/talon/ui/TalonApp.kt")
        assertTrue(
            bad.isEmpty(),
            "Section flags made the old way: $bad. Declare a section with `sections.flag()` so the drawer's " +
                "reset and the back button cannot miss it (see Sections), or list it in notSections with why.",
        )
    }

    @Test
    fun `every desktop section is made by the registry`() {
        val bad = offenders("composeApp/src/commonMain/kotlin/io/nisfeb/talon/compose/App.kt")
        assertTrue(
            bad.isEmpty(),
            "Section flags made the old way: $bad. Declare a section with `sections.flag()` so the drawer's " +
                "reset and the back button cannot miss it (see Sections), or list it in notSections with why.",
        )
    }
}
