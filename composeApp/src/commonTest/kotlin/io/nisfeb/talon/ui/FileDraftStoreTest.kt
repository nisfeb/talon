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
}
