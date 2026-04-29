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
        // Best-effort teardown. Order matters slightly — cancel the
        // update scope first so its in-flight HTTP calls bail before
        // we yank the OkHttp dispatcher out from under them, then
        // drain the OkHttp pool, then close Room.
        runCatching { updateScope.cancel() }
        runCatching { http.dispatcher.executorService.shutdown() }
        runCatching { http.connectionPool.evictAll() }
        runCatching { db.close() }
        // Note: TlonChatRepo's SSE drain runs inside App()'s
        // key(loggedInShip) block; its DisposableEffect.onDispose
        // calls repo.stop() when the composition tears down, which
        // happens during exitApplication's runtime shutdown.
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
