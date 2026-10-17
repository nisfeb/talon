package io.nisfeb.talon.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The Activity feed's notices on Android. Only App.kt recorded them and
 * read their taps: Android showed the list and its badge with nothing in
 * them, and a tapped row opened nothing. TalonApp has no test harness, so
 * its wiring is guarded by reading the source; where the notices land is
 * [io.nisfeb.talon.notify.noticeLanding]'s, tested in common.
 */
class AndroidNoticesGuardTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }
    private fun read(path: String) = File(root, path).readText()
    private val talonApp = read("composeApp/src/androidMain/kotlin/io/nisfeb/talon/ui/TalonApp.kt")
    private val app = read("composeApp/src/commonMain/kotlin/io/nisfeb/talon/compose/App.kt")

    @Test
    fun `Android records every notice the desktop shell records`() {
        for (kind in listOf("orrery-proposal", "dm-request", "group-invite")) {
            assertTrue("\"$kind\"" in app, "App.kt no longer records $kind; update this list")
            assertTrue("\"$kind\"" in talonApp, "TalonApp does not record $kind")
        }
        val armillary = Regex("""ArmillaryRepo\((.*?)\n    \}""", RegexOption.DOT_MATCHES_ALL).find(talonApp)?.groupValues?.get(1)
        assertTrue(armillary != null && "noticeRepo = noticeRepo" in armillary, "Armillary's credit is not recorded: $armillary")
    }

    @Test
    fun `a loop's result is recorded where Android posts it, with no screen up`() {
        val notifications = read("composeApp/src/androidMain/kotlin/io/nisfeb/talon/Notifications.kt")
        val showLoop = notifications.substringAfter("fun showLoop(").substringBefore("\n    }\n")
        assertTrue("\"loop-result\"" in showLoop && ".notices()" in showLoop, showLoop)
    }

    @Test
    fun `a tapped notice opens its place on Android`() {
        assertTrue("OpenNoticeRequests.requests.collect" in talonApp && "noticeLanding(tag)" in talonApp)
    }
}
