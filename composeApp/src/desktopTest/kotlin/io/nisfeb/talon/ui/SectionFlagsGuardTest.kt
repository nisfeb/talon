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
        "shareLoginQrOpen", // a dialog over whatever is on screen
        "notebookComposeOpen", // a composer inside a notebook, with its own back
        "galleryComposeOpen", // a composer inside a gallery, with its own back
        "showNewDmRequest", // a one-shot request, not a screen
    )

    private fun offenders(path: String, name: Regex): List<String> =
        Regex("""var (\w+) by remember \{ mutableStateOf\(false\) \}""")
            .findAll(File(root, path).readText())
            .map { it.groupValues[1] }
            .filter { name.matches(it) && it !in notSections }
            .toList()

    @Test
    fun `every android section is made by the registry`() {
        val bad = offenders("composeApp/src/androidMain/kotlin/io/nisfeb/talon/ui/TalonApp.kt", Regex(".*Open"))
        assertTrue(
            bad.isEmpty(),
            "Section flags made the old way: $bad. Declare a section with `sections.flag()` so the drawer's " +
                "reset and the back button cannot miss it (see Sections), or list it in notSections with why.",
        )
    }

    @Test
    fun `every desktop section is made by the registry`() {
        val bad = offenders("composeApp/src/commonMain/kotlin/io/nisfeb/talon/compose/App.kt", Regex("show[A-Z].*"))
        assertTrue(
            bad.isEmpty(),
            "Section flags made the old way: $bad. Declare a section with `sections.flag()` so the drawer's " +
                "reset and the back button cannot miss it (see Sections), or list it in notSections with why.",
        )
    }
}
