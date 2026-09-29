package io.nisfeb.talon.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.SpanStyle
import io.nisfeb.talon.ui.inlineAnnotated
import io.nisfeb.talon.ui.linkifyStatus
import io.nisfeb.talon.ui.withLinkColor
import io.nisfeb.talon.urbit.Story
import io.nisfeb.talon.urbit.StoryPart
import io.nisfeb.talon.urbit.URL_TAG
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The six colours a theme may set beyond its five: each lands where it
 * says, what was derived from it follows it, an old theme draws as it
 * did, and a writer that does not know them cannot clear them.
 */
class CustomThemeExtrasTest {
    private val five = CustomTheme("a1", "Ocean", dark = true, primary = "#38BDF8", secondary = "#A78BFA",
        tertiary = "#34D399", background = "#0B1120", surface = "#111827")

    @Test
    fun `a theme of five draws as it did, whether its extras are absent or Auto`() {
        // ColorScheme has no equals of its own: every role, by its text.
        assertEquals(customScheme(five).toString(), customScheme(five.explicit()).toString())
        assertNull(five.linkColor())
        assertNull(five.selectionColor())
    }

    @Test
    fun `each extra lands where it says`() {
        val s = customScheme(five.copy(text = "#EEEEEE", muted = "#999999", raised = "#223344", error = "#FF5555"))
        assertEquals(Color(0xFFEEEEEE), s.onSurface)
        assertEquals(Color(0xFFEEEEEE), s.onBackground)
        assertEquals(Color(0xFF999999), s.onSurfaceVariant)
        assertEquals(Color(0xFF223344), s.surfaceVariant)
        assertEquals(Color(0xFF223344), s.surfaceContainerHighest)
        assertEquals(Color(0xFFFF5555), s.error)
        assertFalse(s.onError == s.error, "an on-colour for the error")
        assertEquals(Color(0xFF38BDF8), s.primary, "the five are untouched")
        assertEquals(Color(0xFFFF0000), five.copy(link = "#FF0000").linkColor())
        assertEquals(Color(0xFF00FF00), five.copy(selection = "#00FF00").selectionColor())
    }

    @Test
    fun `what was derived from the text follows it`() {
        val s = customScheme(five.copy(text = "#EEEEEE"))
        val surface = Color(0xFF111827)
        assertEquals(lerp(Color(0xFFEEEEEE), surface, 0.35f), s.onSurfaceVariant)
        assertEquals(lerp(surface, Color(0xFFEEEEEE), 0.4f), s.outline)
    }

    @Test
    fun `Auto, nothing, and nonsense are all derived, and nonsense is not a good theme`() {
        for (v in listOf("", null, "blue")) assertNull(five.copy(link = v).linkColor(), "$v")
        assertTrue(five.copy(link = "").valid)
        assertFalse(five.copy(link = "blue").valid)
    }

    @Test
    fun `Talon writes all six, Auto as empty, and a theme of five writes none`() {
        val written = Json.parseToJsonElement(ThemeSettings(listOf(five.explicit())).toJson()).jsonObject["themes"]!!.jsonArray[0].jsonObject
        for (k in listOf("text", "muted", "raised", "error", "selection", "link")) assertEquals("\"\"", written[k].toString(), k)
        val old = Json.parseToJsonElement(ThemeSettings(listOf(five)).toJson()).jsonObject["themes"]!!.jsonArray[0].jsonObject
        assertTrue(old.keys.none { it in setOf("text", "link") }, "$old")
        assertEquals("", CustomTheme.blank(dark = true, id = "x").link, "a new theme is written whole")
    }

    // Another device's Talon from before the extras, or a tool that
    // writes the five, drops the keys it does not know.
    @Test
    fun `a theme arriving without its extras keeps this device's, and empty clears one`() {
        val mine = ThemeSettings(listOf(five.copy(link = "#FF0000", text = "#EEEEEE")), activeId = "a1")
        val stripped = ThemeSettings.fromJson("""{"themes":[{"id":"a1","name":"Ocean","dark":true,"primary":"#123456",
            "secondary":"#A78BFA","tertiary":"#34D399","background":"#0B1120","surface":"#111827"}],"activeId":"a1"}""")!!
        val kept = stripped.keepingLocalExtras(mine).active!!
        assertEquals("#123456" to "#FF0000", kept.primary to kept.link, "the change arrives, the link stays")
        assertEquals("#EEEEEE", kept.text)
        val cleared = stripped.copy(themes = stripped.themes.map { it.copy(link = "") }).keepingLocalExtras(mine).active!!
        assertEquals("", cleared.link, "empty is a choice to clear it")
        val other = ThemeSettings(listOf(five.copy(id = "b2"))).keepingLocalExtras(mine)
        assertNull(other.themes.single().link, "a theme this device lacks has nothing to keep")
    }

    @Test
    fun `links and mentions in messages, statuses and markdown take the theme's link colour, or stay blue`() {
        val red = Color(0xFFFF0000)
        fun colourAt(a: androidx.compose.ui.text.AnnotatedString, i: Int) =
            a.spanStyles.filter { i in it.start until it.end }.mapNotNull { it.item.color.takeIf { c -> c != Color.Unspecified } }.lastOrNull()
        val parts = Story.parse(Json.parseToJsonElement("""[{"inline":["hi ",{"ship":"~zod"}," see ",{"link":{"href":"https://x.dev","content":"x"}}]}]"""))
        val text = (parts.single() as StoryPart.Text).text
        val link = text.getStringAnnotations(URL_TAG, 0, text.length).single().start
        val mention = text.indexOf("~zod").takeIf { it >= 0 } ?: 3
        assertEquals(LINK_BLUE, colourAt(text, link))
        assertEquals(red, colourAt(text.withLinkColor(red), link))
        assertEquals(red, colourAt(text.withLinkColor(red), mention))
        assertEquals(text, text.withLinkColor(null))

        fun linkStyle(a: androidx.compose.ui.text.AnnotatedString): SpanStyle? =
            (a.getLinkAnnotations(0, a.length).single().item as androidx.compose.ui.text.LinkAnnotation.Url).styles?.style
        assertEquals(LINK_BLUE, linkStyle(linkifyStatus("see https://x.dev"))?.color)
        assertEquals(red, linkStyle(linkifyStatus("see https://x.dev", red))?.color)
        assertEquals(red, linkStyle(inlineAnnotated("[x](https://x.dev)", Color.Unspecified, red))?.color)
    }
}
