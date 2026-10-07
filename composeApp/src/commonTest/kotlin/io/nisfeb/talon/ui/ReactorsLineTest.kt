package io.nisfeb.talon.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class ReactorsLineTest {
    private val names = mapOf("~nec" to "Alice", "~bus" to "Bob", "~wes" to "Wes")
    private fun line(vararg a: String, us: String? = "~zod") = reactorsLine(a.toList(), us) { names[it] ?: it }

    @Test
    fun owner_first_as_you_then_the_rest_by_name() {
        assertEquals("Alice", line("~nec"))
        assertEquals("You", line("~zod"))
        assertEquals("You and Alice", line("~nec", "~zod"))
        assertEquals("You, Alice and Bob", line("~nec", "~bus", "~zod"))
        assertEquals("Alice and Bob", line("~nec", "~bus", "~nec"), "one name per person")
    }

    @Test
    fun a_long_list_ends_with_how_many_more() {
        val many = (1..13).map { "~s$it" }.toTypedArray()
        assertEquals((1..10).joinToString(", ") { "~s$it" } + " and 3 more", line(*many))
    }
}
