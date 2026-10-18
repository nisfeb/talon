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
    private val application = read("composeApp/src/androidMain/kotlin/io/nisfeb/talon/TalonApplication.kt")

    @Test
    fun `Android records every notice the desktop shell records`() {
        for (kind in listOf("orrery-proposal", "dm-request", "group-invite")) {
            assertTrue("\"$kind\"" in app, "App.kt no longer records $kind; update this list")
            // The DM and invite listeners live on the ship's repo, for the process's life.
            assertTrue("\"$kind\"" in talonApp || "\"$kind\"" in application, "Android does not record $kind")
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

    // A3-1, A5-1: set by TalonApp's screen, the listeners went with it, and
    // a stream kept by the sync service (a boot start, Talon swiped from
    // recents) notified nothing. They are the repo's, set where the repo is
    // built, and decided by the common, tested liveMessageAlerts (A5-3, A5-11).
    @Test
    fun `the chat notifications are set on each ship's repo, not by a screen`() {
        val build = application.substringAfter("private fun buildShipScoped(").substringBefore("\n    }\n")
        assertTrue("listenForAlerts(ship, r, db)" in build, build)
        val listen = application.substringAfter("private fun listenForAlerts(").substringBefore("\n    }\n")
        for (set in listOf("messageListener = ", "dmInviteListener = ", "groupInviteListener = ")) {
            assertTrue(set in listen, "listenForAlerts does not set $set")
            assertTrue(set !in talonApp, "TalonApp sets $set again, on the screen's lifetime")
        }
        assertTrue("liveMessageAlerts(" in listen && "ShownConversation.keys" in listen, listen)
        assertTrue("forShip = ship" in listen && "\"invite:${'$'}{invite.flag}\"" in listen, listen)
    }

    // A5-8: the invite's notification opened the group's page, which can
    // only ask to join, and carried no ship.
    @Test
    fun `a tapped invite notification opens Invites`() {
        assertTrue("initialOpenWhom.startsWith(\"invite:\")" in talonApp && "invitesOpen = true" in talonApp)
        assertTrue("it.startsWith(\"invite:\")" in talonApp, "an invite is kept out of openWhom")
    }

    // A5-10, and a hint for a ship no longer signed in.
    @Test
    fun `a relay hint is marked as one and dropped for a ship not signed in here`() {
        val receiver = read("composeApp/src/androidMain/kotlin/io/nisfeb/talon/notify/TalonMessagingReceiver.kt")
        assertTrue("hint = true" in receiver, "the hint is not marked")
        assertTrue("patp !in app.allShipsFlow.value" in receiver)
        assertTrue("updateEndpoint(" in receiver, "a new endpoint is not sent to the relay")
        val notifications = read("composeApp/src/androidMain/kotlin/io/nisfeb/talon/Notifications.kt")
        val show = notifications.substringAfter("fun showMessage(").substringBefore("\n    }\n")
        assertTrue("chatAlert(" in show && "ChatAlert.Skip) return" in show && "setOnlyAlertOnce(alert == io.nisfeb.talon.notify.ChatAlert.Quiet)" in show, show)
    }

    @Test
    fun `the catch-up worker starts the stream and the last sign-out stops the service`() {
        val worker = read("composeApp/src/androidMain/kotlin/io/nisfeb/talon/CatchUpWorker.kt")
        assertTrue("app.repo.start(app.session)" in worker)
        assertTrue("loggedInShip ?: run { TalonSyncService.stop(context); return@LaunchedEffect }" in talonApp)
    }
}
