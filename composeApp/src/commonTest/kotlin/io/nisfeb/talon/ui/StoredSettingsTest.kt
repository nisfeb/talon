package io.nisfeb.talon.ui

import io.nisfeb.talon.notify.RelaySettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * iOS kept these three in memory and forgot them on every launch. Each is
 * now read back from its store by the next instance: a relaunch.
 */
class StoredSettingsTest {
    private class Store : UiSettingsStore {
        var text: String? = null
        override fun read() = text
        override fun write(text: String) { this.text = text }
    }

    @Test
    fun `the relay's endpoint and device ids outlive a relaunch`() {
        val store = Store()
        assertEquals(RelaySettings.DEFAULT_ENDPOINT, StoredRelaySettings(store).endpoint.value)
        StoredRelaySettings(store).apply { setEndpoint("https://relay.me.test"); setDeviceIdFor("~zod", "dev-1") }
        val again = StoredRelaySettings(store)
        assertEquals("https://relay.me.test", again.endpoint.value)
        assertEquals("dev-1", again.deviceIdFor("~zod"))
        again.clearDeviceIdFor("~zod")
        assertEquals("", StoredRelaySettings(store).deviceIdFor("~zod"))
    }

    @Test
    fun `seen dots stay seen, and a store that cannot be read starts fresh`() {
        val store = Store()
        StoredMenuSeenStore(store).apply { markStatusesSeenAt(42L); markInvitesSeen("abc") }
        assertEquals(MenuSeenState(42L, "abc"), StoredMenuSeenStore(store).state.value)
        store.text = "not json"
        assertEquals(MenuSeenState(), StoredMenuSeenStore(store).state.value)
        assertEquals("menuseen-sampel-palnet.json", menuSeenFileName("~sampel-palnet"))
    }
}
