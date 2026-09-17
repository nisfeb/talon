package io.nisfeb.talon.orrery

import io.nisfeb.talon.ai.AiSettings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The cloud rung is ready only with a key, and says so otherwise. */
class CloudRungTest {
    @Test
    fun `no key is not a rung`() = runTest {
        val rung = CloudRung { AiSettings.Config(provider = AiSettings.Provider.Anthropic, apiKey = "", model = null) }
        assertTrue(rung.status() is RungStatus.Unavailable)
        val keyed = CloudRung { AiSettings.Config(provider = AiSettings.Provider.Anthropic, apiKey = "sk", model = null) }
        assertEquals(RungStatus.Ready, keyed.status())
        assertTrue("Anthropic" in keyed.name)
    }
}
