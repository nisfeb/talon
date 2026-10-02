package io.nisfeb.talon.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RecentByKeyTest {
    // Every chat opened kept all its rows for the life of the process.
    @Test
    fun `only the last few are kept, one put again counting as newest`() {
        val recent = RecentByKey<Int>(keep = 3)
        listOf("a", "b", "c").forEachIndexed { i, k -> recent.put(k, i) }
        recent.put("a", 9) // put again: newest
        recent.put("d", 3)
        assertNull(recent["b"], "put longest ago")
        assertEquals(9, recent["a"])
        assertEquals(listOf(2, 3), listOf(recent["c"], recent["d"]))
    }
}
