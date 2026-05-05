package io.nisfeb.talon.notify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LastOpenChatStoreTest {
    @Test
    fun `set and read round-trips`() {
        val s = InMemoryLastOpenChatStore()
        s.set("~sampel", "~friend")
        assertEquals("~friend", s.state.value["~sampel"])
    }

    @Test
    fun `set is per-patp - other patps unaffected`() {
        val s = InMemoryLastOpenChatStore()
        s.set("~sampel", "~friend")
        s.set("~other", "~someone")
        assertEquals("~friend", s.state.value["~sampel"])
        assertEquals("~someone", s.state.value["~other"])
    }

    @Test
    fun `clear removes the entry for a patp`() {
        val s = InMemoryLastOpenChatStore()
        s.set("~sampel", "~friend")
        s.clear("~sampel")
        assertNull(s.state.value["~sampel"])
    }

    @Test
    fun `set with same value is a no-op (no flow emission storm)`() {
        val s = InMemoryLastOpenChatStore()
        s.set("~sampel", "~friend")
        val before = s.state.value
        s.set("~sampel", "~friend")
        assertEquals(before, s.state.value)
    }

    @Test
    fun `initial map seeds the flow`() {
        val s = InMemoryLastOpenChatStore(initial = mapOf("~a" to "~x"))
        assertEquals("~x", s.state.value["~a"])
    }
}
