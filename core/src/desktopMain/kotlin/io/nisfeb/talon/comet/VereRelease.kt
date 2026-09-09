package io.nisfeb.talon.comet

/**
 * Which vere binary this machine needs and where to get it. Pure so
 * the OS / arch mapping is testable without touching the network.
 *
 * Vere publishes one tarball per platform on its GitHub release, each
 * holding a single binary named like the tarball. Windows' has no
 * `.exe` suffix, so we rename on extract.
 */
object VereRelease {
    /** What a fresh setup installs; an upgrade from Settings can move a
     *  pier past it. The pier migrates itself on the next start. */
    const val VERSION = "4.6"

    /** The GitHub API answer for the newest release. */
    const val LATEST_URL = "https://api.github.com/repos/urbit/vere/releases/latest"

    fun assetFor(osName: String, osArch: String): String? {
        val os = osName.lowercase()
        val arch = osArch.lowercase()
        val isArm = arch.contains("aarch64") || arch.contains("arm64")
        return when {
            os.contains("linux") -> if (isArm) "linux-aarch64.tgz" else "linux-x86_64.tgz"
            os.contains("mac") -> if (isArm) "macos-aarch64.tgz" else "macos-x86_64.tgz"
            os.contains("win") -> if (isArm) null else "windows-x86_64.tgz"
            else -> null
        }
    }

    fun downloadUrl(asset: String, version: String = VERSION): String =
        "https://github.com/urbit/vere/releases/download/vere-v$version/$asset"

    /** The binary's name inside the tarball: `vere-v4.6-linux-x86_64`. */
    fun memberName(asset: String, version: String = VERSION): String =
        "vere-v$version-" + asset.removeSuffix(".tgz")

    /** What we store it as. Windows gets the suffix its loader wants. */
    fun binaryName(asset: String, version: String = VERSION): String =
        memberName(asset, version) + if (asset.startsWith("windows")) ".exe" else ""

    /** "4.7" from a release tag like `vere-v4.7`; null for anything else
     *  (release candidates and the like are not offered). */
    fun versionFromTag(tag: String): String? =
        Regex("^vere-v(\\d+\\.\\d+)$").find(tag.trim())?.groupValues?.get(1)

    /** Whether [candidate] is a later major.minor than [installed]. */
    fun isNewer(candidate: String, installed: String): Boolean {
        fun parts(v: String) = v.split('.').map { it.toIntOrNull() ?: 0 }
        val c = parts(candidate)
        val i = parts(installed)
        for (k in 0 until maxOf(c.size, i.size)) {
            val a = c.getOrElse(k) { 0 }
            val b = i.getOrElse(k) { 0 }
            if (a != b) return a > b
        }
        return false
    }
}
