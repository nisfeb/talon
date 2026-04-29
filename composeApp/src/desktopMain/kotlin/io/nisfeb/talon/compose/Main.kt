package io.nisfeb.talon.compose

import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.nisfeb.talon.ai.AiSettingsRepository
import io.nisfeb.talon.ai.createAiSettings
import io.nisfeb.talon.data.AppDatabase
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
        //  2. dispatcher.cancelAll() interrupts ALL in-flight calls
        //     including TlonChatRepo's SSE drain (parked on a
        //     long-poll execute()). Without this the executor
        //     shutdown waits for the SSE call to return — which it
        //     won't until the server times out — and the JVM lingers.
        //  3. Shut the executor service. Now-empty queue + idle
        //     threads → orderly thread exit.
        //  4. Evict the connection pool.
        //  5. Close Room.
        // TlonChatRepo's repo.stop() still fires from App()'s
        // key-block DisposableEffect during exitApplication's
        // composition teardown, but it runs AFTER this shutdown(),
        // so the SSE drain coroutine has already thrown by then.
        runCatching { updateScope.cancel() }
        runCatching { http.dispatcher.cancelAll() }
        runCatching { http.dispatcher.executorService.shutdown() }
        runCatching { http.connectionPool.evictAll() }
        runCatching { db.close() }
    }
}

fun main() = application {
    val graph = remember { DesktopAppGraph() }
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
