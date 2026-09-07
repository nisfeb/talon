package io.nisfeb.talon.bridge

import io.nisfeb.talon.bridge.ui.PartyManager
import io.nisfeb.talon.util.Log
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess

/**
 * `talon-bridge [config]` sits on a party line headless until killed.
 * `talon-bridge --ui [config]` opens the Party Manager window instead.
 */
object Bridge {

    @JvmStatic
    fun main(args: Array<String>) {
        val ui = "--ui" in args
        val configFile = args.firstOrNull { !it.startsWith("--") }?.let(::File)
            ?: if (ui) File(System.getProperty("user.home"), ".config/talon/bridge.properties") else File("bridge.properties")
        if (ui) {
            PartyManager.launch(configFile)
            return
        }

        val config = runCatching { Config.load(configFile) }.getOrElse {
            System.err.println("talon-bridge: ${it.message}")
            exitProcess(2)
        }
        Log.i(TAG, "starting: $config")

        val runner = BridgeRunner()
        Runtime.getRuntime().addShutdownHook(
            Thread {
                Log.i(TAG, "leaving the line")
                runner.stop()
            },
        )

        runBlocking {
            runner.connect(config, this)
            var asked = false
            runner.status
                .map { s ->
                    when (s) {
                        is BridgeRunner.Status.Live -> "on the line with ${s.members.size}"
                        is BridgeRunner.Status.Failed -> "failed: ${s.why}"
                        is BridgeRunner.Status.Connected -> s.error?.let { "failed: $it" } ?: "connected as ${s.ship}"
                        is BridgeRunner.Status.Busy -> s.what
                        BridgeRunner.Status.Idle -> "idle"
                    }
                }
                .distinctUntilChanged()
                .collect { text ->
                    if (text.startsWith("failed: ")) {
                        System.err.println("talon-bridge: ${text.removePrefix("failed: ")}")
                        exitProcess(1)
                    }
                    Log.i(TAG, text)
                    if (!asked && runner.status.value is BridgeRunner.Status.Connected) {
                        asked = true
                        runner.join(config.host, config.room)
                    }
                }
        }
    }

    private const val TAG = "Bridge"
}
