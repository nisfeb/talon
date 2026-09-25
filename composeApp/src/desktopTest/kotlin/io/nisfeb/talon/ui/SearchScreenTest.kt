package io.nisfeb.talon.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.ai.IndexProgress
import io.nisfeb.talon.ai.SearchEmbedderClient
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.ContactEntity
import io.nisfeb.talon.data.GroupEntity
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.data.MessageMediaEntity
import io.nisfeb.talon.ui.screens.SearchScreen
import io.nisfeb.talon.ui.theme.TalonTheme
import io.nisfeb.talon.urbit.FakeAiSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Search over what is kept here: groups, people and messages for a word,
 * the from:/since:/has: operators, and search by meaning when an
 * embedder is there.
 */
@OptIn(ExperimentalTestApi::class)
class SearchScreenTest {
    private val db: AppDatabase = createTempDirectory(prefix = "talon-search-").toFile().let { dir ->
        Room.databaseBuilder<AppDatabase>(File(dir, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
    }
    private val now = System.currentTimeMillis()
    private val day = 24 * 60 * 60 * 1000L

    private fun msg(whom: String, id: String, author: String, ago: Long, text: String, deleted: Boolean = false) =
        MessageEntity(whom, id, author, now - ago, """[{"inline":["$text"]}]""", "/chat", isDeleted = deleted)

    private val noon = msg("~bus", "1", "~bus", 60 * 60 * 1000L, "lunch at noon")
    private val plans = msg("chat/~bus/general", "2", "~nec", 30 * day, "lunch plans tomorrow")
    private val dinner = msg("~bus", "3", "~zod", 2 * day, "dinner later")

    init {
        runBlocking {
            db.messages().upsertAll(listOf(noon, plans, dinner, msg("~bus", "4", "~bus", day, "lunch deleted", deleted = true)))
            db.messageMedia().insertAll(listOf(MessageMediaEntity("chat/~bus/general", "2", "https://x.test/a.png", "Photo", null, plans.sentMs, "~nec")))
            db.contacts().upsert(ContactEntity("~bus", "Bus", null, null))
            db.contacts().upsert(ContactEntity("~nec", "Lunch Club", null, null))
            db.groups().upsertGroups(listOf(GroupEntity("~bus/crew", "Lunch Crew", null)))
        }
    }

    @AfterTest
    fun close() = db.close()

    private val ui = InMemoryUiSettings()
    private val opened = mutableListOf<String>()

    private class Embedder(private val meaning: List<MessageEntity>, private val highlights: List<MessageEntity>) : SearchEmbedderClient {
        override val progress = MutableStateFlow(IndexProgress(indexed = 3, total = 3))
        override suspend fun start() = Unit
        override suspend fun semanticSearch(query: String) = meaning
        override suspend fun keywordSearch(terms: List<String>) = emptyList<MessageEntity>()
        override suspend fun embed(text: String): FloatArray? = null
        override suspend fun computeHighlights() = highlights
    }

    private fun search(embedder: SearchEmbedderClient? = null, block: ComposeUiTest.() -> Unit) = runComposeUiTest {
        setContent {
            TalonTheme(darkTheme = false) {
                SearchScreen(
                    db = db, aiSettings = FakeAiSettings(), uiSettings = ui,
                    onOpenConversation = { opened += "dm $it" },
                    onOpenMessage = { whom, id, parent -> opened += "msg $whom $id $parent" },
                    onOpenGroup = { opened += "group $it" },
                    onBack = {},
                    embedder = embedder,
                )
            }
        }
        block()
    }

    private fun ComposeUiTest.shows(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.type(query: String, until: String) {
        onNode(hasSetTextAction()).performTextClearance()
        onNode(hasSetTextAction()).performTextInput(query)
        waitUntil(timeoutMillis = 5_000) { shows(until) }
    }

    private fun ComposeUiTest.top(text: String) = onNodeWithText(text, substring = true).fetchSemanticsNode().boundsInRoot.top

    @Test
    fun `a word finds groups, people and messages, newest first, and not deleted ones`() = search {
        type("lunch", "lunch at noon")
        assertTrue(shows("GROUPS") && shows("Lunch Crew"))
        assertTrue(shows("PEOPLE") && shows("Lunch Club") && shows("Tap to start a DM"), "a contact never written to says how to start")
        assertTrue(shows("lunch plans tomorrow"))
        assertTrue(top("lunch at noon") < top("lunch plans tomorrow"), "newest first")
        assertTrue(!shows("lunch deleted") && !shows("dinner later"))
    }

    @Test
    fun `each result opens what it is`() = search {
        type("lunch", "lunch at noon")
        onNodeWithText("Lunch Crew").performClick()
        onNodeWithText("Lunch Club").performClick()
        onNodeWithText("lunch at noon").performClick()
        assertEquals(listOf("group ~bus/crew", "dm ~nec", "msg ~bus 1 null"), opened)
    }

    @Test
    fun `operators narrow the search and say what they did`() = search {
        type("lunch from:~nec", "from ~nec")
        waitUntil(timeoutMillis = 5_000) { !shows("lunch at noon") }
        assertTrue(shows("lunch plans tomorrow"))

        type("lunch has:image", "has image")
        waitUntil(timeoutMillis = 5_000) { !shows("lunch at noon") && shows("lunch plans tomorrow") }

        type("lunch since:1w", "since ")
        waitUntil(timeoutMillis = 5_000) { shows("lunch at noon") && !shows("lunch plans tomorrow") }
    }

    @Test
    fun `too short asks for more, and nothing found says so`() = search {
        type("l", "Type at least two characters.")
        type("zzzz", "No matches.")
    }

    @Test
    fun `smart search finds by meaning once chosen, and shows highlights before typing`() {
        ui.setSmartSearchPreferred(false)
        search(Embedder(meaning = listOf(dinner), highlights = listOf(noon))) {
            waitUntil(timeoutMillis = 5_000) { shows("HIGHLIGHTS") && shows("lunch at noon") }
            onNodeWithText("✨ Smart").performClick()
            waitUntil(timeoutMillis = 5_000) { shows("✨ Smart on") && shows("3 messages indexed") }
            assertTrue(ui.smartSearchPreferred.value, "the choice is kept")
            type("evening meal", "dinner later")
        }
    }
}
