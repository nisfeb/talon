package io.nisfeb.talon.ui.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import io.nisfeb.talon.notify.InMemoryRelaySettings
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The app-icon count is the owner's choice, off until turned on. The row
 *  shows only where isAppIconBadgeSupported (iOS), so it is drawn alone. */
@OptIn(ExperimentalTestApi::class)
class AppIconBadgeRowTest {
    @Test
    fun the_switch_turns_the_count_on_and_off() = runComposeUiTest {
        val settings = InMemoryRelaySettings()
        setContent { AppIconBadgeRow(settings) }
        onNodeWithText("Unread count on the app icon").assertExists()
        assertFalse(settings.badges.value)
        onNode(isToggleable()).performClick()
        waitForIdle()
        assertTrue(settings.badges.value)
        onNode(isToggleable()).performClick()
        waitForIdle()
        assertFalse(settings.badges.value)
    }
}
