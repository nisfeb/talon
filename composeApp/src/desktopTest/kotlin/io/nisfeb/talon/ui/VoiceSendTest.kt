package io.nisfeb.talon.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.input.TextFieldValue
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.ui.theme.TalonTheme
import io.nisfeb.talon.urbit.FakeShip
import io.nisfeb.talon.urbit.TlonChatRepo
import io.nisfeb.talon.util.createAppHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A recorded voice message, sent from the composer's preview row: on the
 * repo's scope, so leaving the chat mid-upload still sends it and only
 * then is the recording deleted; and the words in the box are left alone.
 * Desktop has no recorder, so a file stands in for the recording; the
 * Android and iOS recorders that stage one are not driven here.
 * Real time throughout: the virtual clock hides the waits.
 */
@OptIn(ExperimentalTestApi::class)
class VoiceSendTest {
    private val texts = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val strategy = object : ChatSendStrategy {
        override suspend fun sendText(text: String) { texts += text }
        override suspend fun sendImage(src: String, width: Int, height: Int, alt: String, caption: String) { texts += "image $src [$caption]" }
        override val supportsQuote = false
        override suspend fun sendQuote(body: String, quoteWhom: String, quoteId: String) = Unit
    }

    /** A ship that hosts uploads slowly, as AttachmentWithTextTest's does. */
    private fun hostingShip(putMs: Long = 1_000) = FakeShip("~zod").apply {
        scries["genuine/secret"] = "\"tok-1\""
        answerApi = { method, path, _ ->
            when {
                method == "PUT" && path == "/v1/zod/upload" ->
                    """{"uploadUrl":"https://bucket.test/put-here?sig=1","hostedUrl":"https://cdn.test/zod/voice.m4a"}"""
                method == "PUT" && path == "/put-here" -> { Thread.sleep(putMs); "" }
                else -> null
            }
        }
    }

    private fun recording(dir: File) = File(dir, "voice-1.m4a").apply { writeBytes(byteArrayOf(0, 0, 0, 24, 102, 116, 121, 112)) }

    private fun until(ms: Long = 8_000, done: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!done() && System.currentTimeMillis() < end) Thread.sleep(50)
    }

    /** The composer with a recording staged and [typed] in the box; [ship] null is no ship at all. */
    private fun withVoice(
        ship: FakeShip?,
        typed: String,
        block: androidx.compose.ui.test.ComposeUiTest.(state: () -> ComposerState, leave: () -> Unit, file: File, drafts: InMemoryDraftStore) -> Unit,
    ) {
        val tmp = createTempDirectory(prefix = "talon-voice-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(tmp, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        val events = CoroutineScope(Dispatchers.IO).launch { ship?.channel?.events()?.collect {} }
        val repo = TlonChatRepo(db).also { r -> ship?.let { r.attachForTest(it.channel, "~zod", http = it.http) } }
        val file = recording(tmp)
        try {
            runComposeUiTest {
                var shown by mutableStateOf(true)
                lateinit var state: ComposerState
                val drafts = InMemoryDraftStore()
                setContent {
                    TalonTheme(darkTheme = false) {
                        if (shown) {
                            state = rememberComposerState("~zod", drafts)
                            ChatComposer(
                                state = state, db = db, repo = repo, http = createAppHttpClient(),
                                drafts = drafts, whom = "~zod", contactMap = ContactMap.EMPTY, allShips = emptyList(),
                                canSend = true, hideComposerButtons = true, focusOnOpen = false, strategy = strategy,
                            )
                        }
                    }
                }
                waitForIdle()
                runOnIdle {
                    state.draft = TextFieldValue(typed)
                    drafts.save("~zod", typed)
                    state.pendingVoice = PendingVoice(file.absolutePath, durationMs = 3_400)
                }
                waitForIdle()
                block({ state }, { runOnIdle { shown = false }; waitForIdle() }, file, drafts)
            }
        } finally {
            events.cancel()
            runBlocking { repo.stopAndJoinForTest() }
            db.close()
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `leaving the chat mid-upload still sends the voice message and then deletes it`() =
        withVoice(hostingShip(), typed = "half a thought") { state, leave, file, drafts ->
            onNodeWithContentDescription("Send recording").performClick()
            waitUntil(timeoutMillis = 2_000) { state().uploading }
            leave()
            assertTrue(file.exists(), "not deleted while it is on its way")
            until { texts.isNotEmpty() }
            assertEquals(listOf("[🎙 Voice 3s](https://cdn.test/zod/voice.m4a)"), texts.toList())
            until { !file.exists() }
            assertFalse(file.exists(), "deleted once sent")
            assertEquals("half a thought", drafts.load("~zod"), "the words in the box were not part of it")
        }

    @Test
    fun `a sent voice message leaves the words in the box`() =
        withVoice(hostingShip(putMs = 0), typed = "and another thing") { state, _, file, drafts ->
            onNodeWithContentDescription("Send recording").performClick()
            until { texts.isNotEmpty() && state().pendingVoice == null }
            waitForIdle()
            assertEquals(1, texts.size)
            assertEquals("and another thing", state().draft.text)
            assertEquals("and another thing", drafts.load("~zod"))
            until { !file.exists() }
            assertFalse(file.exists())
        }

    @Test
    fun `a voice message that fails stays staged with its file for another try`() =
        withVoice(ship = null, typed = "") { state, _, file, _ ->
            onNodeWithContentDescription("Send recording").performClick()
            until { state().sendError != null }
            waitForIdle()
            assertNotNull(state().sendError)
            assertTrue(texts.isEmpty())
            assertNotNull(state().pendingVoice, "still there to send again")
            assertTrue(file.exists(), "the recording is kept")
        }

    @Test
    fun `a voice message that fails after its chat is left takes its file with it`() {
        val tmp = createTempDirectory(prefix = "talon-voice-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(tmp, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        try {
            val file = recording(tmp)
            val r = runBlocking { sendVoice(PendingVoice(file.absolutePath, 1_000), TlonChatRepo(db), strategy, keep = { false }) }
            assertTrue(r.isFailure)
            assertFalse(file.exists(), "nobody is left to send it again")
        } finally {
            db.close()
            tmp.deleteRecursively()
        }
    }

    // A multi-file drop carried each file's upload but not the loop, so
    // leaving the chat after the first started dropped the rest.
    @Test
    fun `every file of a drop is sent after its chat is left mid-batch`() {
        val ship = hostingShip(putMs = 700)
        val tmp = createTempDirectory(prefix = "talon-drops-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(tmp, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        val events = CoroutineScope(Dispatchers.IO).launch { ship.channel.events().collect {} }
        val screen = CoroutineScope(Dispatchers.IO)
        val repo = TlonChatRepo(db).also { it.attachForTest(ship.channel, "~zod", http = ship.http) }
        val failures = java.util.concurrent.CopyOnWriteArrayList<String>()
        try {
            val files = listOf(
                DroppedFile("a.txt", "text/plain", "a".encodeToByteArray()),
                DroppedFile("empty.txt", "text/plain", ByteArray(0)),
                DroppedFile("b.txt", "text/plain", "b".encodeToByteArray()),
            )
            screen.launch { uploadDroppedFiles(files, repo, strategy) { f, err -> failures += "${f.name} ${err != null}" } }
            Thread.sleep(300)
            screen.coroutineContext[Job]!!.cancel()
            until { texts.size == 2 }
            assertEquals(
                listOf("[📎 a.txt](https://cdn.test/zod/voice.m4a)", "[📎 b.txt](https://cdn.test/zod/voice.m4a)"),
                texts.toList(),
            )
            assertEquals(listOf("empty.txt false"), failures.toList(), "the empty one is said, and not sent")
        } finally {
            screen.coroutineContext[Job]?.cancel()
            events.cancel()
            runBlocking { repo.stopAndJoinForTest() }
            db.close()
            tmp.deleteRecursively()
        }
    }
}
