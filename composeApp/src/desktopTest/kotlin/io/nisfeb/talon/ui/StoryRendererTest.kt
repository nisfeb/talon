package io.nisfeb.talon.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.data.ReactionEntity
import io.nisfeb.talon.ui.theme.TalonTheme
import io.nisfeb.talon.urbit.Story
import io.nisfeb.talon.urbit.StoryPart
import io.nisfeb.talon.urbit.chatTextToStory
import kotlinx.serialization.json.Json
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Messages as they are drawn: the cards /poll, /cal, /tz and /loc post,
 * from the very bodies those commands send, and the taps on links,
 * mentions, pictures, media and quoted posts.
 */
@OptIn(ExperimentalTestApi::class)
class StoryRendererTest {
    private val did: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private fun parts(text: String) = Story.parse(chatTextToStory(text))
    private fun parts(json: String, raw: Boolean) = Story.parse(Json.parseToJsonElement(json))

    private fun render(
        parts: List<StoryPart>,
        reactions: List<ReactionEntity> = emptyList(),
        cite: CiteResolver? = null,
        block: ComposeUiTest.() -> Unit,
    ) = runComposeUiTest {
        setContent {
            CompositionLocalProvider(
                LocalUriHandler provides object : UriHandler { override fun openUri(uri: String) { did += "open $uri" } },
                LocalCalendarLauncher provides CalendarLauncher { s, e, t -> did += "calendar $s $e $t" },
                LocalCiteResolver provides cite,
                LocalCitationOpen provides { c -> did += "cite ${c.openTarget}" },
                LocalPlaceName provides { nest -> if (nest == "chat/~bus/general") "Garden · General" else null },
                LocalDisplayName provides { ship -> if (ship == "~bus") "Bus" else ship },
            ) {
                TalonTheme(darkTheme = false) {
                    StoryRenderer(
                        parts,
                        onMentionTap = { did += "mention $it" },
                        onLinkTap = { did += "link $it" },
                        onImageTap = { did += "image $it" },
                        reactions = reactions,
                        ourPatp = "~zod",
                        onPollVote = { did += "vote $it" },
                        onMessageTap = { did += "message" },
                    )
                }
            }
        }
        block()
    }

    private fun ComposeUiTest.shows(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    /** Click the glyphs of [word] inside the text that holds it. */
    private fun ComposeUiTest.clickWord(word: String) {
        val node = onNodeWithText(word, substring = true)
        val layout = mutableListOf<TextLayoutResult>().also { node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!(it) }.single()
        val at = layout.getBoundingBox(layout.layoutInput.text.indexOf(word)).center
        node.performTouchInput { click(at) }
    }

    // ─── cards ────────────────────────────────────────────────────

    @Test
    fun `a poll is a card of its options, counts the votes, and a tap votes`() {
        val poll = Poll("Lunch?", listOf("pizza", "tacos"))
        val body = "📊 Lunch?\n${VOTE_EMOJIS[0]} pizza\n${VOTE_EMOJIS[1]} tacos\n${encodePollTag(poll)}"
        val p = parts(body)
        assertEquals(listOf<StoryPart>(StoryPart.PollWidget("Lunch?", listOf("pizza", "tacos"))), p)
        val votes = listOf(ReactionEntity("~bus", "1", "~nec", VOTE_EMOJIS[1]), ReactionEntity("~bus", "1", "~zod", VOTE_EMOJIS[1]))
        render(p, reactions = votes) {
            assertTrue(shows("📊 Lunch?") && shows("2 votes · tap to change."))
            assertTrue(!shows("[poll|"), "the tag is not shown")
            onNodeWithText("pizza").performClick()
            assertEquals(listOf("vote ${VOTE_EMOJIS[0]}"), did)
        }
    }

    @Test
    fun `an event card adds itself to a calendar app`() {
        val start = 1_790_000_000_000L
        val end = start + 30 * 60_000
        val p = parts("📅 Standup\n${formatCalSummary(start, end)}\n${encodeCalTag(start, end, "Standup")}")
        assertEquals(listOf<StoryPart>(StoryPart.CalWidget(start, end, "Standup")), p)
        render(p) {
            assertTrue(shows("📅 Standup"))
            onNodeWithText("Add").performClick()
            assertEquals(listOf("calendar $start $end Standup"), did)
        }
    }

    @Test
    fun `a time card says where it was sent from`() {
        val at = 1_790_000_000_000L
        val p = parts("🕒 9:00 AM PDT\n${encodeTzTag(formatIsoUtc(at), "PDT")}")
        assertEquals(listOf<StoryPart>(StoryPart.TzWidget(at, "PDT")), p)
        render(p) { assertTrue(shows("sender sent from PDT") && shows("UTC")) }
    }

    @Test
    fun `a location card opens the map`() {
        val p = parts(formatLocationShare(51.5, -0.12))
        assertEquals(listOf<StoryPart>(StoryPart.LocWidget(51.5, -0.12)), p)
        render(p) {
            onNodeWithText("Open").performClick()
            assertEquals(listOf("open ${osmViewerUrl(51.5, -0.12)}"), did)
        }
    }

    @Test
    fun `a bracket that is not a whole tag stays text`() {
        val p = parts("[poll|only one option]")
        assertEquals("[poll|only one option]", (p.single() as StoryPart.Text).text.text)
    }

    // ─── taps ─────────────────────────────────────────────────────

    @Test
    fun `a link, a mention and the words around them each go their own way`() {
        val p = parts("""[{"inline":["hello ",{"ship":"~mitlyn-ditrel"}," see ",{"link":{"href":"https://x.test/a","content":"this page"}}]}]""", raw = true)
        render(p) {
            clickWord("this page")
            clickWord("~mitlyn-ditrel")
            clickWord("hello")
            assertEquals(listOf("link https://x.test/a", "mention ~mitlyn-ditrel", "message"), did)
        }
    }

    @Test
    fun `code and tables are drawn, a picture opens, and media offers to play elsewhere`() {
        val p = parts(
            """[{"block":{"code":{"code":"val x = 1","lang":"kotlin"}}},""" +
                """{"block":{"image":{"src":"https://x.test/cat.png","alt":"a cat","height":300,"width":400}}},""" +
                """{"inline":[{"link":{"href":"https://x.test/song.mp3","content":"song"}}]}]""",
            raw = true,
        )
        render(p) {
            assertTrue(shows("val x = 1"))
            onNodeWithContentDescription("a cat").performClick()
            assertTrue("image https://x.test/cat.png" in did)
            assertTrue(onAllNodesWithText("song.mp3", substring = true).fetchSemanticsNodes().isNotEmpty() || shows("Audio"), "a row for the audio")
        }
    }

    @Test
    fun `a quoted post shows where it is from and what it says, and opens it`() {
        val p = parts("""[{"block":{"cite":{"chan":{"nest":"chat/~bus/general","where":"/msg/170.141.184.506"}}}}]""", raw = true)
        val resolver = object : CiteResolver {
            override suspend fun findLocal(whom: String, da: String) =
                MessageEntity(whom, da, "~bus", 1, """[{"inline":["the quoted words"]}]""", "/chat")
            override suspend fun fetchPost(whom: String, da: String): MessageEntity? = null
            override suspend fun fetchReply(whom: String, postDa: String, replyDa: String): MessageEntity? = null
        }
        render(p, cite = resolver) {
            waitUntil(timeoutMillis = 5_000) { shows("the quoted words") }
            assertTrue(shows("Post in Garden · General") && shows("Bus"))
            onNodeWithText("the quoted words").performClick()
            assertEquals(listOf("cite chat/~bus/general"), did)
        }
    }
}
