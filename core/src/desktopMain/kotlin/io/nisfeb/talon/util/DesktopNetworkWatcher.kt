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
        .filter { it.isUp && !it.isLoopback && !it.isVirtual && carriesCalls(it.name) }
        .flatMap { i -> i.inetAddresses.toList().filterNot { it.isLinkLocalAddress }.map { it.hostAddress ?: "" } }
        .toSet()

    /**
     * Whether an interface can carry a call's path. A container or VM
     * bridge coming and going (docker starting one per container) is not
     * a network change, nor a link-local address, and each one dropped
     * the party line. A VPN (tun, wg) moving is a real change.
     */
    internal fun carriesCalls(name: String): Boolean =
        VIRTUAL_PREFIXES.none { name.startsWith(it) }

    private val VIRTUAL_PREFIXES = listOf("docker", "veth", "br-", "virbr", "vboxnet", "vmnet", "lxcbr", "lxdbr", "podman", "cni", "flannel")
}
