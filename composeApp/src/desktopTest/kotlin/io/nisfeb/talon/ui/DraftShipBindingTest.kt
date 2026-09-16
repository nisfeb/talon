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

    /** Drafts per ship, resolving the active ship at call time the way
     *  the iOS store does, and binding the way it does too. A fake on
     *  purpose: the real IosDraftStore is iosMain and unreachable from
     *  here, so this mirrors its Bound semantics — the contract this
     *  test exists to pin. */
    private class ShipDrafts(private val active: () -> String) : DraftStore() {
        val byShip = mutableMapOf<String, MutableMap<String, String>>()
        private fun of(ship: String) = byShip.getOrPut(ship) { mutableMapOf() }
        override fun load(whom: String) = of(active())[whom] ?: ""
        // Blank removes, as the real stores do.
        override fun save(whom: String, draft: String) { if (draft.isBlank()) of(active()).remove(whom) else of(active())[whom] = draft }
        override fun clear(whom: String) { of(active()).remove(whom) }
        override fun bound(): DraftStore {
            val ship = active()
            return object : DraftStore() {
                override fun load(whom: String) = of(ship)[whom] ?: ""
                override fun save(whom: String, draft: String) { if (draft.isBlank()) of(ship).remove(whom) else of(ship)[whom] = draft }
                override fun clear(whom: String) { of(ship).remove(whom) }
            }
        }
    }

    private val noSend = object : ChatSendStrategy {
        override suspend fun sendText(text: String) = Unit
        override suspend fun sendImage(src: String, width: Int, height: Int, alt: String) = Unit
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
            val drafts = ShipDrafts { active }
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
            assertEquals("typed on a", drafts.byShip["~a"]?.get("~group"))
            // The incoming composer may record its own empty start; what it
            // must never hold is the outgoing ship's text.
            assertTrue(drafts.byShip["~b"]?.get("~group").isNullOrEmpty(), "the incoming ship must not inherit the outgoing draft")
        } finally {
            db.close()
            tmp.deleteRecursively()
        }
    }
}
