package io.nisfeb.talon.bridge

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExtrasTest {
    @Test
    fun nowPlayingParsesGdbusText() {
        val meta = "(<{'xesam:artist': <['Rob Swire', 'deadmau5']>, 'mpris:trackid': <objectpath '/org/x/1'>, " +
            "'xesam:title': <'Ghosts \\'n\\' Stuff'>, 'mpris:length': <int64 22000000>}>,)"
        val t = NowPlaying.parse("org.mpris.MediaPlayer2.firefox.instance_1_9273", "Playing", meta)!!
        assertEquals("firefox", t.player)
        assertEquals("Rob Swire · Ghosts 'n' Stuff", t.text)
        assertNull(NowPlaying.parse("org.mpris.MediaPlayer2.brave", "Paused", "(<{'mpris:trackid': <'/x'>}>,)"))
    }

    @Test
    fun duckerSavesAndRestores() {
        val d = Ducker().apply { enabled = true; holdMs = 0 }
        val music = listOf(Pulse.Stream(999_999, "Spotify", null, Pulse.SPACE, volume = 80))
        d.tick(voice = true, music = music)
        assertTrue(d.ducking)
        assertEquals(80, d.originalVolume(music[0]))
        d.tick(voice = false, music = music)
        assertFalse(d.ducking)
        d.enabled = false
        d.tick(voice = true, music = music)
        assertFalse(d.ducking)
    }

    @Test
    fun presetsRoundTrip() {
        val f = File(System.getProperty("java.io.tmpdir"), "talon-presets-${System.nanoTime()}.json")
        val pf = PresetFile("show", listOf(Preset("show", mapOf("Spotify" to AppRoute(Pulse.BOTH, 60)), listOf("Brave"), 90, 110, true, 25)))
        f.writeText(kotlinx.serialization.json.Json.encodeToString(PresetFile.serializer(), pf))
        assertEquals(pf, kotlinx.serialization.json.Json.decodeFromString(PresetFile.serializer(), f.readText()))
        f.delete()
    }
}
