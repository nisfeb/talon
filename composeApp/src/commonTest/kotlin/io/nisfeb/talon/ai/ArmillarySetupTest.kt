package io.nisfeb.talon.ai

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** When this device takes an Armillary row of its own: a profile from elsewhere that runs on Armillary. */
class ArmillarySetupTest {
    private val armillary = armillaryRow()
    private val device = AiProvider(DEVICE_PROVIDER, ProviderKind.ThisDevice, "On this device")
    private val openRouter = AiProvider("main", ProviderKind.OpenRouter, "OpenRouter", apiKey = "sk-or")
    private val ours = ModelRef(ARMILLARY_PROVIDER, "big/tools")

    @Test
    fun `a profile running on armillary wants a row on a device that has none`() {
        val synced = AiProfile(providers = listOf(device), defaultModel = ours)
        assertTrue(synced.wantsArmillaryRow())
        assertFalse(synced.copy(providers = listOf(device, armillary)).wantsArmillaryRow(), "it has one")
        assertFalse(AiProfile(providers = listOf(device, openRouter), defaultModel = ModelRef("main", "")).wantsArmillaryRow(), "nothing runs on armillary")
        assertTrue(AiProfile(providers = listOf(device), features = mapOf(AiFeature.Assistant to FeatureSetting(model = ours))).wantsArmillaryRow())
    }
}
