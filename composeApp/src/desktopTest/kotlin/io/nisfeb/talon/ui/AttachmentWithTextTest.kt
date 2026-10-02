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
import androidx.compose.ui.test.hasSetTextAction
import kotlinx.coroutines.launch
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
    private val texts = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val strategy = object : ChatSendStrategy {
        override suspend fun sendText(text: String) { texts += text }
        override suspend fun sendImage(src: String, width: Int, height: Int, alt: String, caption: String) { texts += "image $src [$caption]" }
        override val supportsQuote = true
        override suspend fun sendQuote(body: String, quoteWhom: String, quoteId: String) { texts += "quote $quoteId [$body]" }
        override suspend fun sendImageQuote(src: String, width: Int, height: Int, alt: String, caption: String, quoteWhom: String, quoteId: String) {
            texts += "image $src quoting $quoteId [$caption]"
        }
    }
    private val quoted = io.nisfeb.talon.data.MessageEntity("~zod", "~zod/170141184506", "~bus", 1_000, """[{"inline":["the post"]}]""", "/chat")

    /** A ship that hosts uploads, as [io.nisfeb.talon.urbit.TlonChatRepoUploadTest] has it. */
    private fun hostingShip() = io.nisfeb.talon.urbit.FakeShip("~zod").apply {
        scries["genuine/secret"] = "\"tok-1\""
        answerApi = { method, path, _ ->
            when {
                method == "PUT" && path == "/v1/zod/upload" ->
                    """{"uploadUrl":"https://bucket.test/put-here?sig=1","hostedUrl":"https://cdn.test/zod/cat.png"}"""
                method == "PUT" && path == "/put-here" -> ""
                else -> null
            }
        }
    }

    private fun withComposer(
        image: Boolean = false,
        quote: Boolean = false,
        ship: io.nisfeb.talon.urbit.FakeShip? = null,
        block: androidx.compose.ui.test.ComposeUiTest.(ComposerState) -> Unit,
    ) {
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
                            state = state, db = db,
                            repo = TlonChatRepo(db).also { r -> ship?.let { r.attachForTest(it.channel, "~zod", http = it.http) } },
                            http = createAppHttpClient(),
                            drafts = drafts, whom = "~zod", contactMap = ContactMap.EMPTY, allShips = emptyList(),
                            canSend = true, hideComposerButtons = true, focusOnOpen = false, strategy = strategy,
                        )
                    }
                }
                waitForIdle()
                runOnIdle {
                    state.draft = TextFieldValue("look at this")
                    state.pendingAttachment = if (image) PendingAttachment(byteArrayOf(1, 2, 3, 4), "image/png", "cat.png", isImage = true)
                        else PendingAttachment("x".encodeToByteArray(), "text/plain", "notes.txt", isImage = false)
                    if (quote) state.pendingQuote = quoted
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

    // Quote a post, paste a picture, Enter: the picture and the words
    // went, and the quote stayed staged, so the next Enter sent the
    // quoted post again on its own.
    @Test
    fun `a staged quote goes with a pasted picture, and is gone after`() {
        val ship = hostingShip()
        withComposer(image = true, quote = true, ship = ship) { state ->
            val events = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { ship.channel.events().collect {} }
            try {
                onNodeWithText("look at this").performKeyInput { pressKey(Key.Enter) }
                waitUntil(timeoutMillis = 5_000) { texts.isNotEmpty() || state.sendError != null }
                assertEquals(listOf("image https://cdn.test/zod/cat.png quoting ~zod/170141184506 [look at this]"), texts.toList(), "${state.sendError}")
                waitUntil(timeoutMillis = 5_000) { state.pendingAttachment == null }
                assertNull(state.pendingQuote, "the quote went with it")
                assertEquals("", state.draft.text)
                onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }
                waitForIdle()
                Thread.sleep(300)
                assertEquals(1, texts.size, "nothing more to send: $texts")
            } finally { events.cancel() }
        }
    }

    @Test
    fun `a failed upload keeps the quote with the picture and the text`() = withComposer(image = true, quote = true) { state ->
        onNodeWithText("look at this").performKeyInput { pressKey(Key.Enter) }
        waitUntil(timeoutMillis = 5_000) { state.sendError != null }
        assertTrue(texts.isEmpty(), "$texts")
        assertNotNull(state.pendingAttachment)
        assertEquals(quoted.id, state.pendingQuote?.id)
        assertEquals("look at this", state.draft.text)
    }

    @Test
    fun `escape drops the attachment and keeps the text`() = withComposer { state ->
        onNodeWithText("look at this").performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertNull(state.pendingAttachment)
        assertEquals("look at this", state.draft.text)
    }
}
