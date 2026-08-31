package io.nisfeb.talon.urbit

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A post's author is two shapes, not one.
 *
 * Tlon widened the type to `$@(ship bot-meta)`: a person still encodes
 * as the bare string "~ship", while a bot encodes as an object with
 * its ship plus an optional nickname and avatar. Every call site here
 * read it as a string, so a bot's author parsed to null and the row
 * rendered with no name and no icon — the reported bug.
 */
class PostAuthorTest {

    private fun parse(raw: String) = Json.parseToJsonElement(raw).asAuthor()

    @Test
    fun `a person is a bare ship string`() {
        val a = parse(""""~ricsul-bilwyt"""")
        assertEquals("~ricsul-bilwyt", a?.ship)
        assertNull(a?.nickname)
        assertNull(a?.avatarUrl)
        assertTrue(a?.isBot == false)
    }

    @Test
    fun `a bot is an object and still yields its ship`() {
        // The regression itself: read as a string this is null, and the
        // author falls back to "" — no name, no icon.
        val a = parse(
            """{"ship":"~sampel-palnet","nickname":"Helper","avatar":"https://x/y.png"}""",
        )
        assertEquals("~sampel-palnet", a?.ship)
        assertEquals("Helper", a?.nickname)
        assertEquals("https://x/y.png", a?.avatarUrl)
        assertTrue(a?.isBot == true)
    }

    @Test
    fun `a bot with no profile still resolves to its ship`() {
        // Both fields are units in the Hoon, so null is the common case.
        val a = parse("""{"ship":"~sampel-palnet","nickname":null,"avatar":null}""")
        assertEquals("~sampel-palnet", a?.ship)
        assertNull(a?.nickname)
        assertNull(a?.avatarUrl)
    }

    @Test
    fun `blank strings are treated as absent, not as a blank name`() {
        // A bot that sends "" would otherwise render a blank label,
        // which looks exactly like the bug being fixed.
        val a = parse("""{"ship":"~sampel-palnet","nickname":"","avatar":""}""")
        assertEquals("~sampel-palnet", a?.ship)
        assertNull(a?.nickname)
        assertNull(a?.avatarUrl)
    }

    @Test
    fun `an object with no ship is not an author`() {
        assertNull(parse("""{"nickname":"Helper"}"""))
    }

    @Test
    fun `asAuthorShip gives identity for both shapes`() {
        assertEquals(
            "~ricsul-bilwyt",
            Json.parseToJsonElement(""""~ricsul-bilwyt"""").asAuthorShip(),
        )
        assertEquals(
            "~sampel-palnet",
            Json.parseToJsonElement("""{"ship":"~sampel-palnet"}""").asAuthorShip(),
        )
    }

    // ── collecting bots out of a whole post ───────────────────────
    //
    // A bot is never in %contacts, so its nickname and avatar only
    // ever arrive inline on posts. These are what the repo writes to
    // a contact row; miss one and that bot renders as its @p forever.

    private fun post(raw: String) = botAuthorsIn(Json.parseToJsonElement(raw))

    @Test
    fun `a bot posting at top level is collected`() {
        val a = post(
            """{"seal":{"id":"1","replies":{}},
                "essay":{"author":{"ship":"~sampel-palnet","nickname":"Helper",
                                   "avatar":"https://x/y.png"}}}""",
        )
        assertEquals(1, a.size)
        assertEquals("Helper", a.single().nickname)
    }

    @Test
    fun `a bot replying is collected too`() {
        // Replies carry their own author and are the case toEntity
        // never sees — it only gets the top-level essay.
        val a = post(
            """{"seal":{"id":"1","replies":{
                  "2":{"seal":{"id":"2"},
                       "reply-essay":{"author":{"ship":"~bot-bot","nickname":"Rep"}}}}},
                "essay":{"author":"~ricsul-bilwyt"}}""",
        )
        assertEquals(listOf("~bot-bot"), a.map { it.ship })
        assertEquals("Rep", a.single().nickname)
    }

    @Test
    fun `humans are never collected`() {
        // Otherwise every ordinary message would rewrite a contact row.
        val a = post(
            """{"seal":{"id":"1","replies":{
                  "2":{"seal":{"id":"2"},
                       "reply-essay":{"author":"~hapnyl-fotlyx"}}}},
                "essay":{"author":"~ricsul-bilwyt"}}""",
        )
        assertTrue(a.isEmpty(), "collected $a for a post with no bots")
    }

    @Test
    fun `a bot with no profile is not worth a contact row`() {
        // ship-only carries nothing ContactMap could show, so writing
        // it would churn the table for no display change.
        val a = post(
            """{"seal":{"id":"1","replies":{}},
                "essay":{"author":{"ship":"~bot-bot","nickname":null,"avatar":null}}}""",
        )
        assertTrue(a.isEmpty(), "collected $a for a bot with no name or icon")
    }

    @Test
    fun `a malformed post yields nothing rather than throwing`() {
        assertTrue(post("""{"nonsense":true}""").isEmpty())
        assertTrue(botAuthorsIn(Json.parseToJsonElement(""""a string"""")).isEmpty())
    }
}
