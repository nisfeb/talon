package io.nisfeb.talon.ai

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException

actual fun createUrlFetcherClient(): HttpClient = HttpClient(OkHttp) {
    followRedirects = true
    engine {
        config {
            dns(BlockingDns)
        }
    }
}

/** OkHttp [Dns] that resolves normally but refuses internal targets — runs
 *  per hop (so redirects are covered) and before connect (so the socket to
 *  an internal host is never opened). */
internal object BlockingDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        if (isBlockedName(hostname)) {
            throw UnknownHostException("refusing to fetch internal host: $hostname")
        }
        val addrs = Dns.SYSTEM.lookup(hostname)
        if (addrs.any(::isInternalAddress)) {
            throw UnknownHostException("refusing to fetch internal host: $hostname")
        }
        return addrs
    }
}

/** True for loopback / any-local / link-local (incl. cloud metadata) /
 *  site-local (RFC1918) / multicast addresses. Also covers IPv6 unique-local
 *  (fc00::/7), which InetAddress.isSiteLocalAddress misses. */
internal fun isInternalAddress(addr: InetAddress): Boolean {
    if (addr.isLoopbackAddress || addr.isAnyLocalAddress || addr.isLinkLocalAddress ||
        addr.isSiteLocalAddress || addr.isMulticastAddress
    ) {
        return true
    }
    val bytes = addr.address
    return bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc
}
