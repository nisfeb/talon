package io.nisfeb.talon.compose

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.nisfeb.talon.ai.AiSettingsRepository
import io.nisfeb.talon.ai.createAiSettings
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.DatabaseOpenTimeoutException
import io.nisfeb.talon.data.createAppDatabase
import io.nisfeb.talon.ui.DraftStore
import io.nisfeb.talon.ui.InMemoryDraftStore
import io.nisfeb.talon.update.NoopUpdateInstallerHook
import io.nisfeb.talon.update.StaticUpdateRuntime
import io.nisfeb.talon.update.UpdateState
import io.nisfeb.talon.urbit.SessionStore
import io.nisfeb.talon.urbit.createSessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Desktop counterpart to the Android `TalonComposeApp` class. Holds
 * everything that should outlive a single composition + tears it
 * down cleanly when the window closes. Without [shutdown], closing
 * the window leaks:
 *   - the OkHttp dispatcher's executor service (non-daemon threads
 *     keep the JVM alive)
 *   - the connection pool (sockets stay open until server timeout)
 *   - the UpdateState supervisor scope
 *   - SQLite WAL handles (can race on rapid restart)
 * Symptom: the window vanishes but the process lingers in `ps -ef`
 * for up to 60s, sometimes indefinitely if SSE keeps a thread
 * parked.
 */
private class DesktopAppGraph {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // long-lived SSE — no read timeout
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
    val sessionStore: SessionStore = createSessionStore()
    val aiSettings: AiSettingsRepository = createAiSettings()
    val db: AppDatabase = createAppDatabase()
    val drafts: DraftStore = InMemoryDraftStore()

    private val updateScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val updateState: UpdateState = UpdateState(
        scope = updateScope,
        runtime = StaticUpdateRuntime(),
        installer = NoopUpdateInstallerHook(),
    )

    fun shutdown() {
        // Best-effort teardown. Order matters:
        //  1. Cancel update scope so its in-flight HTTP calls bail.
        //  2. dispatcher.cancelAll() interrupts dispatcher-tracked
        //     calls (enqueue + EventSources). Synchronous execute()
        //     calls running on coroutine workers aren't always
        //     reached by cancelAll, so we rely on shutdownNow below
        //     to interrupt their threads.
        //  3. shutdown() → awaitTermination(2s) → shutdownNow().
        //     Graceful first; if anything's still parked after 2s
        //     (e.g. a synchronous long-poll that didn't see the
        //     cancel), interrupt the threads so the JVM can exit
        //     instead of lingering for the server's 60s timeout.
        //  4. Evict the connection pool.
        //  5. Close Room.
        runCatching { updateScope.cancel() }
        runCatching { http.dispatcher.cancelAll() }
        runCatching {
            val executor = http.dispatcher.executorService
            executor.shutdown()
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow()
            }
        }
        runCatching { http.connectionPool.evictAll() }
        runCatching { db.close() }
    }
}

fun main() {
    // Construct the dependency graph BEFORE entering application { … }.
    // application's body composes on the AWT EDT; building the graph
    // there means the SQLite smoke-test's runBlocking parks the EDT
    // until the open completes — multi-second hitches on slow disks
    // (encrypted home dir, AV-scanned new file on Windows). Building
    // here runs on the JVM main thread and the window only mounts
    // once the graph is ready.
    //
    // DatabaseOpenTimeoutException is special: the disk is wedged
    // (unreachable NFS, stalled FUSE), the data may be fine. Show a
    // dialog and exit non-zero rather than wiping or crashing
    // silently with a stack trace nobody will see.
    val graph = try {
        DesktopAppGraph()
    } catch (t: DatabaseOpenTimeoutException) {
        javax.swing.JOptionPane.showMessageDialog(
            null,
            "Talon couldn't open its data directory at:\n\n${t.dbPath}\n\n" +
                "If you store your home directory on a network mount or " +
                "encrypted volume, make sure it's available and try again.",
            "Talon — startup error",
            javax.swing.JOptionPane.ERROR_MESSAGE,
        )
        kotlin.system.exitProcess(2)
    }
    application {
        Window(
            onCloseRequest = {
                graph.shutdown()
                exitApplication()
            },
            title = "Talon",
        ) {
            App(
                http = graph.http,
                sessionStore = graph.sessionStore,
                aiSettings = graph.aiSettings,
                db = graph.db,
                drafts = graph.drafts,
                updateState = graph.updateState,
            )
        }
    }
}
