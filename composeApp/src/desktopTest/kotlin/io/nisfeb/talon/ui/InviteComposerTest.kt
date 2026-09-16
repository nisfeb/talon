package io.nisfeb.talon.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.GroupEntity
import io.nisfeb.talon.ui.theme.TalonTheme
import io.nisfeb.talon.urbit.TlonChatRepo
import io.nisfeb.talon.util.createAppHttpClient
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

/** `/invite` in the real composer: the group argument offers our groups, and a pick fills in its flag. */
@OptIn(ExperimentalTestApi::class)
class InviteComposerTest {

    private val noSend = object : ChatSendStrategy {
        override suspend fun sendText(text: String) = Unit
        override suspend fun sendImage(src: String, width: Int, height: Int, alt: String) = Unit
        override val supportsQuote = false
        override suspend fun sendQuote(body: String, quoteWhom: String, quoteId: String) = Unit
    }

    @Test
    fun `typing invite offers matching groups and a pick fills the flag`() {
        val tmp = createTempDirectory(prefix = "talon-invite-composer-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(tmp, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver())
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        try {
            runBlocking {
                db.groups().upsertGroups(
                    listOf(GroupEntity("~nec/tlon-studio", "Tlon Studio", null), GroupEntity("~zod/urbit-dev", "Urbit Dev", null)),
                )
            }
            runComposeUiTest {
                val whom = "chat/~nec/tlon-studio/general"
                val drafts = InMemoryDraftStore()
                lateinit var state: ComposerState
                setContent {
                    TalonTheme(darkTheme = false) {
                        state = rememberComposerState(whom, drafts)
                        ChatComposer(
                            state = state, db = db, repo = TlonChatRepo(db), http = createAppHttpClient(),
                            drafts = drafts, whom = whom, contactMap = ContactMap.EMPTY, allShips = listOf("~sampel-palnet"),
                            canSend = true, hideComposerButtons = true, focusOnOpen = false, strategy = noSend,
                        )
                    }
                }
                waitForIdle()
                onNode(hasSetTextAction()).performTextInput("/invite tlo")
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Tlon Studio").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("Urbit Dev").assertDoesNotExist()
                onNodeWithText("Tlon Studio").performClick()
                waitForIdle()
                assertEquals("/invite ~nec/tlon-studio ", state.draft.text)
            }
        } finally {
            db.close()
            tmp.deleteRecursively()
        }
    }
}
