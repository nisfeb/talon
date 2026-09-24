package io.nisfeb.talon.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import io.nisfeb.talon.ui.screens.SettingsScreen
import io.nisfeb.talon.ui.theme.InMemoryThemePreference
import io.nisfeb.talon.ui.theme.TalonTheme
import io.nisfeb.talon.ui.theme.ThemePreference
import io.nisfeb.talon.urbit.FakeAiSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Settings, as the screen writes them: each control changes the stored
 * setting the rest of the app reads, and shows what is stored.
 */
@OptIn(ExperimentalTestApi::class)
class SettingsScreenTest {
    private val ui = InMemoryUiSettings()
    private val theme = InMemoryThemePreference()
    private val patpChanges = mutableListOf<Boolean>()

    private fun settings(block: ComposeUiTest.() -> Unit) = runComposeUiTest {
        setContent {
            TalonTheme(darkTheme = false) {
                SettingsScreen(
                    aiSettings = FakeAiSettings(), themePreference = theme, uiSettings = ui, onBack = {},
                    onAlwaysPatpChanged = { patpChanges += it },
                )
            }
        }
        waitForIdle()
        block()
    }

    private fun ComposeUiTest.tap(text: String) {
        val node = onAllNodesWithText(text)[0]
        runCatching { node.performScrollTo() }
        node.performClick()
        waitForIdle()
    }

    /** The switch drawn nearest [label]: the rows share one parent, so nearness is what ties them. */
    private fun ComposeUiTest.switchBeside(label: String): androidx.compose.ui.test.SemanticsNodeInteraction {
        runCatching { onAllNodesWithText(label)[0].performScrollTo() }
        val y = onAllNodesWithText(label)[0].fetchSemanticsNode().boundsInRoot.center.y
        val switches = onAllNodes(isToggleable())
        val nearest = switches.fetchSemanticsNodes().indices
            .minBy { kotlin.math.abs(switches[it].fetchSemanticsNode().boundsInRoot.center.y - y) }
        return switches[nearest]
    }

    @Test
    fun `the theme is stored as chosen`() = settings {
        tap("Dark")
        assertEquals(ThemePreference.Mode.Dark, theme.mode.value)
        tap("Light")
        assertEquals(ThemePreference.Mode.Light, theme.mode.value)
        tap("System")
        assertEquals(ThemePreference.Mode.System, theme.mode.value)
    }

    @Test
    fun `density and the home list's order are stored as chosen`() = settings {
        tap("Compact")
        assertEquals(Density.Compact, ui.density.value)
        tap("Cozy")
        assertEquals(Density.Cozy, ui.density.value)
        tap("Host order")
        assertEquals(GroupChannelOrder.HostOrder, ui.groupChannelOrder.value)
        tap("Saved order")
        assertEquals(FolderItemOrder.Manual, ui.folderItemOrder.value)
    }

    @Test
    fun `showing raw ship names is switched and reported`() = settings {
        switchBeside("Always show ~ship names").performClick()
        waitForIdle()
        assertEquals(listOf(true), patpChanges)
        switchBeside("Always show ~ship names").assertIsOn()
    }

    @Test
    fun `the Chats tab's switches store what they show`() = settings {
        tap("Chats")
        assertFalse(ui.hideComposerButtons.value)
        switchBeside("Hide composer buttons").performClick()
        waitForIdle()
        assertTrue(ui.hideComposerButtons.value)
        switchBeside("Hide composer buttons").assertIsOn()

        switchBeside("Power features").performClick()
        waitForIdle()
        assertTrue(ui.powerFeaturesEnabled.value)

        val before = ui.swipeQuotes.value
        tap(if (before) "Replies in its thread" else "Quotes it")
        assertEquals(!before, ui.swipeQuotes.value)
    }
}
