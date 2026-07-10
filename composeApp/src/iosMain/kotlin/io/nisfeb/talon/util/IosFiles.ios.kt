package io.nisfeb.talon.util

import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

/**
 * Tiny JSON-file persistence primitive for the iOS settings stores,
 * mirroring the desktop pattern (write to a sibling .tmp then atomically
 * move) but via okio's multiplatform [FileSystem]. Files live under the
 * app's Documents directory.
 */
internal object IosFiles {
    private fun documentsDir(): String =
        NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
            .first() as String

    fun read(name: String): String? {
        val path = "${documentsDir()}/$name".toPath()
        return runCatching {
            if (!FileSystem.SYSTEM.exists(path)) return null
            FileSystem.SYSTEM.read(path) { readUtf8() }
        }.getOrNull()
    }

    fun write(name: String, content: String) {
        val dir = documentsDir()
        val path = "$dir/$name".toPath()
        val tmp = "$dir/$name.tmp".toPath()
        runCatching {
            FileSystem.SYSTEM.write(tmp) { writeUtf8(content) }
            FileSystem.SYSTEM.atomicMove(tmp, path)
        }
    }
}
