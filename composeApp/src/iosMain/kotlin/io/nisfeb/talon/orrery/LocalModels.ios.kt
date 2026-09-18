package io.nisfeb.talon.orrery

import io.nisfeb.talon.util.cacheDirPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import kotlin.coroutines.resume

/** The Swift-side factory, set once by MainViewController before anything asks. */
object IosModels {
    var factory: NativeModelFactory? = null
}

/**
 * iOS rungs, best first: Apple's system model where the phone has it,
 * then the floor, llama.cpp with the same Qwen2.5 1.5B the desktop
 * runs, fetched on first use.
 */
// A server you named comes first: the machine under your desk holds a
// far larger model than the one in your pocket. It is skipped in a
// blink when no server is set.
actual fun localModelRungs(): List<Rung> = listOf(LocalServerRung, SystemModelRung, LlamaIosRung)

private class Wrapped(private val native: NativeModel) : LocalModel {
    override val rung: String get() = native.name
    override suspend fun complete(system: String, user: String, grammar: String?, maxTokens: Int): String =
        withContext(Dispatchers.Default) { native.complete(system, user, grammar, maxTokens) }
    override fun close() = native.close()
}

object SystemModelRung : Rung() {
    override val name = "Apple's on-device model"

    override suspend fun status(): RungStatus {
        val f = IosModels.factory ?: return RungStatus.Unavailable("No model runtime was handed to the app.")
        val why = f.systemModelUnavailableReason()
        return if (why == null) RungStatus.Ready else RungStatus.Unavailable(why)
    }

    override suspend fun open(): LocalModel {
        val f = IosModels.factory ?: error("no runtime")
        return Wrapped(f.openSystemModel() ?: error("the system model would not open"))
    }
}

object LlamaIosRung : Rung() {
    override val name = "Qwen2.5 1.5B on this phone"
    const val FILE = "qwen2.5-1.5b-instruct-q4_k_m.gguf"
    const val URL = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/$FILE"
    const val BYTES = 1_117_320_736L
    val path: String get() = "${cacheDirPath.trimEnd('/')}/models/$FILE"

    override suspend fun status(): RungStatus = when {
        IosModels.factory == null -> RungStatus.Unavailable("No model runtime was handed to the app.")
        !NSFileManager.defaultManager.fileExistsAtPath(path) -> RungStatus.NeedsDownload(BYTES)
        else -> RungStatus.Ready
    }

    override suspend fun prepare(progress: (Float) -> Unit) {
        val f = IosModels.factory ?: error("no runtime")
        val failed = suspendCancellableCoroutine<String?> { cont ->
            f.download(URL, path, progress) { why -> if (cont.isActive) cont.resume(why) }
        }
        if (failed != null) error(failed)
    }

    override suspend fun open(): LocalModel {
        val f = IosModels.factory ?: error("no runtime")
        return Wrapped(f.openLlama(path) ?: error("the model would not load"))
    }
}
