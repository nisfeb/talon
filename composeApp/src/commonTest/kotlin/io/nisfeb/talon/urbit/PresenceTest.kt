package io.nisfeb.talon.urbit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * %presence wire shapes, checked against desk/sur/presence.hoon and
 * desk/lib/presence-json.hoon at tlon-apps v11.4.0. Getting `display`
 * or `disclose` wrong makes the poke silently nack, which is exactly
 * the failure mode that hid the contacts scry rename for months.
 */
class PresenceTest {

    private fun obj(s: String) = Json.parseToJsonElement(s) as JsonObject

    @Test
    fun `a DM context names the peer, a channel context is the nest`() {
        assertEquals("/dm/~bob", Presence.contextFor("~bob"))
        assertEquals("/channel/chat/~zod/general", Presence.contextFor("chat/~zod/general"))
        assertEquals("/channel/diary/~zod/notes", Presence.contextFor("diary/~zod/notes"))
        // Clubs have no context — +context-host crashes on anything
        // that isn't %dm / %channel / %group, so we must not announce.
        assertNull(Presence.contextFor("0v4.hj2ln.qjnn5"))
    }

    @Test
    fun `the set action carries every key the dejs requires`() {
        val action = Presence.setAction("/dm/~bob", "~zod")
        val set = action["set"] as JsonObject
        // `disclose` is `(as (se %p))` — an array. Empty means public.
        assertEquals(0, (set["disclose"] as kotlinx.serialization.json.JsonArray).size)
        assertEquals("~s30", set["timeout"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })

        val key = set["key"] as JsonObject
        assertEquals("/dm/~bob", key["context"].asStr())
        assertEquals("~zod", key["ship"].asStr(), "the key names the announcer, not the peer")
        assertEquals("typing", key["topic"].asStr())

        // `display` is `(ot 'icon' 'text' 'blob' ~)` — all three keys
        // are REQUIRED even though each value may be null.
        val display = set["display"] as JsonObject
        assertEquals(setOf("icon", "text", "blob"), display.keys)
    }

    @Test
    fun `the clear action is a bare key`() {
        val key = Presence.clearAction("/dm/~bob", "~zod")["clear"] as JsonObject
        assertEquals("/dm/~bob", key["context"].asStr())
        assertEquals("typing", key["topic"].asStr())
    }

    @Test
    fun `scot dr durations parse, and garbage falls back to the topic default`() {
        assertEquals(30_000L, Presence.parseDurationMs("~s30"))
        assertEquals(60_000L, Presence.parseDurationMs("~m1"))
        assertEquals(90_000L, Presence.parseDurationMs("~m1.s30"))
        assertEquals(3_600_000L, Presence.parseDurationMs("~h1"))
        assertNull(Presence.parseDurationMs(null))
        assertNull(Presence.parseDurationMs("~z9"))
        assertNull(Presence.parseDurationMs(""))

        assertEquals(30_000L, Presence.defaultTimeoutMs("typing"))
        assertEquals(60_000L, Presence.defaultTimeoutMs("computing"))
    }

    @Test
    fun `an init fact is a full snapshot of nested context-topic-ship`() {
        val update = Presence.parseResponse(
            obj(
                """{"init":{
                     "/dm/~bob":{"typing":{"~bob":{
                        "timing":{"since":"~2026.7.10","timeout":"~s30"},
                        "display":{"icon":null,"text":null,"blob":null}}}},
                     "/channel/chat/~zod/general":{"typing":{"~dev":{
                        "timing":{"since":"~2026.7.10","timeout":null},
                        "display":{"icon":null,"text":null,"blob":null}}}}}}""",
            ),
        )!!
        assertTrue(update.snapshot, "init replaces state; it does not merge")
        assertEquals(2, update.here.size)

        val bob = update.here.single { it.ship == "~bob" }
        assertEquals("/dm/~bob", bob.context)
        assertEquals(30_000L, bob.timeoutMs)

        // A null timeout means "use the agent's default for the topic".
        assertEquals(30_000L, update.here.single { it.ship == "~dev" }.timeoutMs)
    }

    @Test
    fun `a display text rides along and becomes the label`() {
        val set = Presence.setAction("/dm/~bob", "~zod", Presence.TOPIC_COMPUTING, "uploading an image")
        val disp = (set["set"] as JsonObject)["display"] as JsonObject
        assertEquals("uploading an image", disp["text"].asStr())
        // %computing asks for the minute timeout, not 30s.
        assertEquals("~m1", (set["set"] as JsonObject)["timeout"].asStr())

        val here = Presence.parseResponse(
            obj(
                """{"here":{"key":{"context":"/dm/~bob","ship":"~bob","topic":"computing"},
                           "timing":{"since":"~2026.7.10","timeout":"~m1"},
                           "display":{"icon":null,"text":"uploading an image","blob":null}}}""",
            ),
        )!!
        val e = here.here.single()
        assertEquals("uploading an image", e.text)
        assertEquals("uploading an image", e.label, "a set text wins over the topic default")
        assertEquals(60_000L, e.timeoutMs)
    }

    @Test
    fun `topic drives the label when no text was set`() {
        assertEquals("typing…", Presence.labelFor(Presence.TOPIC_TYPING, null))
        assertEquals("thinking…", Presence.labelFor(Presence.TOPIC_COMPUTING, null))
        assertEquals("active", Presence.labelFor(Presence.TOPIC_OTHER, ""))
        assertEquals("recording audio", Presence.labelFor(Presence.TOPIC_OTHER, "recording audio"))
    }

    @Test
    fun `here and gone facts carry a key`() {
        val here = Presence.parseResponse(
            obj(
                """{"here":{"key":{"context":"/dm/~bob","ship":"~bob","topic":"typing"},
                           "timing":{"since":"~2026.7.10","timeout":"~s30"},
                           "display":{"icon":null,"text":null,"blob":null}}}""",
            ),
        )!!
        assertEquals(1, here.here.size)
        assertTrue(!here.snapshot)
        assertEquals("~bob", here.here[0].ship)
        assertEquals(30_000L, here.here[0].timeoutMs)

        val gone = Presence.parseResponse(
            obj("""{"gone":{"context":"/dm/~bob","ship":"~bob","topic":"typing"}}"""),
        )!!
        assertEquals(1, gone.gone.size)
        assertEquals("/dm/~bob", gone.gone[0].context)
        assertEquals(0, gone.here.size)
    }

    @Test
    fun `an unrelated payload is not mistaken for presence`() {
        assertNull(Presence.parseResponse(obj("""{"page":{"kip":"~bob"}}""")))
        // A malformed here (no key) must not throw.
        assertNull(Presence.parseResponse(obj("""{"here":{"timing":{}}}""")))
    }
}
