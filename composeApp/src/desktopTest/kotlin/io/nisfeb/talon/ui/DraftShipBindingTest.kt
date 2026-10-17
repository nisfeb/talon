package io.nisfeb.talon.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
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
import kotlin.test.assertTrue

/**
 * A draft saved as a composer disposes belongs to the ship it was
 * composed under, even though the switch has already happened and the
 * incoming ship's composer has loaded the same conversation.
 */
@OptIn(ExperimentalTestApi::class)
class DraftShipBindingTest {

    /** What a [FileDraftStore] wrote, by file name, in place of the disk. */
    private val files = mutableMapOf<String, String>()

    /** The draft [whom] on [ship], as its file holds it. */
    private fun kept(ship: String, whom: String): String? =
        files[draftsFileName(ship)]?.let { kotlinx.serialization.json.Json.decodeFromString<Map<String, String>>(it)[whom] }

    private val noSend = object : ChatSendStrategy {
        override suspend fun sendText(text: String) = Unit
        override suspend fun sendImage(src: String, width: Int, height: Int, alt: String, caption: String) = Unit
        override val supportsQuote = false
        override suspend fun sendQuote(body: String, quoteWhom: String, quoteId: String) = Unit
    }

    @Test
    fun `a dispose-time save lands in the ship the composer was built under`() {
        val tmp = createTempDirectory(prefix = "talon-draft-ship-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(tmp, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver())
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        try {
            var active = "~a"
            // The desktop and iOS store itself, over a map for the disk.
            val drafts = FileDraftStore({ active }, read = { files[it] }, write = { name, text -> files[name] = text })
            runComposeUiTest {
                // Same conversation on both ships; the tree re-keys on the
                // ship exactly as the shell does.
                var shipKey by mutableStateOf("~a")
                setContent {
                    TalonTheme(darkTheme = false) {
                        key(shipKey) {
                            val state = rememberComposerState("~group", drafts)
                            ChatComposer(
                                state = state, db = db, repo = TlonChatRepo(db), http = createAppHttpClient(),
                                drafts = drafts, whom = "~group", contactMap = ContactMap.EMPTY, allShips = emptyList(),
                                canSend = true, hideComposerButtons = true, focusOnOpen = false, strategy = noSend,
                            )
                        }
                    }
                }
                waitForIdle()
                onNode(hasSetTextAction()).performTextInput("typed on a")
                waitForIdle()
                // The switch: the active ship flips first, then the tree
                // re-keys, and only then does the old composer dispose.
                active = "~b"
                shipKey = "~b"
                waitForIdle()
            }
            assertEquals("typed on a", kept("~a", "~group"))
            // The incoming composer may record its own empty start; what it
            // must never hold is the outgoing ship's text.
            assertTrue(kept("~b", "~group").isNullOrEmpty(), "the incoming ship must not inherit the outgoing draft")
        } finally {
            db.close()
            tmp.deleteRecursively()
        }
    }
}

