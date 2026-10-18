package io.nisfeb.talon.ui

import io.nisfeb.talon.call.RingIntents
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pass 2's Android services and lifecycle fixes (B3). The decisions are
 * common and tested there (NativeCallsTest's RingIntents, KeepAliveTest);
 * the wiring around them needs a phone, so it is guarded by reading the
 * source, as AndroidNoticesGuardTest does.
 */
class AndroidLifecycleGuardTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }
    private fun read(path: String) = File(root, path).readText()
    private val android = "composeApp/src/androidMain/kotlin/io/nisfeb/talon"
    private val manifest = read("composeApp/src/androidMain/AndroidManifest.xml")
    private val notifications = read("$android/Notifications.kt")
    private val activity = read("$android/MainActivity.kt")
    private val application = read("$android/TalonApplication.kt")
    private val talonApp = read("$android/ui/TalonApp.kt")

    // B3-1
    @Test
    fun `every notification opens Talon through the alias no other app can start`() {
        val alias = Regex("""<activity-alias[^>]*android:name="${Regex.escape(RingIntents.TAP_ALIAS)}"[^>]*>""").find(manifest)?.value
            ?: error("the notification alias is not declared")
        assertTrue("""android:exported="false"""" in alias, alias)
        assertTrue("""android:targetActivity="io.nisfeb.talon.MainActivity"""" in alias, alias)
        assertTrue(manifest.indexOf(alias) > manifest.indexOf("""android:name="io.nisfeb.talon.MainActivity""""), "an alias comes after its target")
        assertFalse("MainActivity::class" in notifications, "a notification opens the exported MainActivity")
        assertEquals(7, Regex("""tapIntent\(context\)""").findAll(notifications).count(), "a notification tap was added or lost")
        val redial = read("$android/call/TalonConnectionService.kt")
        assertTrue("Notifications.tapIntent(this)" in redial && "MainActivity::class" !in redial, "the telecom redial's call back no longer counts")
    }

    @Test
    fun `MainActivity acts on call extras only through the alias`() {
        val consume = activity.substringAfter("private fun consumeIntent(").substringBefore("\n    }\n")
        assertTrue("val trusted = callsTrusted(intent)" in consume, consume)
        assertTrue("if (trusted) {\n                pendingAnswerFrom.value = it" in consume, "an answer from any app")
        assertTrue("if (trusted) pendingCallBack.value = it" in consume, "a call back from any app")
        // Untrusted extras are still stripped, so the task's intent does not keep them.
        assertTrue("intent.removeExtra(Notifications.EXTRA_ANSWER_FROM)" in consume && "intent.removeExtra(Notifications.EXTRA_CALL_BACK)" in consume)
        val lock = activity.substringAfter("private fun showOverLockScreenIfRinging(").substringBefore("\n    }\n")
        assertTrue("!callsTrusted(intent)" in lock, "any app could show Talon over the lock screen")
        assertTrue("RingIntents.trusted(intent.component?.className)" in activity)
        // The Telecom call log's own call back is untouched.
        assertTrue("android.telecom.TelecomManager.ACTION_CALL_BACK" in consume)
    }

    // B3-3
    @Test
    fun `a ring heard over the stream with Talon behind posts its notification once`() {
        val effect = talonApp.substringAfter("LaunchedEffect(callState) {").substringBefore("\n        }\n")
        assertTrue("currentStateFlow.first { !it.isAtLeast(Lifecycle.State.STARTED) }" in effect, effect)
        assertTrue("Notifications.shownCall != id" in effect, "the push's notification is posted again")
        assertTrue("Notifications.showIncomingCall(" in effect && "forShip = loggedInShip" in effect, effect)
    }

    // B3-5
    @Test
    fun `a ring for another ship carries it and goes there first`() {
        val show = notifications.substringAfter("fun showIncomingCall(").substringBefore("\n    }\n")
        assertTrue("if (forShip != null) putExtra(EXTRA_FOR_SHIP, forShip)" in show, show)
        assertTrue("showIncomingCall(context, from, eventId, name, forShip = patp)" in read("$android/notify/TalonMessagingReceiver.kt"))
        assertTrue("RingIntents.shipToSwitch(it, app.activeShipFlow.value, app.allShipsFlow.value)" in activity)
        assertTrue("LaunchedEffect(callController, initialAnswerFrom, initialAnswerCallId)" in talonApp, "the answer waits on the old ship's controller")
    }

    // B3-6 and B3-15
    @Test
    fun `the service and the catch-up worker follow the stream alone`() {
        val dispose = talonApp.substringAfter("// Mail polls only while this screen lives").substringBefore("\n    }\n")
        assertTrue("onDispose { if (!TalonSyncService.streamNeeded(app)) TalonSyncService.stop(context) }" in dispose, dispose)
        val stream = read("$android/TalonSyncService.kt").substringAfter("fun streamNeeded(").substringBefore("\n        }\n")
        assertTrue("mailNeedsProcess" !in stream, "mail counts after the screen is gone")
        val worker = read("$android/CatchUpWorker.kt").substringAfter("fun follow(").substringBefore("\n        }\n")
        assertTrue("if (TalonSyncService.streamNeeded(app)) schedule(app) else cancel(app)" in worker, worker)
        assertTrue("CatchUpWorker.follow(this)" in application && "CatchUpWorker.schedule(this)" !in application)
        assertTrue("CatchUpWorker.follow(app)" in talonApp && "CatchUpWorker.cancel(context)" in talonApp)
    }

    // B3-7
    @Test
    fun `Orrery off stops the leave poll`() {
        val stop = read("$android/orrery/LeaveAlarm.kt").substringAfter("fun stop(ctx: Context) {").substringBefore("\n    }\n")
        assertTrue("cancelUniqueWork(WORK_POLL)" in stop && "clear(ctx)" in stop, stop)
        assertTrue("else runCatching { io.nisfeb.talon.orrery.LeaveAlarm.stop(this) }" in application)
        val gate = talonApp.substringAfter("LaunchedEffect(orreryGate == true) {").substringBefore("\n    }\n")
        assertTrue("LeaveAlarm.schedule(app)" in gate && "LeaveAlarm.stop(app)" in gate, gate)
    }

    // B3-8
    @Test
    fun `an update starts the stream as a reboot does`() {
        val boot = manifest.substringAfter("""android:name="io.nisfeb.talon.BootReceiver"""").substringBefore("</receiver>")
        assertTrue("android.intent.action.MY_PACKAGE_REPLACED" in boot, boot)
        val receiver = read("$android/BootReceiver.kt")
        assertTrue("Intent.ACTION_MY_PACKAGE_REPLACED," in receiver)
        assertTrue("if (intent.action != Intent.ACTION_TIMEZONE_CHANGED) TalonSyncService.startIfNeeded(app)" in receiver)
    }

    // B3-9
    @Test
    fun `retired channels are deleted and nothing posts to them`() {
        for (id in listOf("watchwords", "calls", "daily-digest")) {
            assertTrue("mgr.deleteNotificationChannel(\"$id\")" in notifications, id)
            val uses = File(root, "composeApp/src/androidMain").walk().filter { it.extension == "kt" }
                .sumOf { f -> Regex("\"${Regex.escape(id)}\"").findAll(f.readText()).count() }
            assertEquals(1, uses, "\"$id\" is used again in androidMain")
        }
    }

    // B3-11, B3-13, B3-14
    @Test
    fun `ship pushes follow the active ship and the app scope survives a throw`() {
        for (fn in listOf("fun switchShip(", "fun signOutActive(", "fun forgetShip(", "fun onShipLoggedIn(")) {
            val body = application.substringAfter(fn).substringBefore("\n    }\n")
            assertTrue("ensureShipPushes()" in body, "$fn leaves the new ship without its pushes")
        }
        assertTrue("SupervisorJob() + Dispatchers.IO + io.nisfeb.talon.util.backgroundExceptionHandler" in application)
        val shortcuts = read("$android/ShortcutsPublisher.kt")
        assertTrue("putExtra(Notifications.EXTRA_FOR_SHIP, ship)" in shortcuts)
        assertTrue("ShortcutsPublisher(this, db, ship)" in application)
    }
}
