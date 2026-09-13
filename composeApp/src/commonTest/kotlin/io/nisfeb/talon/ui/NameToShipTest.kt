package io.nisfeb.talon.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NameToShipTest {

    private val comet = "~doznec-binwes-samper-siglet--fidpen-sogdur-wacser-wissun"
    private val twinA = "~binfet-harmeb-tirmut-raclug--linnec-tobmed-hacdeg-dartul"
    private val twinB = "~binwed-watmeg-tadrel-modreg--nimfen-bidtus-finrus-dablep"
    private val planet = "~ricsul-bilwyt"

    private fun one(r: NameToShip.Result) = (r as NameToShip.Result.One).ship

    @Test
    fun `an at-p works with or without its sig`() {
        assertEquals(planet, one(NameToShip.resolve(planet)))
        assertEquals(planet, one(NameToShip.resolve("ricsul-bilwyt")))
        assertEquals(comet, one(NameToShip.resolve(comet)))
    }

    @Test
    fun `the full word name works with nobody known`() {
        // It decodes on its own, so an invite needs no prior contact.
        val nym = Mnemonym.forShip(comet)!!
        assertEquals(comet, one(NameToShip.resolve(nym)))
        assertEquals(comet, one(NameToShip.resolve(nym.removePrefix(".."))))
    }

    @Test
    fun `the short name works for somebody already known`() {
        assertEquals(comet, one(NameToShip.resolve("..admire...attune", known = listOf(comet))))
    }

    @Test
    fun `a short name nobody matches resolves to nothing`() {
        assertEquals(NameToShip.Result.None, NameToShip.resolve("..admire...attune"))
    }

    @Test
    fun `a nickname works`() {
        val r = NameToShip.resolve("Sam", known = listOf(comet, planet)) { s ->
            if (s == comet) "Sam" else null
        }
        assertEquals(comet, one(r))
    }

    @Test
    fun `an ambiguous short name asks rather than guesses`() {
        // These two really do abridge the same. Picking one would
        // start a conversation with the wrong person.
        val r = NameToShip.resolve("..absolves...delay", known = listOf(twinA, twinB))
        assertTrue(r is NameToShip.Result.Several)
        assertEquals(setOf(twinA, twinB), (r as NameToShip.Result.Several).ships.toSet())
        assertTrue(NameToShip.hint(r, "..absolves...delay")!!.contains("2 ships"))
    }

    @Test
    fun `a name that is only shaped like a ship is refused here`() {
        // ~wisdom matches the shape regex and is no ship: `dom` is no
        // suffix. It used to reach the ship, which nacked with a
        // message about invites rather than about the name.
        assertEquals(NameToShip.Result.None, NameToShip.resolve("~wisdom"))
        assertEquals(NameToShip.Result.None, NameToShip.resolve("~zzzzzz"))
        // ...while ~wisper, which the old comment called a non-ship,
        // is a real star and still resolves.
        assertEquals("~wisper", one(NameToShip.resolve("~wisper")))
    }

    @Test
    fun `nonsense resolves to nothing and says nothing`() {
        assertEquals(NameToShip.Result.None, NameToShip.resolve(""))
        assertEquals(NameToShip.Result.None, NameToShip.resolve("   "))
        assertEquals(NameToShip.Result.None, NameToShip.resolve("who?"))
        // An empty box says nothing; a typed name that matched
        // nobody says so, rather than letting the ship answer.
        assertEquals(null, NameToShip.hint(NameToShip.Result.None, ""))
        assertEquals("No ship goes by that name.", NameToShip.hint(NameToShip.Result.None, "who?"))
    }

    @Test
    fun `a mistyped full name usually fails, but not always`() {
        // The checksum is four bits, so it catches fifteen wrong words
        // in sixteen. This is the sixteenth: changing one word of this
        // comet's name yields a different, entirely valid comet.
        //
        // Pinned rather than wished away, because it is the reason the
        // field shows who resolved before anything is sent. A typo
        // here does not fail loudly -- it quietly names a stranger.
        val words = Mnemonym.forShip(comet)!!.removePrefix("..").split('.').toMutableList()
        words[5] = "abate"
        val slip = NameToShip.resolve(".." + words.joinToString("."))
        assertEquals(
            "~doznec-binwes-samper-siglet--dozpen-sogdur-wacser-wissun",
            one(slip),
        )

        // Most single-word slips do fail, which is the checksum working.
        var caught = 0
        for (i in words.indices) {
            val w = Mnemonym.forShip(comet)!!.removePrefix("..").split('.').toMutableList()
            w[i] = if (w[i] == "abate") "abduct" else "abate"
            if (NameToShip.resolve(".." + w.joinToString(".")) == NameToShip.Result.None) caught++
        }
        assertTrue(caught >= words.size - 2, "caught $caught of ${words.size}")
    }
}
