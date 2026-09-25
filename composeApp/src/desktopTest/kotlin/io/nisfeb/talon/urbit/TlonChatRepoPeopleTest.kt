package io.nisfeb.talon.urbit

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.ContactEntity
import io.nisfeb.talon.data.DmInviteEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * People and places: contacts added, renamed and removed, DM requests
 * answered, groups joined and posts edited. Each is checked by what it
 * sends a [FakeShip] and what it leaves here.
 */
class TlonChatRepoPeopleTest {
    private val db: AppDatabase = createTempDirectory(prefix = "talon-people-").toFile().let { dir ->
        Room.databaseBuilder<AppDatabase>(File(dir, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
    }
    private val ship = FakeShip("~zod")
    private val repo = TlonChatRepo(db).apply { attachForTest(ship.channel, "~zod") }

    @AfterTest
    fun close() = db.close()

    private fun live(body: suspend CoroutineScope.() -> Unit) = runBlocking<Unit> {
        val events = ship.channel.events().launchIn(this)
        try { body() } finally { events.cancel() }
    }

    private fun JsonElement.at(vararg keys: String): JsonElement = keys.fold(this) { e, k -> e.jsonObject[k] ?: error("no $k in $e") }

    // ─── contacts ─────────────────────────────────────────────────

    @Test
    fun `the book is what the ship says, and a book it could not send is kept`() = live {
        ship.scries["contacts/v1/book"] = """{"~bus":[{"nickname":{"type":"text","value":"Bus"}},{}]}"""
        repo.bootstrapContacts(ship.channel)
        assertEquals(setOf("~bus"), repo.bookContacts.value)
        ship.scries.remove("contacts/v1/book")
        repo.bootstrapContacts(ship.channel)
        assertEquals(setOf("~bus"), repo.bookContacts.value, "no answer is not an empty book")
        ship.scries["contacts/v1/book"] = "{}"
        repo.bootstrapContacts(ship.channel)
        assertEquals(emptySet(), repo.bookContacts.value, "an empty book is")
    }

    @Test
    fun `adding a contact meets them, pages them, and keeps what we knew`() = live {
        db.contacts().upsert(ContactEntity("~bus", null, "a bus", null, status = "on the road", statusUpdatedMs = 1))
        repo.addContact("~bus", nickname = "  Bus  ")
        val (meet, page) = ship.pokesTo("contacts")
        assertEquals("~bus", meet.json.at("meet").jsonArray.single().jsonPrimitive.content)
        assertEquals("Bus", page.json.at("page", "contact", "nickname", "value").jsonPrimitive.content)
        assertTrue("~bus" in repo.bookContacts.value)
        val row = db.contacts().get("~bus")!!
        assertEquals(Triple("Bus", "a bus", "on the road"), Triple(row.nickname, row.bio, row.status))
    }

    @Test
    fun `adding without a name keeps the name we had`() = live {
        db.contacts().upsert(ContactEntity("~bus", "Old Bus", null, null))
        repo.addContact("~bus", nickname = "   ")
        assertEquals("Old Bus", db.contacts().get("~bus")?.nickname)
        assertTrue("nickname" !in ship.pokesTo("contacts").last().json.at("page", "contact").jsonObject)
    }

    @Test
    fun `a refused add leaves the book as it was`() = live {
        ship.refuse = { if (it.app == "contacts") "nope" else null }
        assertFailsWith<PokeNacked> { repo.addContact("~bus") }
        assertTrue("~bus" !in repo.bookContacts.value)
    }

    @Test
    fun `removing a contact wipes them from the book`() = live {
        repo.addContact("~bus")
        repo.removeContact("~bus")
        assertEquals("~bus", ship.pokesTo("contacts").last().json.at("wipe").jsonArray.single().jsonPrimitive.content)
        assertTrue("~bus" !in repo.bookContacts.value)
        assertNotNull(db.contacts().get("~bus"), "the directory row stays")
    }

    @Test
    fun `a pet name shows here at once and keeps the rest`() = live {
        db.contacts().upsert(ContactEntity("~bus", "Bus", "a bus", null))
        repo.setPetName("~bus", "Big Blue")
        assertEquals("Big Blue" to "a bus", db.contacts().get("~bus")!!.let { it.nickname to it.bio })
        assertTrue("Big Blue" in ship.pokesTo("contacts").single().json.toString())
    }

    @Test
    fun `a name that is not a ship is refused before anything is sent`() = live {
        assertFailsWith<IllegalArgumentException> { repo.addContact("bus") }
        assertFailsWith<IllegalArgumentException> { repo.setPetName("bus", "x") }
        assertTrue(ship.pokes.isEmpty())
    }

    // ─── DM requests, groups ──────────────────────────────────────

    @Test
    fun `a DM request is accepted or declined, and leaves the list either way`() = live {
        db.dmInvites().upsertAll(listOf(DmInviteEntity("~bus", 1), DmInviteEntity("~nec", 2)))
        repo.acceptDmInvite("~bus")
        repo.declineDmInvite("~nec")
        val (yes, no) = ship.pokesTo("chat")
        assertEquals("chat-dm-rsvp", yes.mark)
        assertEquals(listOf("~bus" to "true", "~nec" to "false"), listOf(yes, no).map { it.json.at("ship").jsonPrimitive.content to it.json.at("ok").jsonPrimitive.content })
        assertTrue(db.dmInvites().allShips().isEmpty())
    }

    @Test
    fun `a joined group arrives in the list once the ship has it`() = live {
        ship.scries["groups/v2/groups"] = """{"~bus/garden":{"meta":{"title":"The Garden"},"channels":{}}}"""
        repo.joinGroup("~bus/garden")
        val join = ship.pokesTo("groups").single()
        assertEquals("group-join", join.mark)
        assertEquals("~bus/garden", join.json.at("flag").jsonPrimitive.content)
        withTimeout(10_000) { while (db.groups().getGroup("~bus/garden") == null) delay(100) }
        assertEquals("The Garden", db.groups().getGroup("~bus/garden")?.title)
    }

    // ─── edits ────────────────────────────────────────────────────

    @Test
    fun `an edit keeps the post's time and its quote, and dots its id`() = live {
        val quoted = """[{"block":{"cite":{"chan":{"nest":"chat/~bus/general","where":"/msg/170.141.184.500"}}}},{"inline":["old words"]}]"""
        repo.edit("chat/~bus/general", "170141184506", "new words", originalSentMs = 1_234, originalContentJson = quoted)
        val edit = ship.pokesTo("channels").single().json.at("channel", "action", "post", "edit")
        assertEquals("170.141.184.506", edit.at("id").jsonPrimitive.content)
        assertEquals("1234", edit.at("essay", "sent").jsonPrimitive.content)
        val content = edit.at("essay", "content").toString()
        assertTrue("cite" in content && "new words" in content && "old words" !in content, content)
    }

    @Test
    fun `an edited reply goes under its parent`() = live {
        repo.edit("chat/~bus/general", "170141184507", "fixed", originalSentMs = 5, parentId = "170141184506")
        val reply = ship.pokesTo("channels").single().json.at("channel", "action", "post", "reply")
        assertEquals("170.141.184.506", reply.at("id").jsonPrimitive.content)
        assertEquals("170.141.184.507", reply.at("action", "edit", "id").jsonPrimitive.content)
    }

    @Test
    fun `a DM cannot be edited, and nothing is sent`() = live {
        assertFailsWith<IllegalStateException> { repo.edit("~bus", "~zod/170141184506", "x", originalSentMs = 1) }
        assertTrue(ship.pokes.isEmpty())
    }

    // ─── our own profile ──────────────────────────────────────────

    @Test
    fun `a profile edit sends only what was given, and shows here at once`() = live {
        db.contacts().upsert(ContactEntity("~zod", "Zod", "a bio", null))
        repo.updateProfile(nickname = "Zed", color = "#ff0000")
        val self = ship.pokesTo("contacts").single().json.at("self").jsonObject
        assertEquals(setOf("nickname", "color"), self.keys)
        assertEquals("Zed", self.at("nickname", "value").jsonPrimitive.content)
        assertEquals("ff.0000", self.at("color", "value").jsonPrimitive.content)
        val row = db.contacts().get("~zod")!!
        assertEquals(Triple("Zed", "a bio", "#ff0000"), Triple(row.nickname, row.bio, row.color))
    }

    @Test
    fun `a field emptied is emptied here too`() = live {
        db.contacts().upsert(ContactEntity("~zod", "Zod", "a bio", null, status = "out"))
        repo.updateProfile(bio = "", status = "")
        val self = ship.pokesTo("contacts").single().json.at("self")
        assertEquals("", self.at("bio", "value").jsonPrimitive.content)
        val row = db.contacts().get("~zod")!!
        assertEquals(Triple("Zod", null, null), Triple(row.nickname, row.bio, row.status))
    }

    @Test
    fun `an edit the ship refuses puts back what it still has`() = live {
        db.contacts().upsert(ContactEntity("~zod", "Zod", "a bio", null))
        ship.refuse = { if (it.app == "contacts") "no" else null }
        assertFailsWith<PokeNacked> { repo.updateProfile(nickname = "Zed") }
        assertEquals("Zod", db.contacts().get("~zod")?.nickname)
    }

    @Test
    fun `nothing given sends nothing`() = live {
        repo.updateProfile()
        assertTrue(ship.pokes.isEmpty())
    }
}
