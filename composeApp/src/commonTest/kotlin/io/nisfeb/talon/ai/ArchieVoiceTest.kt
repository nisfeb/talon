package io.nisfeb.talon.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArchieVoiceTest {
    private fun v(name: String, tag: String = "en-US", features: Set<String> = emptySet(), network: Boolean = false, quality: Int = 400) =
        ArchieVoice.Option(name, tag, features, network, quality)

    // Google's voices on a Pixel, the women's among them.
    private val google = listOf(
        v("en-us-x-sfg-local"), v("en-us-x-iob-local"), v("en-us-x-tpf-local"),
        v("en-us-x-iom-network", network = true), v("en-us-x-iol-local"),
        v("en-gb-x-rjs-local", "en-GB"), v("fr-fr-x-frd-local", "fr-FR"),
    )

    @Test
    fun `a man's voice that needs no network, in the owner's own region first`() {
        assertEquals("en-us-x-iol-local", ArchieVoice.pick(google, "en-US"))
        assertEquals("en-gb-x-rjs-local", ArchieVoice.pick(google, "en-GB"))
        // Offline over everything: the car may have no signal.
        assertEquals("en-gb-x-rjs-local", ArchieVoice.pick(google.filterNot { it.name == "en-us-x-iol-local" }, "en-US"))
        assertEquals("en-us-x-iom-network", ArchieVoice.pick(listOf(v("en-us-x-sfg-local"), v("en-us-x-iom-network", network = true)), "en-US"))
        // The better of two.
        assertEquals("b", ArchieVoice.pick(listOf(v("a", features = setOf("male"), quality = 300), v("b", features = setOf("male"), quality = 500)), "en-US"))
    }

    @Test
    fun `what engines say of a man's voice, and no woman's is taken for one`() {
        assertTrue(ArchieVoice.isMale(v("x", features = setOf("gender=male"))))
        assertTrue(ArchieVoice.isMale(v("Bdl")) && ArchieVoice.isMale(v("Alan")), "RHVoice's men")
        assertTrue(ArchieVoice.isMale(v("en-us-male-2")))
        assertFalse(ArchieVoice.isMale(v("en-us-female-1")))
        assertFalse(ArchieVoice.isMale(v("x", features = setOf("gender=female"))))
        assertNull(ArchieVoice.pick(listOf(v("en-us-x-sfg-local"), v("Slt")), "en-US"), "no man's voice: the engine's own, at Archie's pitch")
        assertNull(ArchieVoice.pick(google, "de-DE"), "never a voice in another language")
    }
}
