package io.nisfeb.talon.ui

import io.nisfeb.talon.orrery.LateMove
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Done and Snooze 1 h on orrery's nag on iOS: the relay names a category,
 * CallPush.swift registers it and hands a tapped button to Kotlin
 * (IosLateMoves). Swift cannot be built here, so the three are read to
 * agree: the category, the button ids and the moves' names.
 */
class IosLateButtonsGuardTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }
    private fun read(path: String) = File(root, path).readText()

    @Test
    fun `the relay's category the swift buttons and the kotlin moves agree`() {
        val relay = read("relay/src/main/kotlin/io/nisfeb/talon/relay/Apns.kt")
        val swift = read("iosApp/iosApp/CallPush.swift")
        val bridge = read("composeApp/src/iosMain/kotlin/io/nisfeb/talon/notify/IosVoipBridge.kt")
        assertTrue("OVERDUE_CATEGORY = \"orrery-overdue\"" in relay, "the relay's category")
        assertTrue("UNNotificationCategory(identifier: \"orrery-overdue\", actions: [done, snooze]" in swift, "registered under the same name")
        assertTrue("identifier: \"orrery-done\"" in swift && "identifier: \"orrery-snooze\"" in swift, "two buttons")
        assertTrue(".authenticationRequired" in swift, "Face ID before acting as the owner")
        for (m in LateMove.entries) assertTrue("\"${m.name}\"" in swift, "Swift sends the move ${m.name}")
        assertTrue("IosVoipBridge.shared.lateMove(tag: tag, move: move, ship: info[\"patp\"] as? String)" in swift, "a tap goes to Kotlin")
        assertTrue("fun lateMove(tag: String, move: String, ship: String?, finished: (String?) -> Unit)" in bridge, "the bridge Swift calls")
        assertTrue("removeDeliveredNotifications(withIdentifiers: [request.identifier])" in swift, "the nag taken back once done")
    }
}
