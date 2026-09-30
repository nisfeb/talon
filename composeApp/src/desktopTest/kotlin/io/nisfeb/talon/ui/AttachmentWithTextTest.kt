package io.nisfeb.talon.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An attachment sits above the box, which stays open: a message written
 * before a screenshot was attached went out as the screenshot alone and
 * the text was thrown away.
 */
@OptIn(ExperimentalTestApi::class)
class AttachmentWithTextTest {
    private val texts = mutableListOf<String>()
    private val strategy = object : ChatSendStrategy {
        override suspend fun sendText(text: String) { texts += text }
        override suspend fun sendImage(src: String, width: Int, height: Int, alt: String, caption: String) = Unit
        override val supportsQuote = true
        override suspend fun sendQuote(body: String, quoteWhom: String, quoteId: String) = Unit
    }

    private fun withComposer(block: androidx.compose.ui.test.ComposeUiTest.(ComposerState) -> Unit) {
        val tmp = createTempDirectory(prefix = "talon-attach-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(tmp, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver())
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        try {
            runComposeUiTest {
                lateinit var state: ComposerState
                val drafts = InMemoryDraftStore()
                setContent {
                    TalonTheme(darkTheme = false) {
                        state = rememberComposerState("~zod", drafts)
                        ChatComposer(
                            state = state, db = db, repo = TlonChatRepo(db), http = createAppHttpClient(),
                            drafts = drafts, whom = "~zod", contactMap = ContactMap.EMPTY, allShips = emptyList(),
                            canSend = true, hideComposerButtons = true, focusOnOpen = false, strategy = strategy,
                        )
                    }
                }
                waitForIdle()
                runOnIdle {
                    state.draft = TextFieldValue("look at this")
                    state.pendingAttachment = PendingAttachment("x".encodeToByteArray(), "text/plain", "notes.txt", isImage = false)
                }
                waitForIdle()
                block(state)
            }
        } finally {
            db.close()
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `the text stays in view beside the attachment`() = withComposer {
        onNodeWithText("look at this").assertIsDisplayed()
        onNodeWithText("notes.txt").assertIsDisplayed()
    }

    @Test
    fun `enter sends the attachment with the text, and a failed send keeps both`() = withComposer { state ->
        onNodeWithText("look at this").performKeyInput { pressKey(Key.Enter) }
        // No ship here, so the upload fails: nothing may have been lost.
        waitUntil(timeoutMillis = 5_000) { state.sendError != null }
        assertTrue(texts.isEmpty(), "the text never went out on its own: $texts")
        assertEquals("look at this", state.draft.text)
        assertNotNull(state.pendingAttachment)
    }

    @Test
    fun `escape drops the attachment and keeps the text`() = withComposer { state ->
        onNodeWithText("look at this").performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertNull(state.pendingAttachment)
        assertEquals("look at this", state.draft.text)
    }
}
