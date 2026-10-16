package io.nisfeb.talon.ai

import io.nisfeb.talon.orrery.DecideSettings
import io.nisfeb.talon.orrery.under
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What of the profile travels under Armillary-only: the switches, and
 * nothing else. The switches decide the decision model and Orrery for
 * every device.
 */
class AiProfileSyncTest {
    private val base = paidSettings()

    @Test
    fun `the switches go out and come back as they were, and only they`() {
        val p = base.profile().copy(
            features = base.profile().features + (AiFeature.CatchUp to FeatureSetting(on = false)),
            jev = true,
            orrery = true,
        )
        val wire = p.switches()
        assertEquals(setOf("CatchUp", "Assistant", "jev", "orrery"), wire.keys)
        assertEquals(JsonPrimitive(false), wire["CatchUp"])
        val back = base.profile().withSwitches(wire)
        assertFalse(back.isOn(AiFeature.CatchUp))
        assertTrue(back.isOn(AiFeature.Assistant))
        assertEquals(true, back.jev)
        assertEquals(true, back.orrery)
        assertEquals(base.profile().providers, back.providers, "a switch moves no provider")
    }

    @Test
    fun `a switch arriving for a feature with no setting yet makes one`() {
        val wire = kotlinx.serialization.json.buildJsonObject { put("CatchUp", JsonPrimitive(true)); put("Assistant", JsonPrimitive(false)) }
        val p = AiProfile().withSwitches(wire)
        assertTrue(p.isOn(AiFeature.CatchUp))
        assertFalse(p.isOn(AiFeature.Assistant))
        val nonsense = kotlinx.serialization.json.buildJsonObject { put("CatchUp", JsonPrimitive("maybe")) }
        assertEquals(base.profile().features, base.profile().withSwitches(nonsense).features, "a value that is not a boolean moves nothing")
    }

    @Test
    fun `once a profile is saved its switch decides jev, before it the old ones do`() {
        val ds = DecideSettings(on = true, gate = false, relevance = false)
        val plain = base.copy(savedProfile = null)
        assertEquals(ds, ds.under(plain))
        val jevOn = base.copy(savedProfile = base.profile().copy(jev = true))
        assertEquals(ds.copy(gate = true, relevance = true), ds.under(jevOn), "one switch, all three")
        assertEquals(ds, ds.under(base), "never flipped: this install's own")
        assertFalse(ds.under(base.copy(savedProfile = base.profile().copy(jev = false))).on)
    }

    @Test
    fun `orrery is on only when turned on`() {
        assertFalse(base.orreryOn(), "never set")
        assertFalse(base.copy(savedProfile = base.profile().copy(orrery = false)).orreryOn())
        assertTrue(base.copy(savedProfile = base.profile().copy(orrery = true)).orreryOn())
    }
}
