package io.nisfeb.talon.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runComposeUiTest
import io.nisfeb.talon.ui.screens.SkyClockDial
import io.nisfeb.talon.ui.theme.TalonTheme
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The home dial drawn through the day and the weather: a wet afternoon
 * with its high and low and hour-by-hour cloud, a moonlit night, and a
 * polar summer; each says in words what it draws.
 */
@OptIn(ExperimentalTestApi::class)
class SkyClockDialTest {
    private fun dial(sky: SkyClock.Sky, fahrenheit: Boolean = false, block: ComposeUiTest.(String) -> Unit) = runComposeUiTest {
        setContent { TalonTheme(darkTheme = false) { SkyClockDial(sky, fahrenheit = fahrenheit, twentyFourHour = true) } }
        waitForIdle()
        val spoken = onAllNodes(SemanticsMatcher("described") { it.config.getOrNull(SemanticsProperties.ContentDescription) != null })
            .fetchSemanticsNodes().mapNotNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString() }
            .firstOrNull { "sun" in it } ?: ""
        block(spoken)
    }

    private fun ComposeUiTest.shows(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `a wet afternoon shows its high and low, and says so`() = dial(
        SkyClock.Sky(
            minuteOfDay = 14 * 60, currentC = 15.0, highC = 18.0, highAtMinute = 15 * 60, lowC = 7.0, lowAtMinute = 5 * 60,
            cloudCover = 0.8f, condition = SkyClock.Weather.RAIN,
            hourlyCloud = List(24) { if (it in 12..18) 0.9f else 0.2f },
            hourlyCondition = List(24) { if (it in 12..18) SkyClock.Weather.RAIN else SkyClock.Weather.CLEAR },
        ),
    ) { spoken ->
        assertTrue(shows("H 18°") && shows("L 7°"), "the day's extremes on the dial")
        assertTrue(spoken.startsWith("The sun is up. It sets at 18:00") && "High 18° at 15:00" in spoken && "Low 7° at 05:00" in spoken, spoken)
    }

    @Test
    fun `a night under a full moon says the sun is down`() = dial(
        SkyClock.Sky(minuteOfDay = 23 * 60, moonElongationDeg = 180.0, cloudCover = 0.1f, condition = SkyClock.Weather.CLEAR),
    ) { spoken ->
        assertTrue(spoken.startsWith("The sun is down. It rises at 06:00"), spoken)
    }

    @Test
    fun `a polar summer has no sunset to give`() = dial(
        SkyClock.Sky(minuteOfDay = 12 * 60, polar = true, polarDay = true, condition = SkyClock.Weather.SNOW),
    ) { spoken ->
        assertTrue(spoken.startsWith("The sun does not set today") && "daylight" !in spoken, spoken)
    }

    @Test
    fun `temperatures read in Fahrenheit where asked`() = dial(
        SkyClock.Sky(minuteOfDay = 10 * 60, currentC = 15.0, highC = 20.0, lowC = 10.0),
        fahrenheit = true,
    ) {
        assertTrue(shows("59°") && shows("H 68°") && shows("L 50°"))
    }
}
