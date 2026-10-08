package io.nisfeb.talon.ui.screens

import io.nisfeb.talon.ai.ARMILLARY_PROVIDER
import io.nisfeb.talon.ai.AiProvider
import io.nisfeb.talon.ai.DEVICE_PROVIDER
import io.nisfeb.talon.ai.ModelRef
import io.nisfeb.talon.ai.ProviderKind
import kotlin.test.Test
import kotlin.test.assertEquals

/** The providers in Settings: what works on top, the device's own model last. */
class ProviderOrderTest {
    private val openRouter = AiProvider("or", ProviderKind.OpenRouter, "OpenRouter")
    private val device = AiProvider(DEVICE_PROVIDER, ProviderKind.ThisDevice, "On this device")
    private val armillary = AiProvider(ARMILLARY_PROVIDER, ProviderKind.Armillary, "Armillary", apiKey = "k.s")
    private val anthropic = AiProvider("an", ProviderKind.Anthropic, "Anthropic", apiKey = "sk-ant")
    private val server = AiProvider("lm", ProviderKind.OpenAiCompatible, "LM Studio", baseUrl = "http://localhost:1234/v1")
    private val noAddress = AiProvider("x", ProviderKind.OpenAiCompatible, "Server")

    private fun ids(ps: List<AiProvider>) = ps.map { it.id }

    @Test
    fun `the default's provider first, then the set up, then the waiting, the device last`() {
        // sneagan's screen: a keyless OpenRouter above a working Armillary.
        assertEquals(listOf("armillary", "or", "device"), ids(showOrder(listOf(openRouter, device, armillary), ModelRef(ARMILLARY_PROVIDER, ""))))
        assertEquals(
            listOf("an", "armillary", "lm", "or", "x", "device"),
            ids(showOrder(listOf(device, openRouter, anthropic, noAddress, armillary, server), null)),
            "set up (a key, or a server's address) keeps profile order, then the rest",
        )
        assertEquals(listOf("device", "an"), ids(showOrder(listOf(anthropic, device), ModelRef(DEVICE_PROVIDER, ""))), "the default goes first, even the device's")
        assertEquals(listOf("or", "device"), ids(showOrder(listOf(device, openRouter.copy(apiKey = "sk-or")), ModelRef("gone", "m"))), "a default on no provider here moves nothing")
        // An Armillary row before its ship gave this device a key is still waiting.
        assertEquals(listOf("an", "armillary"), ids(showOrder(listOf(armillary.copy(apiKey = ""), anthropic), null)))
    }
}
