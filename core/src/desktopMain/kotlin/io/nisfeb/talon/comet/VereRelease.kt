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
    /** Pinned per Talon release; bump deliberately, the pier migrates
     *  itself on the next start. */
    const val VERSION = "4.6"

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
}
