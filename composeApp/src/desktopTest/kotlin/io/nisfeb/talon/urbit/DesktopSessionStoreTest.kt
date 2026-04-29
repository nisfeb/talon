package io.nisfeb.talon.urbit

import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SessionStore is what keeps a user signed in across restarts.
 * The interesting behaviors are around the active-ship pointer:
 * removing the active ship promotes the next remaining one (rather
 * than leaving the user logged out into a confusing empty state),
 * and corrupt JSON falls back to "no sessions" instead of crashing
 * the app.
 */
class DesktopSessionStoreTest {
    private lateinit var tmpDir: File
    private lateinit var file: File

    @Before
    fun setUp() {
        tmpDir = createTempDirectory(prefix = "talon-sessions-test-").toFile()
        file = File(tmpDir, "sessions.json")
    }

    @After
    fun tearDown() {
        tmpDir.deleteRecursively()
    }

    private fun session(ship: String, host: String = "https://example.com") =
        SavedSession(
            shipUrl = host,
            ship = ship,
            cookieName = "urbauth-$ship",
            cookieValue = "0v.opaque",
            cookieDomain = "example.com",
        )

    @Test
    fun `empty when file does not exist`() {
        val store = DesktopSessionStore(file)
        assertTrue(store.all().isEmpty())
        assertNull(store.active())
        assertNull(store.activeShip())
    }

    @Test
    fun `save persists across instances`() {
        DesktopSessionStore(file).save(session("~zod"))
        val reloaded = DesktopSessionStore(file)
        assertEquals(1, reloaded.all().size)
        assertEquals("~zod", reloaded.all()[0].ship)
    }

    @Test
    fun `save with makeActive true sets the active ship`() {
        val store = DesktopSessionStore(file)
        store.save(session("~zod"), makeActive = true)
        assertEquals("~zod", store.activeShip())
        assertEquals("~zod", store.active()?.ship)
    }

    @Test
    fun `save with makeActive false leaves active untouched`() {
        val store = DesktopSessionStore(file)
        store.save(session("~zod"), makeActive = true)
        store.save(session("~bus"), makeActive = false)
        assertEquals("~zod", store.activeShip())
    }

    @Test
    fun `save replaces an existing entry for the same ship`() {
        val store = DesktopSessionStore(file)
        store.save(session("~zod", host = "https://old.example"), makeActive = true)
        store.save(session("~zod", host = "https://new.example"), makeActive = true)
        assertEquals(1, store.all().size)
        assertEquals("https://new.example", store.all()[0].shipUrl)
    }

    @Test
    fun `setActive does nothing if the ship is unknown`() {
        val store = DesktopSessionStore(file)
        store.save(session("~zod"), makeActive = true)
        store.setActive("~bus")  // not saved
        assertEquals("~zod", store.activeShip(), "unknown ship must not become active")
    }

    @Test
    fun `setActive switches between saved ships`() {
        val store = DesktopSessionStore(file)
        store.save(session("~zod"), makeActive = true)
        store.save(session("~bus"), makeActive = false)
        store.setActive("~bus")
        assertEquals("~bus", store.activeShip())
    }

    @Test
    fun `remove drops the entry but leaves other sessions intact`() {
        val store = DesktopSessionStore(file)
        store.save(session("~zod"), makeActive = true)
        store.save(session("~bus"), makeActive = false)
        store.remove("~zod")
        assertEquals(listOf("~bus"), store.all().map { it.ship })
    }

    @Test
    fun `removing the active ship promotes the first remaining one`() {
        // Critical UX guarantee: removing the active ship while others
        // exist should NOT silently log the user out — it should switch
        // to a remaining ship.
        val store = DesktopSessionStore(file)
        store.save(session("~zod"), makeActive = true)
        store.save(session("~bus"), makeActive = false)
        store.remove("~zod")
        assertEquals("~bus", store.activeShip())
    }

    @Test
    fun `removing the only ship clears the active pointer`() {
        val store = DesktopSessionStore(file)
        store.save(session("~zod"), makeActive = true)
        store.remove("~zod")
        assertTrue(store.all().isEmpty())
        assertNull(store.activeShip(),
            "removing the last ship must null the active pointer")
    }

    @Test
    fun `removing an unknown ship is a no-op for active pointer`() {
        val store = DesktopSessionStore(file)
        store.save(session("~zod"), makeActive = true)
        store.remove("~bus")  // not present
        assertEquals("~zod", store.activeShip())
    }

    @Test
    fun `clearAll wipes everything`() {
        val store = DesktopSessionStore(file)
        store.save(session("~zod"), makeActive = true)
        store.save(session("~bus"), makeActive = false)
        store.clearAll()
        assertTrue(store.all().isEmpty())
        assertNull(store.activeShip())
    }

    @Test
    fun `corrupt JSON falls back to empty store`() {
        file.writeText("{ this is not valid json")
        val store = DesktopSessionStore(file)
        assertTrue(store.all().isEmpty(),
            "corrupt sessions.json must not crash; degrade gracefully")
        assertNull(store.activeShip())
    }

    @Test
    fun `all returns ships sorted by patp`() {
        val store = DesktopSessionStore(file)
        store.save(session("~zod"), makeActive = false)
        store.save(session("~bus"), makeActive = false)
        store.save(session("~mister-botter"), makeActive = false)
        assertEquals(
            listOf("~bus", "~mister-botter", "~zod"),
            store.all().map { it.ship },
        )
    }

    @Test
    fun `active returns the SavedSession matching the active pointer`() {
        val store = DesktopSessionStore(file)
        val zod = session("~zod", host = "https://zod.example")
        store.save(zod, makeActive = true)
        store.save(session("~bus"), makeActive = false)
        val active = store.active()
        assertNotNull(active)
        assertEquals("~zod", active.ship)
        assertEquals("https://zod.example", active.shipUrl)
    }

    @Test
    fun `atomic move leaves no tmp file after persist`() {
        DesktopSessionStore(file).save(session("~zod"))
        val tmp = File(tmpDir, "sessions.json.tmp")
        assertFalse(tmp.exists())
    }

    @Test
    fun `unknown extra fields in the JSON are tolerated on load`() {
        // ignoreUnknownKeys=true — a future Talon adding fields to the
        // top-level blob (e.g. version) should be loadable by an older
        // Talon without crashing.
        file.writeText(
            """{"sessions":[],"activeShip":null,"futureFlag":42}"""
        )
        val store = DesktopSessionStore(file)
        assertTrue(store.all().isEmpty())
    }
}
