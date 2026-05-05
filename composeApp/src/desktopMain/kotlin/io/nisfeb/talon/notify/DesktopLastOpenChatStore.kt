package io.nisfeb.talon.notify

import io.nisfeb.talon.util.AppDirs
import io.nisfeb.talon.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * JSON-file-backed [LastOpenChatStore] for desktop. The map is
 * serialised verbatim under `<userData>/last-open-chat.json`. Writes
 * go via a `<file>.tmp + atomic rename` so a crash mid-write
 * leaves the previous version intact (Files.move with ATOMIC_MOVE
 * + REPLACE_EXISTING).
 *
 * The store reads the file on construction; if the file is absent
 * or unreadable the in-memory map starts empty and the next write
 * creates it.
 */
class DesktopLastOpenChatStore(
    private val file: File = File(AppDirs.userData, FILE_NAME),
) : LastOpenChatStore {

    @Serializable
    private data class Snapshot(val entries: Map<String, String> = emptyMap())

    private val _state = MutableStateFlow(loadOrEmpty())
    override val state: StateFlow<Map<String, String>> = _state.asStateFlow()

    override fun set(patp: String, whom: String) {
        val prev = _state.value
        if (prev[patp] == whom) return
        val next = prev + (patp to whom)
        _state.value = next
        persist(next)
    }

    override fun clear(patp: String) {
        val prev = _state.value
        if (patp !in prev) return
        val next = prev - patp
        _state.value = next
        persist(next)
    }

    private fun loadOrEmpty(): Map<String, String> = runCatching {
        if (!file.exists()) return emptyMap()
        JSON.decodeFromString<Snapshot>(file.readText()).entries
    }.getOrElse {
        Log.w(TAG, "last-open-chat read failed; starting empty", it)
        emptyMap()
    }

    private fun persist(map: Map<String, String>) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(JSON.encodeToString(Snapshot(map)))
            Files.move(
                tmp.toPath(), file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.onFailure { Log.w(TAG, "last-open-chat write failed", it) }
    }

    private companion object {
        private const val TAG = "DesktopLastOpenChatStore"
        const val FILE_NAME = "last-open-chat.json"
        val JSON = Json {
            ignoreUnknownKeys = true
            prettyPrint = false
        }
    }
}
