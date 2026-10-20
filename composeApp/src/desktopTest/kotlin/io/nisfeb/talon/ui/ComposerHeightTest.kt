package io.nisfeb.talon.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
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

/**
 * A user reported that with a bigger text size the message box changed
 * height by a few pixels as they started typing. Material draws a
 * field's placeholder in its own style, so the empty box was sized by
 * that and the typed one by the composer's. At the default size the
 * field's minimum height hid the difference.
 */
@OptIn(ExperimentalTestApi::class)
class ComposerHeightTest {

    private val idle = object : ChatSendStrategy {
        override suspend fun sendText(text: String) = Unit
        override suspend fun sendImage(src: String, width: Int, height: Int, alt: String, caption: String) = Unit
        override val supportsQuote = false
        override suspend fun sendQuote(body: String, quoteWhom: String, quoteId: String) = Unit
    }

    @Test
    fun `the message box keeps its height when typing starts, at every text size`() {
        for (scale in listOf(1f, 1.3f, 1.6f, 2f)) {
            val (empty, typed) = heights(scale)
            assertEquals(empty, typed, "at text size $scale the box went from $empty to $typed")
        }
    }

    /** The box's height empty, then with a word typed, at [fontScale]. */
    private fun heights(fontScale: Float): Pair<Float, Float> {
        val tmp = createTempDirectory(prefix = "talon-height-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(tmp, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver())
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        try {
            var result = 0f to 0f
            runComposeUiTest {
                val whom = "chat/~nec/tlon-studio/general"
                val drafts = InMemoryDraftStore()
                lateinit var state: ComposerState
                setContent {
                    val d = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
                        TalonTheme(darkTheme = false) {
                            state = rememberComposerState(whom, drafts)
                            ChatComposer(
                                state = state, db = db, repo = TlonChatRepo(db), http = createAppHttpClient(),
                                drafts = drafts, whom = whom, contactMap = ContactMap.EMPTY, allShips = emptyList(),
                                canSend = true, hideComposerButtons = true, focusOnOpen = false, strategy = idle,
                            )
                        }
                    }
                }
                waitForIdle()
                val box = onNode(hasSetTextAction())
                val empty = box.fetchSemanticsNode().size.height.toFloat()
                state.draft = TextFieldValue("hello", TextRange(5))
                waitForIdle()
                result = empty to box.fetchSemanticsNode().size.height.toFloat()
            }
            return result
        } finally {
            db.close()
            tmp.deleteRecursively()
        }
    }
}
