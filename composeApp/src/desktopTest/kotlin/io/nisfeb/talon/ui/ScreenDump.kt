package io.nisfeb.talon.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.printToString

/**
 * Runs [block], and when it fails prints what was on screen, the threads,
 * the app's log and the [ship]'s last requests, into the report CI
 * keeps. A wait that times out only on a loaded runner can't be run
 * again to look; this is the look.
 */
@OptIn(ExperimentalTestApi::class)
internal inline fun ComposeUiTest.dumpingScreenOnFailure(ship: io.nisfeb.talon.urbit.FakeShip? = null, block: () -> Unit) {
    try {
        block()
    } catch (e: Throwable) {
        val tree = runCatching { onAllNodes(isRoot()).printToString(maxDepth = Int.MAX_VALUE) }
            .getOrElse { "(no tree: $it)" }
        println("── on screen when it failed ──\n$tree")
        // What every thread was doing: a read that never came back is
        // stuck somewhere, a lock or a busy pool, and this says where.
        println("── threads when it failed ──")
        for ((t, stack) in Thread.getAllStackTraces()) {
            println("\"${t.name}\" ${t.state}")
            stack.take(25).forEach { println("    at $it") }
        }
        // The app's own log, the end of it, which the failing test wrote
        // last. Gradle gives each run its own user.home (build.gradle.kts).
        val log = java.io.File(System.getProperty("user.home"), ".config/talon/log/talon.log")
        println("── the app's log when it failed ──")
        runCatching { log.readLines().takeLast(200).forEach(::println) }
            .onFailure { println("(no log: $it)") }
        // What the app asked of the ship, last first out: whether a poke
        // that was waited on ever went.
        if (ship != null) {
            println("── the ship's last requests ──")
            ship.requests.toList().takeLast(60).forEach(::println)
        }
        throw e
    }
}
