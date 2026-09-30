package io.nisfeb.talon.orrery

import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/** Load the model, answer one bounded prompt, exit 0. A native crash never returns. */
fun main(args: Array<String>) {
    val path = args.firstOrNull() ?: exitProcess(2)
    val ok = runCatching {
        LlamaCppModel(path, "probe").use { m ->
            runBlocking { m.complete("Answer with JSON.", "Say {}.", "root ::= \"{\" \"}\"", 8) }
        }
    }.isSuccess
    exitProcess(if (ok) 0 else 1)
}
