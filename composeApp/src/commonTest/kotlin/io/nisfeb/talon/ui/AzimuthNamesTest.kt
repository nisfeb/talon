package io.nisfeb.talon.ui

import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AzimuthNamesTest {

    private val planet = "~ricsul-bilwyt"
    private val moon = "~sampel-palnet-sampel-palnet"
    private val comet = "~doznec-binwes-samper-siglet--fidpen-sogdur-wacser-wissun"

    @AfterTest fun clean() { AzimuthNames.reset(); AzimuthNames.enabled.value = false }

    /** Answers a fixed fingerprint for anything asked. */
    private fun rpc(fig: ByteArray?, seen: MutableList<String> = mutableListOf()) =
        object : AzimuthRpc {
            override suspend fun fingerprint(ship: String): ByteArray? {
                seen += ship
                return fig
            }
        }

    @Test
    fun `it is off until somebody asks for it`() {
        // Turning it on has the client look up every planet it shows,
        // which is not something to start doing unasked.
        assertFalse(AzimuthNames.enabled.value)
        assertEquals(planet, ContactMap().displayName(planet))
    }

    @Test
    fun `only planets and moons are worth looking up`() {
        assertTrue(AzimuthNames.wantsLookup(planet))
        assertTrue(AzimuthNames.wantsLookup(moon))
        // A comet names itself and a star has nothing to name.
        assertFalse(AzimuthNames.wantsLookup(comet))
        assertFalse(AzimuthNames.wantsLookup("~marzod"))
        assertFalse(AzimuthNames.wantsLookup("~zod"))
        assertFalse(AzimuthNames.wantsLookup("chat/~zod/general"))
    }

    @Test
    fun `a looked-up planet gets the same shape of name as a comet`() = runTest {
        val fig = ByteArray(16) { (it + 1).toByte() }
        AzimuthNames.warm(listOf(planet), rpc(fig))
        val name = AzimuthNames.nameFor(planet)
        assertEquals(Mnemonym.displayFingerprint(fig), name)
        assertTrue(name!!.startsWith(".."), "untweaked, like any unproven identity")
    }

    @Test
    fun `a ship the host cannot answer for keeps its at-p`() = runTest {
        AzimuthNames.warm(listOf(planet), rpc(null))
        assertNull(AzimuthNames.nameFor(planet))
        assertTrue(AzimuthNames.known(planet), "asked and answered: do not ask again")
    }

    @Test
    fun `a ship is only asked once`() = runTest {
        val seen = mutableListOf<String>()
        val r = rpc(ByteArray(16), seen)
        AzimuthNames.warm(listOf(planet, planet), r)
        AzimuthNames.warm(listOf(planet), r)
        assertEquals(listOf(planet), seen)
    }

    @Test
    fun `nothing is fetched for ships that name themselves`() = runTest {
        val seen = mutableListOf<String>()
        AzimuthNames.warm(listOf(comet, "~zod", "~marzod"), rpc(ByteArray(16), seen))
        assertEquals(emptyList(), seen)
    }

    @Test
    fun `the generation only moves when an answer changes a name`() = runTest {
        val before = AzimuthNames.generation.value
        AzimuthNames.warm(listOf(planet), rpc(null))
        assertEquals(before, AzimuthNames.generation.value, "a miss changes nothing on screen")
        AzimuthNames.warm(listOf(moon), rpc(ByteArray(16) { 9 }))
        assertTrue(AzimuthNames.generation.value > before, "a hit has to redraw the rows")
    }

    @Test
    fun `the setting gates non-comets and never comets`() = runTest {
        val fig = ByteArray(16) { (it + 1).toByte() }
        AzimuthNames.warm(listOf(planet), rpc(fig))
        val map = { on: Boolean -> ContactMap(nonCometNames = on) }
        assertEquals(AzimuthNames.nameFor(planet), map(true).displayName(planet))
        assertEquals(planet, map(false).displayName(planet), "off means the raw @p")
        // A comet's name is its own and is never gated.
        assertEquals(Mnemonym.display(comet), map(false).displayName(comet))
    }
}
