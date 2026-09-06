package io.nisfeb.talon.util

import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionComplete
import platform.Foundation.NSFileProtectionKey
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

/**
 * Tiny JSON-file persistence primitive for the iOS settings stores,
 * mirroring the desktop pattern (write to a sibling .tmp then atomically
 * move) but via okio's multiplatform [FileSystem].
 *
 * These files hold credentials — three API keys, and a live urbauth ship
 * cookie — so they deliberately do NOT live in Documents: Info.plist
 * sets UIFileSharingEnabled and LSSupportsOpeningDocumentsInPlace, which
 * publishes that directory to the Files app and over AFC, where the
 * contents can be copied out without ever opening Talon. Application
 * Support is not user-visible, and the files are marked
 * NSFileProtectionComplete so they are unreadable while the device is
 * locked. (That closes the Files/AFC path, not an unencrypted local
 * backup — Keychain is the upgrade for that.)
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
internal object IosFiles {

    private fun documentsDir(): String =
        NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
            .first() as String

    private fun baseDir(): String {
        val dir = NSSearchPathForDirectoriesInDomains(
            NSApplicationSupportDirectory, NSUserDomainMask, true,
        ).first() as String
        val fm = NSFileManager.defaultManager
        if (!fm.fileExistsAtPath(dir)) {
            fm.createDirectoryAtPath(
                path = dir,
                withIntermediateDirectories = true,
                attributes = mapOf<Any?, Any?>(NSFileProtectionKey to NSFileProtectionComplete),
                error = null,
            )
        }
        return dir
    }

    /** Protect the file itself, not just the directory it was created in. */
    private fun protect(path: String) {
        runCatching {
            NSFileManager.defaultManager.setAttributes(
                attributes = mapOf<Any?, Any?>(NSFileProtectionKey to NSFileProtectionComplete),
                ofItemAtPath = path,
                error = null,
            )
        }
    }

    /**
     * Move a file left in Documents by an older build, once. Without
     * this an upgrading user keeps the exposed copy forever — and the
     * exposed copy is the one with the ship cookie in it.
     */
    private fun migrate(name: String, from: String, to: String) {
        val old = "$from/$name"
        val new = "$to/$name"
        val fm = NSFileManager.defaultManager
        if (!fm.fileExistsAtPath(old) || fm.fileExistsAtPath(new)) return
        runCatching {
            fm.moveItemAtPath(srcPath = old, toPath = new, error = null)
            protect(new)
        }
    }

    fun read(name: String): String? {
        val dir = baseDir()
        migrate(name, documentsDir(), dir)
        val path = "$dir/$name".toPath()
        return runCatching {
            if (!FileSystem.SYSTEM.exists(path)) return null
            FileSystem.SYSTEM.read(path) { readUtf8() }
        }.getOrNull()
    }

    fun write(name: String, content: String) {
        val dir = baseDir()
        val path = "$dir/$name".toPath()
        val tmp = "$dir/$name.tmp".toPath()
        runCatching {
            FileSystem.SYSTEM.write(tmp) { writeUtf8(content) }
            FileSystem.SYSTEM.atomicMove(tmp, path)
            protect("$dir/$name")
        }
    }
}
