package io.nisfeb.talon.ai

import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/**
 * Child-process entry point for [EmbedderProbe]. Runs one embed and
 * exits 0 on success. A caught failure exits non-zero; an uncatchable
 * native SIGSEGV kills the process (also non-zero) — either way the
 * parent reads "unsafe". Never invoke directly; [EmbedderProbe] spawns
 * it in its own JVM so a crash can't take the app down.
 */
fun main() {
    val ok = runCatching {
        runBlocking { DesktopEmbedder().embed("probe") } != null
    }.getOrDefault(false)
    exitProcess(if (ok) 0 else 3)
}
