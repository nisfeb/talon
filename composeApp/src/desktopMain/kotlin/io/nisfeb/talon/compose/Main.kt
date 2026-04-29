package io.nisfeb.talon.compose

import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import org.jetbrains.skia.Image as SkiaImage
import io.nisfeb.talon.ai.AiSettingsRepository
import io.nisfeb.talon.ai.DailyDigestSettings
import io.nisfeb.talon.ai.DesktopDailyDigestSettings
import io.nisfeb.talon.ai.DesktopWatchwordsSyncSettings
import io.nisfeb.talon.ai.WatchwordsSyncSettings
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
import io.nisfeb.talon.urbit.SettingsSync
import io.nisfeb.talon.urbit.SettingsSyncImpl
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
    val dailyDigestSettings: DailyDigestSettings = DesktopDailyDigestSettings()
    val watchwordsSync: WatchwordsSyncSettings = DesktopWatchwordsSyncSettings()
    val drafts: DraftStore = InMemoryDraftStore()

    init {
        // Eager smoke-test open against the ship we'll first land on,
        // closed immediately. Surfaces DatabaseOpenTimeoutException to
        // main()'s catch arms (so the friendly "data dir unavailable"
        // dialog still fires when a home-dir mount is wedged) before
        // the App composition mounts. The App's key(shipKey) block
        // reopens the same file moments later.
        val probe = createAppDatabase(sessionStore.activeShip() ?: "__loggedout__")
        runCatching { probe.close() }
    }

    // Tracks the currently-mounted db so window-close shutdown can
    // synchronously flush WAL — the daemon-thread close in App's
    // DisposableEffect runs on a 2s delay and may not finish before
    // exitProcess fires.
    @Volatile var currentDb: AppDatabase? = null
    val createDb: (String) -> AppDatabase = { shipKey ->
        createAppDatabase(shipKey).also { currentDb = it }
    }
    val createSettingsSync: (AppDatabase) -> SettingsSync = { db ->
        SettingsSyncImpl(
            db = db,
            aiSettings = aiSettings,
            dailyDigestSettings = dailyDigestSettings,
            // Desktop has no AlarmManager equivalent wired, so the
            // digest doesn't actually fire here. The callback's a no-op
            // until that subsystem ports.
            rearmDailyDigest = {},
        )
    }

    private val updateScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val updateState: UpdateState = UpdateState(
        scope = updateScope,
        runtime = StaticUpdateRuntime(),
        installer = NoopUpdateInstallerHook(),
    )

    fun shutdown() {
        // Best-effort teardown. Order matters:
        //  1. Cancel update scope so its in-flight HTTP calls bail.
        //  2. dispatcher.cancelAll() — soft signal to dispatcher-
        //     tracked calls (enqueue + EventSources).
        //  3. shutdownNow() then awaitTermination(2s).
        //     OkHttp 4.x's Dispatcher constructs its executor with
        //     a NON-daemon thread factory (verified against
        //     OkHttp 4.12 source — Util.threadFactory(name, false)).
        //     A worker mid-Socket.read on a synchronous execute()
        //     only unwinds when the underlying socket closes;
        //     shutdownNow interrupts but doesn't always reach the
        //     blocking syscall. Without awaitTermination the JVM
        //     stays alive waiting for the non-daemon worker.
        //     2s is the cap before we give up and let the daemon-
        //     wrapped exitProcess in Main.kt force-kill.
        //  4. Evict the connection pool.
        //  5. Close Room — synchronous WAL flush, must complete.
        //     The current ship's db is captured by `currentDb` from
        //     the App composition; closing here ensures WAL flush even
        //     if the App's deferred-close daemon hasn't fired yet.
        runCatching { updateScope.cancel() }
        runCatching { http.dispatcher.cancelAll() }
        runCatching {
            val exec = http.dispatcher.executorService
            exec.shutdownNow()
            exec.awaitTermination(2, TimeUnit.SECONDS)
        }
        runCatching { http.connectionPool.evictAll() }
        runCatching { currentDb?.close() }
    }
}

/**
 * Show a Swing error dialog from a startup-failure code path. Uses
 * a scrollable JTextArea inside a JScrollPane so long stack-trace
 * bodies don't overflow the screen the way JOptionPane.PLAIN_MESSAGE
 * with a giant String does.
 */
private fun showStartupError(title: String, body: String) {
    val area = javax.swing.JTextArea(body).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        rows = 12
        columns = 60
    }
    val scroll = javax.swing.JScrollPane(area).apply {
        preferredSize = java.awt.Dimension(640, 280)
    }
    javax.swing.JOptionPane.showMessageDialog(
        null, scroll, title, javax.swing.JOptionPane.ERROR_MESSAGE,
    )
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
    // Two catch arms: the DB-timeout case has actionable user
    // guidance; everything else (UnsatisfiedLinkError on the
    // bundled SQLite native, OOM, corrupt sessions.json that the
    // DesktopSessionStore loader didn't recover from, etc.) gets
    // the generic dialog with a stack trace excerpt. Without the
    // generic catch, double-clicking the app on Windows produces
    // an invisible stderr trace and the user sees a process that
    // briefly appeared and disappeared.
    val graph = try {
        DesktopAppGraph()
    } catch (t: DatabaseOpenTimeoutException) {
        showStartupError(
            title = "Talon — couldn't open data directory",
            body = "Talon couldn't open its data directory at:\n\n${t.dbPath}\n\n" +
                "If your home directory is on a network mount or encrypted " +
                "volume, make sure it's available and try again.",
        )
        kotlin.system.exitProcess(2)
    } catch (t: Throwable) {
        showStartupError(
            title = "Talon — startup error",
            body = "Talon couldn't start.\n\n" +
                "${t.javaClass.simpleName}: ${t.message ?: "(no message)"}\n\n" +
                t.stackTraceToString().lineSequence().take(15).joinToString("\n"),
        )
        kotlin.system.exitProcess(3)
    }
    // Load the window/taskbar icon from the bundled resources. The
    // PNG lives at composeApp/src/desktopMain/resources/icon.png and
    // gets packaged onto the classpath at /icon.png. Skia decodes
    // and we hand a Painter to Window. Best-effort: if loading
    // fails for any reason the window just gets the JVM default.
    val iconPainter = runCatching {
        val bytes = ClassLoader.getSystemResourceAsStream("icon.png")?.use { it.readBytes() }
        bytes?.let { BitmapPainter(SkiaImage.makeFromEncoded(it).toComposeImageBitmap()) }
    }.getOrNull()

    application {
        Window(
            // onCloseRequest runs on the AWT EDT. We can't graph.shutdown()
            // here because shutdown's awaitTermination(2s) would freeze
            // the window for that duration — Windows surfaces "Not
            // Responding" near the threshold. Move the wait to a daemon
            // thread, exitApplication immediately so the window vanishes,
            // and exitProcess(0) when shutdown completes (or 2s elapses)
            // to force JVM exit even if non-daemon OkHttp workers are
            // still alive. The daemon flag on this thread ensures it
            // doesn't itself block JVM exit.
            onCloseRequest = {
                Thread {
                    runCatching { graph.shutdown() }
                    kotlin.system.exitProcess(0)
                }.apply { isDaemon = true; name = "Talon-shutdown" }.start()
                exitApplication()
            },
            title = "Talon",
            icon = iconPainter,
        ) {
            App(
                http = graph.http,
                sessionStore = graph.sessionStore,
                aiSettings = graph.aiSettings,
                createDb = graph.createDb,
                drafts = graph.drafts,
                updateState = graph.updateState,
                createSettingsSync = graph.createSettingsSync,
                dailyDigestSettings = graph.dailyDigestSettings,
                watchwordsSync = graph.watchwordsSync,
            )
        }
    }
}
