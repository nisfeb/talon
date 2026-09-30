package io.nisfeb.talon.urbit

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Quotes and who is doing what: a quote sent as a cite, a quoted post
 * or reply fetched when it is not here, and %presence facts landing as
 * "typing" for everyone but us.
 */
class TlonChatRepoCiteTest {
    private val db: AppDatabase = createTempDirectory(prefix = "talon-cite-").toFile().let { dir ->
        Room.databaseBuilder<AppDatabase>(File(dir, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
    }
    private val ship = FakeShip("~zod")
    private val repo = TlonChatRepo(db).apply { attachForTest(ship.channel, "~zod") }
    private val nest = "chat/~bus/general"

    @AfterTest
    fun close() {
        runBlocking { repo.stopAndJoinForTest() }
        db.close()
    }

    private fun live(body: suspend CoroutineScope.() -> Unit) = runBlocking<Unit> {
        val events = ship.channel.events().launchIn(this)
        try { body() } finally { events.cancel() }
    }

    private fun essay(author: String, text: String) =
        """{"content":[{"inline":["$text"]}],"author":"$author","sent":1000,"kind":"/chat","blob":null,"meta":null}"""

    @Test
    fun `a quote goes as a cite of the post, with the words after it`() = live {
        repo.sendQuote("~bus", "agreed", quotedNest = nest, quotedPostId = "170141184506")
        val sent = ship.pokesTo("chat").single().json.toString()
        assertTrue("\"nest\":\"$nest\"" in sent && "\"where\":\"/msg/170141184506\"" in sent && "agreed" in sent, sent)
        assertTrue(sent.indexOf("cite") < sent.indexOf("agreed"), "the quote leads")
    }

    @Test
    fun `a quoted post not here is fetched, kept, and brings its reactions`() = live {
        ship.scries["channels/v5/$nest/posts/post/170.141.184.506"] =
            """{"seal":{"id":"170141184506","reacts":{"~nec":"👍","~dev":":+1:"}},"essay":${essay("~bus", "the original")}}"""
        val got = repo.fetchCitePost(nest, "170.141.184.506")!!
        assertEquals("170141184506" to "~bus", got.id to got.author)
        assertEquals("~bus", db.messages().getOne(nest, "170141184506")?.author)
        assertEquals(setOf("~nec", "~dev"), db.reactions().stream(nest).first().filter { it.postId == "170141184506" }.map { it.author }.toSet())
    }

    @Test
    fun `a quoted post the ship does not have is nothing, and nothing is kept`() = live {
        assertNull(repo.fetchCitePost(nest, "170.141.184.599"))
        assertNull(db.messages().getOne(nest, "170141184599"))
    }

    @Test
    fun `a quoted reply is fetched under its parent`() = live {
        ship.scries["channels/v4/$nest/posts/post/id/170.141.184.506/replies/reply/id/170.141.184.507"] =
            """{"seal":{"id":"170141184507","parent-id":"170141184506"},"reply-essay":${essay("~nec", "a reply")}}"""
        val got = repo.fetchCiteReply(nest, "170.141.184.506", "170.141.184.507")!!
        assertEquals("170141184506", got.parentId)
        assertEquals("~nec", db.messages().getOne(nest, "170141184507")?.author)
    }

    // ─── presence ──────────────────────────────────────────────────

    private suspend fun fact(json: String) =
        repo.applyEvent(Json.parseToJsonElement("""{"id":1,"response":"diff","json":$json}"""))

    private fun here(ship: String, topic: String = "typing", text: String? = null) =
        """{"here":{"key":{"context":"/dm/~bus","ship":"$ship","topic":"$topic"},"timing":{"since":"~2026.7.10","timeout":"~s30"},""" +
            """"display":{"icon":null,"text":${text?.let { "\"$it\"" } ?: "null"},"blob":null}}}"""

    @Test
    fun `someone typing shows, then goes when they stop`() = live {
        fact(here("~bus"))
        assertEquals(mapOf("~bus" to "typing…"), repo.presenceIn("~bus").first())
        fact("""{"gone":{"context":"/dm/~bus","ship":"~bus","topic":"typing"}}""")
        assertEquals(emptyMap(), repo.presenceIn("~bus").first())
    }

    @Test
    fun `our own entry echoed back is not shown to us`() = live {
        fact(here("~zod"))
        assertEquals(emptyMap(), repo.presenceIn("~bus").first())
    }

    @Test
    fun `the most immediate thing someone is doing is what shows`() = live {
        fact(here("~bus", topic = "computing", text = "uploading an image"))
        fact(here("~bus", topic = "typing"))
        assertEquals("typing…", repo.presenceIn("~bus").first()["~bus"])
    }

    @Test
    fun `a snapshot replaces what was known`() = live {
        fact(here("~bus"))
        fact("""{"init":{"/dm/~nec":{"typing":{"~nec":{"timing":{"since":"~2026.7.10","timeout":"~s30"},"display":{"icon":null,"text":null,"blob":null}}}}}}""")
        assertEquals(emptyMap(), repo.presenceIn("~bus").first())
        assertEquals(setOf("~nec"), repo.presenceIn("~nec").first().keys)
    }
}
