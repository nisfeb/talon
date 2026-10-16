package io.nisfeb.talon.orrery

import io.nisfeb.talon.ai.AiSettings
import io.nisfeb.talon.ai.paidSettings
import io.nisfeb.talon.ai.triageInCloud
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Triage stays on this device under Armillary-only (sneagan, 2026-10-16:
 * "Orrery triage stays on the device either way"): the cloud rung is
 * never switched on and never ready, whatever an older build left.
 */
class CloudRungTest {
    @Test
    fun `the cloud rung is never on, whatever was left from before`() = runTest {
        val left = listOf(
            AiSettings.Config(provider = AiSettings.Provider.Anthropic, apiKey = "sk", model = null, frontierReadsMessages = true),
            paidSettings(),
            paidSettings().copy(frontierReadsMessages = true),
        )
        for (cfg in left) {
            assertFalse(cfg.triageInCloud(), cfg.toString())
            assertTrue(CloudRung { cfg }.status() is RungStatus.Unavailable, "never ready: $cfg")
        }
    }
}
