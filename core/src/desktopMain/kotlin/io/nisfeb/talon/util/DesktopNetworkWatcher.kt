package io.nisfeb.talon.util

import java.net.NetworkInterface

/**
 * Polls the machine's addresses every few seconds and bumps
 * [NetworkChanges] when the set changes. The JVM has no network
 * change callback; a poll this cheap is the honest option.
 */
object DesktopNetworkWatcher {
    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        Thread({
            var last: Set<String>? = null
            while (true) {
                val now = runCatching { snapshot() }.getOrNull()
                if (now != null) {
                    if (last != null && now != last) {
                        Log.i("Network", "addresses changed; nudging live calls")
                        NetworkChanges.bump()
                    }
                    last = now
                }
                Thread.sleep(4_000)
            }
        }, "network-watch").apply { isDaemon = true; start() }
    }

    private fun snapshot(): Set<String> = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { i -> i.inetAddresses.toList().map { it.hostAddress ?: "" } }
        .toSet()
}
