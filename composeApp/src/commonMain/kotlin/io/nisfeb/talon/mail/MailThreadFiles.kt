package io.nisfeb.talon.mail

import io.nisfeb.talon.util.Log
import io.nisfeb.talon.util.cacheDirPath
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * The last copy of each thread read, one file apiece, so a thread opened
 * after a cold start shows at once while the ship is asked again.
 *
 * In the platform's cache directory: the ship holds every message, so
 * the system reclaiming this space costs a wait and nothing else.
 */
internal class MailThreadFiles(private val dir: Path, private val keep: Int = KEEP) {
    private val fs = FileSystem.SYSTEM

    // Hashed, because a thread id is the ship's to choose and a file name is not.
    private fun file(id: String) = dir / "${id.encodeUtf8().sha256().hex()}.json"

    fun read(id: String): MailThread? = runCatching {
        AuspexApi.json.decodeFromString(MailThread.serializer(), fs.read(file(id)) { readUtf8() })
    }.getOrNull()

    fun write(t: MailThread) {
        runCatching {
            fs.createDirectories(dir)
            val f = file(t.id)
            val tmp = dir / "${f.name}.tmp"
            fs.write(tmp) { writeUtf8(AuspexApi.json.encodeToString(MailThread.serializer(), t)) }
            fs.atomicMove(tmp, f)
            prune()
        }.onFailure { Log.w(TAG, "thread not stored", it) }
    }

    fun delete(id: String) {
        runCatching { fs.delete(file(id)) }
    }

    // ponytail: lists the directory on every write, fine at a few hundred files.
    private fun prune() {
        val all = fs.list(dir)
        if (all.size <= keep) return
        all.sortedBy { fs.metadataOrNull(it)?.lastModifiedAtMillis ?: 0L }
            .take(all.size - keep)
            .forEach { runCatching { fs.delete(it) } }
    }

    companion object {
        private const val TAG = "MailThreadFiles"

        /** Threads kept on disk; the least recently read go first. */
        const val KEEP = 500

        /** One ship's directory. The eraser deletes what this names. */
        fun dirFor(ship: String): String =
            "$cacheDirPath/mail/${ship.filter { it.isLetterOrDigit() || it == '-' }}"

        fun erase(ship: String) {
            runCatching { FileSystem.SYSTEM.deleteRecursively(dirFor(ship).toPath()) }
        }
    }
}
