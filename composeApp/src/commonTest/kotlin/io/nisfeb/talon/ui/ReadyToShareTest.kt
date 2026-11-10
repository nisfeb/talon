package io.nisfeb.talon.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A share that starts sends where the owner is, every time. The second
 * share of a day sent nothing from a phone that had not moved
 * (2026-11-10): the fix went only with the switch turning on.
 */
class ReadyToShareTest {
    private class Phone(on: Boolean, val grants: Boolean = true) : LocationSharing {
        val did = mutableListOf<String>()
        override val on = MutableStateFlow(on)
        override fun allowed() = true
        override val names = false
        override suspend fun start(): Boolean { did += "start"; if (grants) on.value = true; return grants }
        override fun sendNow() { did += "sendNow" }
        override fun stop() = Unit
    }

    @Test
    fun `with the switch on already, a share still sends where the owner is`() = runTest {
        val p = Phone(on = true)
        assertTrue(readyToShare(p))
        assertEquals(listOf("sendNow"), p.did)
    }

    @Test
    fun `with the switch off it is turned on, which sends, and a refusal stops the share`() = runTest {
        val p = Phone(on = false)
        assertTrue(readyToShare(p))
        assertEquals(listOf("start"), p.did)
        assertFalse(readyToShare(Phone(on = false, grants = false)))
    }

    @Test
    fun `a device that sends no location shares as before`() = runTest {
        assertTrue(readyToShare(null))
    }

    @Test
    fun `a fix is fresh for two minutes`() {
        assertTrue(freshFix(atMs = 1_000, nowMs = 1_000 + FRESH_FIX_MS - 1))
        assertFalse(freshFix(atMs = 1_000, nowMs = 1_000 + FRESH_FIX_MS))
    }
}
