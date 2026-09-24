package io.nisfeb.talon.urbit

import org.junit.Assert.assertEquals
import org.junit.Test

class UrbitIdsTest {

    // ─── dotAtom ─────────────────────────────────────────────────

    @Test
    fun `non-numeric pass through`() {
        // Author-prefixed ids shouldn't be dotted.
        assertEquals("~sampel-palnet/12345", dotAtom("~sampel-palnet/12345"))
        assertEquals("abc", dotAtom("abc"))
    }

    // ─── undotAtom ──────────────────────────────────────────────

}
