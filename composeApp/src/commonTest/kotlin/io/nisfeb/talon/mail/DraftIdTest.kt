package io.nisfeb.talon.mail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DraftIdTest {

    private val shape = Regex("^0v[0-9a-v]{5}\\.[0-9a-v]{5}\\.[0-9a-v]{5}$")

    @Test
    fun `an id is a well formed @uv`() {
        repeat(200) {
            val id = newDraftId()
            assertTrue(shape.matches(id), id)
        }
    }

    @Test
    fun `no id starts with a zero`() {
        // A leading zero is dropped when a @uv is rendered, so an id we
        // saved and the one the ship echoes back would not match.
        repeat(500) {
            assertTrue(newDraftId()[2] != '0', "leading zero would not round-trip")
        }
    }

    @Test
    fun `two ids differ`() {
        val ids = (1..100).map { newDraftId() }.toSet()
        assertEquals(100, ids.size)
    }

}
