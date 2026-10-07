package io.nisfeb.talon.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
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
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * While an input method composes (pinyin, kana, hangul), its keys are its
 * own: Enter confirms a candidate. The composer took Enter first and sent
 * the half-typed message.
 */
@OptIn(ExperimentalTestApi::class)
class ImeCompositionTest {

    private val sent = CopyOnWriteArrayList<String>()
    private val recording = object : ChatSendStrategy {
        override suspend fun sendText(text: String) { sent += text }
        override suspend fun sendImage(src: String, width: Int, height: Int, alt: String, caption: String) = Unit
        override val supportsQuote = false
        override suspend fun sendQuote(body: String, quoteWhom: String, quoteId: String) = Unit
    }

    @Test
    fun `Enter while an input method composes is the input method's, not a send`() = composer { state ->
        state.draft = TextFieldValue("にほんご", selection = TextRange(4), composition = TextRange(0, 4))
        waitForIdle()
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        Thread.sleep(300)
        waitForIdle()
        assertEquals(emptyList(), sent.toList())
    }

    @Test
    fun `Enter with nothing composing still sends`() = composer { state ->
        state.draft = TextFieldValue("hello", selection = TextRange(5))
        waitForIdle()
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }
        waitUntil(timeoutMillis = 5_000) { sent.isNotEmpty() }
        assertEquals(listOf("hello"), sent.toList())
    }

    private fun composer(body: ComposeUiTest.(ComposerState) -> Unit) {
        val tmp = createTempDirectory(prefix = "talon-ime-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(tmp, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver())
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        try {
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
                            canSend = true, hideComposerButtons = true, focusOnOpen = false, strategy = recording,
                        )
                    }
                }
                waitForIdle()
                onNode(hasSetTextAction()).performClick()
                body(state)
            }
        } finally {
            db.close()
            tmp.deleteRecursively()
        }
    }
}
