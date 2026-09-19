package io.nisfeb.talon.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import io.nisfeb.talon.ai.AiFeature
import io.nisfeb.talon.ai.AiSettings
import io.nisfeb.talon.ui.screens.AiSettingsSection
import io.nisfeb.talon.ui.theme.TalonTheme
import io.nisfeb.talon.urbit.FakeAiSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class AiSettingsSectionTest {
    @Test
    fun `the first change saves the profile the old settings made, and jev waits for openrouter`() = runComposeUiTest {
        val ai = FakeAiSettings().apply { applyRemote(AiSettings.Config(provider = AiSettings.Provider.Anthropic, apiKey = "sk-ant", model = "claude-opus-5")) }
        setContent {
            TalonTheme(darkTheme = false) {
                Column(Modifier.verticalScroll(rememberScrollState())) { AiSettingsSection(ai, orrery = null) }
            }
        }
        onNodeWithText("Providers").assertExists()
        onNodeWithText("claude-opus-5, Anthropic").assertExists()
        onNodeWithText("Default: claude-opus-5, Anthropic").assertExists()
        onNodeWithText("Needs an OpenRouter provider with a key", substring = true).assertExists()
        assertNull(ai.state.value.savedProfile, "looking saves nothing")

        // Rows in order: catch-up first, Jev last and greyed.
        val switches = onAllNodes(isToggleable())
        switches[switches.fetchSemanticsNodes().size - 1].assertIsNotEnabled()
        switches[0].performClick()
        waitForIdle()
        val saved = ai.state.value.savedProfile!!
        assertFalse(saved.isOn(AiFeature.CatchUp))
        assertFalse(ai.state.value.catchMeUpEnabled, "the old switch follows, for older installs")
        assertEquals("sk-ant", saved.provider(saved.defaultModel!!.provider)!!.apiKey)
        assertTrue(onAllNodesWithText("Anthropic", substring = true).fetchSemanticsNodes().isNotEmpty())
    }
}
