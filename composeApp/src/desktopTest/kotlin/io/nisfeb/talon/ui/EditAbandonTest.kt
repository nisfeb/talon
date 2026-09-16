package io.nisfeb.talon.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.ui.theme.TalonTheme
import io.nisfeb.talon.urbit.TlonChatRepo
import io.nisfeb.talon.util.createAppHttpClient
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

/** An edit is abandoned when the conversation is left; a real draft is kept. */
@OptIn(ExperimentalTestApi::class)
class EditAbandonTest {

    private val noSend = object : ChatSendStrategy {
        override suspend fun sendText(text: String) = Unit
        override suspend fun sendImage(src: String, width: Int, height: Int, alt: String) = Unit
        override val supportsQuote = false
        override suspend fun sendQuote(body: String, quoteWhom: String, quoteId: String) = Unit
    }

    /** Opens ~zod, runs [step] against its composer, then switches to ~nec. */
    private fun leaveAfter(drafts: DraftStore, step: androidx.compose.ui.test.ComposeUiTest.(ComposerState) -> Unit) {
        val tmp = createTempDirectory(prefix = "talon-edit-abandon-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(tmp, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver())
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        try {
            runComposeUiTest {
                var whom by mutableStateOf("~zod")
                lateinit var state: ComposerState
                setContent {
                    TalonTheme(darkTheme = false) {
                        state = rememberComposerState(whom, drafts)
                        ChatComposer(
                            state = state, db = db, repo = TlonChatRepo(db), http = createAppHttpClient(),
                            drafts = drafts, whom = whom, contactMap = ContactMap.EMPTY, allShips = emptyList(),
                            canSend = true, hideComposerButtons = true, focusOnOpen = false, strategy = noSend,
                        )
                    }
                }
                waitForIdle()
                step(state)
                whom = "~nec"
                waitForIdle()
            }
        } finally {
            db.close()
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `leaving mid-edit keeps the draft the edit displaced`() {
        val drafts = InMemoryDraftStore().apply { save("~zod", "half typed") }
        leaveAfter(drafts) { state ->
            runOnIdle {
                state.editing = EditTarget(postId = "p1", originalSentMs = 1, originalContentJson = null, priorDraftText = "half typed")
                state.draft = TextFieldValue("the sent words", TextRange(14))
            }
            waitForIdle()
            onNodeWithText("Editing message").assertExists()
            onNode(hasSetTextAction()).performTextInput(" fixed")
            waitForIdle()
        }
        assertEquals("half typed", drafts.load("~zod"))
    }

    @Test
    fun `leaving with a plain draft still keeps it`() {
        val drafts = InMemoryDraftStore()
        leaveAfter(drafts) {
            onNode(hasSetTextAction()).performTextInput("hello")
            waitForIdle()
        }
        assertEquals("hello", drafts.load("~zod"))
    }
}
