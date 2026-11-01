package io.nisfeb.talon.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals

/** A dojo line is taken once dojo prints it back: the panel's "waiting" goes then. */
class DojoTakenTest {
    @Test
    fun countsEachTimeALineWasTaken() {
        val cmd = "(add 2 2)"
        assertEquals(0, takenCount("~zod:dojo> ", cmd))
        assertEquals(2, takenCount("~zod:dojo> (add 2 2)\n> (add 2 2)\n4\n", cmd), "the redrawn line and the print-back")
        assertEquals(4, takenCount("~zod:dojo> (add 2 2)\n> (add 2 2)\n4\n~zod:dojo> (add 2 2)\n> (add 2 2)\n4\n", cmd))
        assertEquals(0, takenCount("~zod:dojo> (add 2 3)\n> (add 2 3)\n5\n", cmd), "another line is not this one")
    }
}
