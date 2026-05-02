package io.nisfeb.talon.compose

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import io.nisfeb.talon.notify.Notifier
import io.nisfeb.talon.notify.SystemNotifier
import org.jetbrains.skia.Image as SkiaImage
import io.nisfeb.talon.ai.AiSettingsRepository
import io.nisfeb.talon.ai.DailyDigestSettings
import io.nisfeb.talon.ai.DesktopDailyDigestSettings
import io.nisfeb.talon.ai.DesktopWatchwordsSyncSettings
import io.nisfeb.talon.ai.WatchwordsSyncSettings
import io.nisfeb.talon.ai.createAiSettings
import io.nisfeb.talon.ui.DesktopUiSettings
import io.nisfeb.talon.ui.UiSettings
import io.nisfeb.talon.ui.theme.DesktopThemePreference
import io.nisfeb.talon.ui.theme.ThemePreference
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
    val themePreference: ThemePreference = DesktopThemePreference()
    val uiSettings: UiSettings = DesktopUiSettings()
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
    val iconBytes = runCatching {
        ClassLoader.getSystemResourceAsStream("icon.png")?.use { it.readBytes() }
    }.getOrNull()
    val iconPainter = iconBytes?.let {
        runCatching {
            BitmapPainter(SkiaImage.makeFromEncoded(it).toComposeImageBitmap())
        }.getOrNull()
    }
    // macOS dock icon: Compose's Window(icon=...) only sets the
    // window's title-bar icon, which on macOS isn't surfaced in the
    // Dock. Without this, the running app shows the JDK's default
    // Java coffee-cup glyph in the Dock instead of the Talon logo.
    // java.awt.Taskbar is the cross-platform handle (also drives the
    // Windows taskbar icon and Linux WM hint, where a default-icon
    // fallback is also possible). Call before application { } so the
    // first frame already renders with the right icon.
    runCatching {
        if (iconBytes != null && java.awt.Taskbar.isTaskbarSupported()) {
            val img = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(iconBytes))
            if (img != null) java.awt.Taskbar.getTaskbar().iconImage = img
        }
    }

    application {
        // visible: false hides the window without tearing down the
        // composition or the rest of the dependency graph (SSE keeps
        // running, notifications keep firing). The user reopens via
        // the tray menu's "Show Talon" item or fully exits via "Quit".
        var windowVisible by remember { mutableStateOf(true) }
        val windowState = rememberWindowState()
        val trayState = rememberTrayState()
        // Tray icon for OS notifications + show/quit menu. iconPainter
        // may be null when the bundled resource fails to load — Tray
        // requires non-null, so fall back to a tiny transparent painter
        // to keep the tray entry visible.
        val trayIconPainter = iconPainter
            ?: androidx.compose.ui.graphics.painter.ColorPainter(
                androidx.compose.ui.graphics.Color.Transparent,
            )
        // Full quit path — same teardown the old onCloseRequest used:
        // background thread runs shutdown + exitProcess so the user
        // sees the window vanish immediately.
        val quitToOs: () -> Unit = {
            Thread {
                runCatching { graph.shutdown() }
                kotlin.system.exitProcess(0)
            }.apply { isDaemon = true; name = "Talon-shutdown" }.start()
            exitApplication()
        }
        Tray(
            icon = trayIconPainter,
            state = trayState,
            tooltip = "Talon",
            // Primary click on the tray icon (Linux/Windows) reopens
            // the window if it's been minimized to tray. macOS tray
            // primary-click usually opens the menu instead.
            onAction = {
                windowVisible = true
                windowState.isMinimized = false
            },
            menu = {
                Item("Show Talon", onClick = {
                    windowVisible = true
                    windowState.isMinimized = false
                })
                Item("Quit", onClick = quitToOs)
            },
        )
        // SystemNotifier shells out to notify-send / gdbus on Linux,
        // osascript on macOS — real native notifications, not the
        // ugly Swing balloon Compose's TrayState renders on Linux.
        // Windows falls through to the tray closure since AWT's
        // displayMessage already maps to native ITaskbarList3 toasts
        // there. The closure is also the universal final fallback.
        val notifier: Notifier = remember(trayState) {
            SystemNotifier(
                trayFallback = { title, body ->
                    trayState.sendNotification(
                        Notification(title = title, message = body),
                    )
                },
            )
        }
        Window(
            // Minimize to tray instead of quitting. SSE / repo / db all
            // keep running so notifications continue firing while the
            // window is hidden. The tray menu's "Quit" is the only path
            // that fully exits the JVM.
            onCloseRequest = { windowVisible = false },
            visible = windowVisible,
            state = windowState,
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
                themePreference = graph.themePreference,
                notifier = notifier,
                uiSettings = graph.uiSettings,
                createSearchEmbedderClient = { db ->
                    val embedder = io.nisfeb.talon.ai.DesktopEmbedder()
                    val indexer = io.nisfeb.talon.ai.DesktopEmbeddingIndexer(
                        db = db,
                        embedder = embedder,
                    )
                    io.nisfeb.talon.ai.DesktopSearchEmbedderClient(
                        db = db,
                        embedder = embedder,
                        indexer = indexer,
                    )
                },
                imageDownloader = io.nisfeb.talon.ui.DesktopImageDownloader(
                    http = graph.http,
                ),
            )
        }
    }
}
