package io.nisfeb.talon.call

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.urbit.FakeShip
import io.nisfeb.talon.urbit.TlonChatRepo
import io.nisfeb.talon.urbit.UrbitSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.runBlocking
import kotlinx.io.IOException
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * A recording notice the ship could not take waits in the queue and goes
 * when the ship is back, saying a call is recorded that never was. Its
 * stop goes behind it, through the same send the app roots wire, so the
 * peer reads both, in order.
 */
class RecordingNoticeQueueTest {
    private val dir = createTempDirectory(prefix = "talon-notice-").toFile()
    private val db: AppDatabase = Room.databaseBuilder<AppDatabase>(File(dir, "t.db").absolutePath)
        .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
    private val ship = FakeShip("~zod").apply {
        scries["trunk/policy"] = "{}"
        scries["trunk/version"] = """{"wire":${TrunkWire.WIRE_VERSION}}"""
    }
    private val repo = TlonChatRepo(db).apply { attachForTest(ship.channel, "~zod") }
    private val lost = IOException("The network connection was lost.")

    private class Engine : CallEngine {
        private val desc = SessionDesc("v=0\na=fingerprint:sha-256 AA:BB\n", "sha-256 AA:BB")
        override val state = MutableStateFlow(MediaState.Idle)
        override suspend fun createOffer() = desc
        override suspend fun acceptOffer(remote: SessionDesc) = desc
        override suspend fun setAnswer(remote: SessionDesc) = Unit
        override fun setMuted(muted: Boolean) = Unit
        override val video = MutableStateFlow(VideoState())
        override suspend fun setCameraEnabled(enabled: Boolean) = false
        override fun close() = Unit
    }

    @AfterTest
    fun close() {
        runBlocking { repo.stopAndJoinForTest() }
        db.close()
        dir.deleteRecursively()
    }

    /** Wait for [cond]; a timeout names [what] in its message. */
    private suspend fun until(what: String, cond: suspend () -> Boolean) {
        val ok = kotlinx.coroutines.withTimeoutOrNull(10_000) { while (!cond()) delay(20) }
        check(ok != null) { "not $what" }
    }

    private fun fact(json: String) = runBlocking { ship.emit("""{"id":1,"response":"diff","json":$json}""") }

    @Test
    fun `a notice that waits for the ship goes later with its stop, in order`() = runBlocking<Unit> {
        val events = ship.channel.events().launchIn(this)
        val engine = Engine()
        val calls = CallController(UrbitSession(ship.http, ship.session).apply { tryRestore("~zod") }, CallEngineProvider { engine })
        calls.recordingNotice = { peer, text -> repo.sendConfirmed(peer, text) }
        calls.start()
        try {
            until("subscribed") { ship.subscribed.any { it.startsWith("trunk") } }
            fact("""{"recv":{"from":"~bus","sig":{"ring":{"id":"r1"}}}}""")
            until("ringing") { calls.state.value is CallUiState.Incoming }
            calls.accept()
            fact("""{"recv":{"from":"~bus","sig":{"offer":{"id":"r1","sdp":"v=0\na=fingerprint:sha-256 AA:BB\n","fpr":"sha-256 AA:BB"}}}}""")
            engine.state.value = MediaState.Live
            until("live") { (calls.state.value as? CallUiState.Active)?.media == MediaState.Live }

            ship.lose = { if (it.app == "chat") lost else null }
            assertEquals("Couldn't tell ~bus in your chat, so the call isn't being recorded.", calls.recordCall())
            assertFalse(calls.callRecording.value)
            until("both queued") { db.messages().queuedIn("~bus") == 2 }

            ship.lose = { null }
            repo.drainQueue()
            val said = ship.pokesTo("chat").map { p ->
                listOf(CallController.RECORDING_ON, CallController.RECORDING_OFF).single { it in p.json.toString() }
            }
            assertEquals(listOf(CallController.RECORDING_ON, CallController.RECORDING_OFF), said, "the stop goes behind the notice, never alone or first")
        } finally {
            calls.stop()
            events.cancel()
        }
    }
}
