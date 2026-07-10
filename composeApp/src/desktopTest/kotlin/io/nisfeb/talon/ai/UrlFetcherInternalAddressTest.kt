package io.nisfeb.talon.ai

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the post-DNS SSRF guard [isInternalAddress]. It's a JVM-only leaf
 * (the OkHttp `Dns` hook feeds it `InetAddress`es), so its coverage moved
 * here from the common UrlFetcherTest when networking went multiplatform.
 * The address checks use IP literals (InetAddress.getByName on a literal
 * does NOT hit DNS) so the test runs offline.
 */
class UrlFetcherInternalAddressTest {

    @Test
    fun `blocks internal IP addresses, allows public ones`() {
        // Loopback, cloud metadata, RFC1918 private ranges.
        assertTrue(isInternalAddress(InetAddress.getByName("127.0.0.1")))
        assertTrue(isInternalAddress(InetAddress.getByName("169.254.169.254")))
        assertTrue(isInternalAddress(InetAddress.getByName("10.1.2.3")))
        assertTrue(isInternalAddress(InetAddress.getByName("192.168.0.1")))
        assertTrue(isInternalAddress(InetAddress.getByName("172.16.5.5")))
        assertTrue(isInternalAddress(InetAddress.getByName("::1")))
        assertTrue(isInternalAddress(InetAddress.getByName("0.0.0.0")))
        // IPv6 unique-local (fc00::/7) — not caught by isSiteLocalAddress.
        assertTrue(isInternalAddress(InetAddress.getByName("fd12:3456:789a::1")))
        assertTrue(isInternalAddress(InetAddress.getByName("fc00::1")))
        // Public addresses pass.
        assertFalse(isInternalAddress(InetAddress.getByName("8.8.8.8")))
        assertFalse(isInternalAddress(InetAddress.getByName("1.1.1.1")))
        assertFalse(isInternalAddress(InetAddress.getByName("172.32.0.1"))) // outside 172.16/12
        assertFalse(isInternalAddress(InetAddress.getByName("2001:4860:4860::8888"))) // public IPv6
    }
}
