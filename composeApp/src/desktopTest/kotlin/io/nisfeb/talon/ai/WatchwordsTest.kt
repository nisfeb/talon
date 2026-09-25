package io.nisfeb.talon.ai

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.data.NotifyLevel
import io.nisfeb.talon.data.NotifyPreferenceEntity
import io.nisfeb.talon.urbit.FakeAiSettings
import io.nisfeb.talon.urbit.FakeShip
import io.nisfeb.talon.urbit.SettingsSyncImpl
import io.nisfeb.talon.urbit.TlonChatRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Watchwords as every platform now runs them: through the repo, which
 * matches each live message, keeps the hits and says which to notify;
 * a new term scans history; each change mirrors to %settings while the
 * sync switch is on.
 */
class WatchwordsTest {
    private val db: AppDatabase = createTempDirectory(prefix = "talon-words-").toFile().let { dir ->
        Room.databaseBuilder<AppDatabase>(File(dir, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
    }
    private val ship = FakeShip("~zod")
    private val syncOn = MutableStateFlow(true)
    private val sync = SettingsSyncImpl(db = db, aiSettings = FakeAiSettings()).apply { attach(ship.channel) }
    private val repo = TlonChatRepo(db, settingsSync = sync, watchwordsSyncEnabled = syncOn).apply { attachForTest(ship.channel, "~zod") }
    private val words = repo.watchwords
    private val notices: MutableList<Pair<String, List<String>>> = java.util.concurrent.CopyOnWriteArrayList()

    init {
        repo.watchwordListener = { m, notice -> notices += m.id to notice.terms }
    }

    @AfterTest
    fun close() = db.close()

    private fun live(body: suspend CoroutineScope.() -> Unit) = runBlocking<Unit> {
        val events = ship.channel.events().launchIn(this)
        try { body() } finally { events.cancel() }
    }

    private var nextId = 170141184500L
    private suspend fun dm(from: String, text: String, whom: String = "~bus"): String {
        val id = "$from/${nextId++}"
        val essay = """{"content":[{"inline":["$text"]}],"author":"$from","sent":1000,"kind":"/chat","blob":null,"meta":null}"""
        repo.applyEvent(Json.parseToJsonElement("""{"id":1,"response":"diff","json":{"whom":"$whom","id":"$id","response":{"add":{"essay":$essay,"time":null}}}}"""))
        return id
    }

    private suspend fun hits(term: String) = db.watchwords().streamHitsForTerm(term).first().map { it.postId }

    private suspend fun settled(what: () -> Boolean) = withTimeout(10_000) { while (!what()) delay(50) }

    private fun settingsPokes() = ship.pokesTo("settings").map { it.json.toString() }

    @Test
    fun `a live message with a term is kept and noticed, and a longer word is not a match`() = live {
        words.add("Mars", notify = true)
        val hit = dm("~bus", "off to (Mars)!")
        dm("~bus", "marshmallows")
        assertEquals(listOf(hit), hits("Mars"))
        assertEquals(listOf(hit to listOf("Mars")), notices.toList())
    }

    @Test
    fun `a quiet term keeps the hit and says nothing`() = live {
        words.add("mars", notify = false)
        val hit = dm("~bus", "mars again")
        assertEquals(listOf(hit), hits("mars"))
        assertTrue(notices.isEmpty())
    }

    @Test
    fun `ours, muted and excluded chats are passed over`() = live {
        words.add("mars", notify = true)
        dm("~zod", "mars from me")
        db.notifyPrefs().upsert(NotifyPreferenceEntity("~nec", NotifyLevel.NONE))
        dm("~nec", "mars muted", whom = "~nec")
        words.excludeChat("~wes", true)
        dm("~wes", "mars excluded", whom = "~wes")
        assertTrue(hits("mars").isEmpty())
        assertTrue(notices.isEmpty())
    }

    @Test
    fun `a new term finds its matches in history, not in excluded chats`() = live {
        fun old(whom: String, id: String, text: String) =
            MessageEntity(whom, id, "~bus", 5, """[{"inline":["$text"]}]""", "/chat")
        db.messages().upsertAll(listOf(old("~bus", "1", "we went to mars"), old("~bus", "2", "marsupial"), old("~wes", "3", "mars too")))
        words.excludeChat("~wes", true)
        words.add("mars", notify = true)
        settled { runBlocking { hits("mars") } == listOf("1") }
        assertTrue(notices.isEmpty(), "history is not news")
    }

    @Test
    fun `terms and excludes mirror to the ship while sync is on`() = live {
        val id = words.add("mars", notify = true)
        settled { settingsPokes().any { "\"bucket-key\":\"watchwords\"" in it && "mars" in it } }
        words.excludeChat("~wes", true)
        settled { settingsPokes().any { "watchword-excludes" in it && "~wes" in it } }
        words.remove(id)
        settled { settingsPokes().any { "del-entry" in it && "mars" in it } }
        assertTrue(hits("mars").isEmpty())
    }

    @Test
    fun `switching sync off clears the ship's copy and keeps later changes here`() = live {
        syncOn.value = false
        settled { settingsPokes().count { "del-bucket" in it } == 2 }
        val before = settingsPokes().size
        words.add("venus", notify = true)
        delay(300)
        assertEquals(before, settingsPokes().size, "nothing sent while off")
        syncOn.value = true
        settled { settingsPokes().any { "put-entry" in it && "venus" in it } }
    }
}
