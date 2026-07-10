package io.nisfeb.talon.urbit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A group invite arrives live on the %groups /gangs/updates
 * subscription as a bare `{flag: {claim, preview, invite}}` map.
 * applyEvent dispatches every SSE fact by shape, so this detector must
 * fire on a gangs fact and NOT on any of the other agents' facts —
 * a false positive would trigger a needless re-scry, a false negative
 * would drop the invite (the bug we're fixing).
 */
class GangsFactTest {

    private fun obj(s: String) = Json.parseToJsonElement(s) as JsonObject

    @Test
    fun `a gangs fact is detected`() {
        assertTrue(
            looksLikeGangsFact(
                obj("""{"~zod/hobbyists":{"claim":null,"preview":{"meta":{}},"invite":{"ship":"~zod"}}}"""),
            ),
        )
        // Multiple gangs, and a bare/empty gang (invite rescinded) still count.
        assertTrue(looksLikeGangsFact(obj("""{"~zod/a":{},"~bus/b":{"invite":{}}}""")))
    }

    @Test
    fun `no other agent's fact is mistaken for a gangs fact`() {
        // The exact shapes applyEvent routes ahead of the gangs branch.
        assertFalse(looksLikeGangsFact(obj("""{"whom":"~zod","id":"1","response":{}}""")))
        assertFalse(looksLikeGangsFact(obj("""{"nest":"chat/~zod/x","response":{}}""")))
        assertFalse(looksLikeGangsFact(obj("""{"activity":{}}""")))
        assertFalse(looksLikeGangsFact(obj("""{"page":{"kip":"~bob"}}""")))
        assertFalse(looksLikeGangsFact(obj("""{"put-entry":{}}""")))
        assertFalse(looksLikeGangsFact(obj("""{"flag":"~zod/g","r-group":{}}""")))
        assertFalse(looksLikeGangsFact(obj("""{"init":{}}""")))
        assertFalse(looksLikeGangsFact(obj("{}")), "empty is not a gangs fact")
    }

    @Test
    fun `a flag must have both the sig and the slash`() {
        assertFalse(looksLikeGangsFact(obj("""{"~zod":{}}""")), "a bare ship is not a flag")
        assertFalse(looksLikeGangsFact(obj("""{"chat/~zod/x":{}}""")), "a nest is not a flag")
        // One non-flag key disqualifies the whole payload.
        assertFalse(looksLikeGangsFact(obj("""{"~zod/a":{},"flag":"x"}""")))
    }
}
