package io.nisfeb.talon.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.ui.theme.TalonTheme
import io.nisfeb.talon.urbit.TlonChatRepo
import io.nisfeb.talon.util.createAppHttpClient
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A quote stays with its conversation, the way the half-typed message beside it does. */
@OptIn(ExperimentalTestApi::class)
class QuoteSurvivesTest {

    private val noSend = object : ChatSendStrategy {
        override suspend fun sendText(text: String) = Unit
        override suspend fun sendImage(src: String, width: Int, height: Int, alt: String) = Unit
        override val supportsQuote = true
        override suspend fun sendQuote(body: String, quoteWhom: String, quoteId: String) = Unit
    }

    private val quoted = MessageEntity(
        whom = "~zod", id = "170.141.184.500", author = "~zod", sentMs = 1, contentJson = "[]", kind = "chat",
    )

    @AfterTest
    fun tearDown() = PendingQuotes.clear()

    @Test
    fun `leaving a chat and coming back finds the quote still attached`() {
        val tmp = createTempDirectory(prefix = "talon-quote-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(tmp, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver())
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        try {
            runComposeUiTest {
                var whom by mutableStateOf("~zod")
                lateinit var state: ComposerState
                val drafts = InMemoryDraftStore()
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
                runOnIdle { state.pendingQuote = quoted }
                waitForIdle()

                // Another conversation: its own composer, with no quote of ours.
                whom = "~bus"
                waitForIdle()
                assertNull(state.pendingQuote, "a quote belongs to the chat it was picked in")

                whom = "~zod"
                waitForIdle()
                assertEquals(quoted.id, state.pendingQuote?.id)
            }
        } finally {
            db.close()
            tmp.deleteRecursively()
        }
    }
}
