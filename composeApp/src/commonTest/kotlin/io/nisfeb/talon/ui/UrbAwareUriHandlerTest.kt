package io.nisfeb.talon.ui

import androidx.compose.ui.platform.UriHandler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UrbAwareUriHandlerTest {

    private class RecordingDelegate : UriHandler {
        var opened: String? = null
        override fun openUri(uri: String) { opened = uri }
    }

    @Test
    fun routesUrbToHandlerNotDelegate() {
        val delegate = RecordingDelegate()
        var urb: String? = null
        val h = UrbAwareUriHandler(delegate) { urb = it }

        h.openUri("urb://~sampel-palnet/notes/x")

        assertEquals("urb://~sampel-palnet/notes/x", urb)
        assertNull(delegate.opened, "urb:// must not reach the platform handler (would open the browser on desktop)")
    }

    @Test
    fun delegatesHttpLinks() {
        val delegate = RecordingDelegate()
        var urb: String? = null
        val h = UrbAwareUriHandler(delegate) { urb = it }

        h.openUri("https://urbit.org")

        assertEquals("https://urbit.org", delegate.opened)
        assertNull(urb, "http(s) must go to the platform handler, not the urb launcher")
    }
}
