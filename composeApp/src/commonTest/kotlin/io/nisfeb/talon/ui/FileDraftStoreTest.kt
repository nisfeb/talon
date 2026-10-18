package io.nisfeb.talon.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Desktop kept drafts in memory, and lost every one on quit or an update.
 * The file-per-ship store keeps them for the next start, and per ship.
 */
class FileDraftStoreTest {
    private val files = mutableMapOf<String, String>()
    private var active: String? = "~zod"
    private fun store() = FileDraftStore({ active }, read = { files[it] }, write = { name, text -> files[name] = text })

    @Test
    fun `a draft outlives the store, per ship, and a blank one is gone`() {
        val first = store()
        first.save("~bus", "half a thought")
        first.save("chat/~zod/general", "x")
        first.save("chat/~zod/general", "  ")
        active = "~nec"
        first.save("~bus", "another ship's")
        active = "~zod"
        val next = store()
        assertEquals("half a thought", next.load("~bus"))
        assertEquals("", next.load("chat/~zod/general"))
        assertEquals(mapOf("~bus" to "half a thought"), next.state.value, "the list's previews, for the active ship only")
        active = "~nec"
        assertEquals("another ship's", next.load("~bus"))
        next.clear("~bus")
        assertEquals("", store().load("~bus"))
        assertEquals(setOf("drafts-zod.json", "drafts-nec.json"), files.keys)
    }

    // A composer saves on dispose, a frame after a ship switch: its handle
    // keeps the ship it was built under, and the list's previews stay the
    // new ship's.
    @Test
    fun `a bound handle saves to its own ship after a switch, and the previews follow the active one`() {
        val s = store()
        val zod = s.bound()
        s.save("~bus", "on zod")
        assertEquals(mapOf("~bus" to "on zod"), s.state.value)
        active = "~nec"
        assertEquals("", s.load("~bus"))
        assertEquals(emptyMap(), s.state.value)
        zod.save("~bus", "still zod")
        zod.save("~dev", "zod too")
        zod.clear("~dev")
        assertEquals(emptyMap(), s.state.value, "another ship's save is not this ship's preview")
        assertEquals("still zod", zod.load("~bus"))
        s.save("~bus", "on nec")
        assertEquals(mapOf("~bus" to "on nec"), s.state.value)
        s.clear("~bus")
        assertEquals(emptyMap(), s.state.value)
        active = "~zod"
        assertEquals("still zod", store().load("~bus"))
        assertEquals("", store().load("~dev"))
    }

    @Test
    fun `no ship signed in keeps nothing, and an unreadable file is no drafts`() {
        active = null
        store().save("~bus", "lost")
        store().clear("~bus")
        assertTrue(files.isEmpty())
        assertEquals("", store().load("~bus"))
        files[draftsFileName("~zod")] = "not json"
        active = "~zod"
        assertEquals("", store().load("~bus"))
    }

    // Desktop and iOS: the list's "Draft:" previews were empty after a
    // launch and the last ship's after a switch, until a composer loaded.
    @Test
    fun `the previews are the active ship's from launch and after a switch`() {
        files[draftsFileName("~zod")] = """{"~bus":"zod's"}"""
        files[draftsFileName("~nec")] = """{"~dev":"nec's"}"""
        val s = store()
        assertEquals(emptyMap(), s.state.value)
        s.showActiveShip()
        assertEquals(mapOf("~bus" to "zod's"), s.state.value)
        active = "~nec"
        s.showActiveShip()
        assertEquals(mapOf("~dev" to "nec's"), s.state.value)
        active = null
        s.showActiveShip()
        assertEquals(emptyMap(), s.state.value)
    }

    // Erasing a ship deleted its file, but the store kept its drafts in
    // memory, and they came back when the ship was added again.
    @Test
    fun `an erased ship's drafts do not come back in the same session`() {
        val s = store()
        val zod = s.bound()
        zod.save("~bus", "half typed")
        active = "~nec"
        files.remove(draftsFileName("~zod"))
        assertEquals("half typed", zod.load("~bus"), "held until the erase is done")
        s.forget("~zod")
        active = "~zod"
        assertEquals("", s.load("~bus"))
        assertEquals(emptyMap(), s.state.value)
        s.save("~dev", "new")
        s.forget("~zod")
        assertEquals(emptyMap(), s.state.value, "the active ship forgotten shows none")
    }
}
