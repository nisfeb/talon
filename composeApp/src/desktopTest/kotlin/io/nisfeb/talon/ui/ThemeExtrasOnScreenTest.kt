package io.nisfeb.talon.ui

import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import io.nisfeb.talon.ui.theme.CustomTheme
import io.nisfeb.talon.ui.theme.LINK_BLUE
import io.nisfeb.talon.ui.theme.TalonTheme
import io.nisfeb.talon.urbit.Story
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/** A custom theme's link and selection colours, as a message is drawn under it. */
@OptIn(ExperimentalTestApi::class)
class ThemeExtrasOnScreenTest {
    private val ocean = CustomTheme("a1", "Ocean", dark = true, primary = "#38BDF8", secondary = "#A78BFA",
        tertiary = "#34D399", background = "#0B1120", surface = "#111827")
    private val parts = Story.parse(Json.parseToJsonElement("""[{"inline":["see ",{"link":{"href":"https://x.dev","content":"this page"}}]}]"""))

    /** The colour the message's link is drawn in, under [theme]. */
    private fun linkColourUnder(theme: CustomTheme?): Pair<Color?, TextSelectionColors?> {
        var selection: TextSelectionColors? = null
        var colour: Color? = null
        runComposeUiTest {
            setContent {
                TalonTheme(darkTheme = true, customTheme = theme) {
                    selection = LocalTextSelectionColors.current
                    StoryRenderer(parts, onLinkTap = {})
                }
            }
            val text = onNodeWithText("this page", substring = true).fetchSemanticsNode()
                .config.getOrNull(SemanticsProperties.Text)!!.single()
            val at = text.text.indexOf("this page")
            colour = text.spanStyles.filter { at in it.start until it.end }.map { it.item.color }.last { it != Color.Unspecified }
        }
        return colour to selection
    }

    @Test
    fun `links stay the standard blue unless the theme sets its own`() {
        assertEquals(LINK_BLUE, linkColourUnder(null).first)
        assertEquals(LINK_BLUE, linkColourUnder(ocean).first)
        assertEquals(LINK_BLUE, linkColourUnder(ocean.explicit()).first, "Auto is the blue")
        assertEquals(Color(0xFFFF7700), linkColourUnder(ocean.copy(link = "#FF7700")).first)
    }

    @Test
    fun `a theme's selection colour is the highlight on selected text`() {
        val (_, set) = linkColourUnder(ocean.copy(selection = "#00FF00"))
        assertEquals(Color(0xFF00FF00), set!!.handleColor)
        assertEquals(Color(0xFF00FF00).copy(alpha = 0.4f), set.backgroundColor)
        val (_, auto) = linkColourUnder(ocean)
        assertEquals(Color(0xFF38BDF8), auto!!.handleColor, "Material's, from primary")
    }
}
