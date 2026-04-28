package io.nisfeb.talon

import android.app.Application
import io.nisfeb.talon.ai.AiClient
import io.nisfeb.talon.ai.AiFeatures
import io.nisfeb.talon.ai.AiSettings
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.ui.DraftStore
import io.nisfeb.talon.ui.ShipProfileStore
import io.nisfeb.talon.ui.UiSettings
import io.nisfeb.talon.update.HttpUpdateChecker
import io.nisfeb.talon.update.UpdateInstaller
import io.nisfeb.talon.update.UpdateState
import io.nisfeb.talon.urbit.SessionStore
import io.nisfeb.talon.urbit.TlonChatRepo
import io.nisfeb.talon.urbit.UrbitSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class TalonApplication : Application() {
    // Always-on singletons — not ship-scoped.
    lateinit var http: OkHttpClient
        private set
    lateinit var sessionStore: SessionStore
        private set
    lateinit var aiSettings: AiSettings
        private set
    lateinit var dailyDigestSettings: io.nisfeb.talon.ai.DailyDigestSettings
        private set
    lateinit var uiSettings: UiSettings
        private set
    lateinit var shipProfiles: ShipProfileStore
        private set
    lateinit var aiClient: AiClient
        private set
    lateinit var ai: AiFeatures
        private set
    lateinit var embedder: io.nisfeb.talon.ai.Embedder
        private set
    lateinit var dailyDigest: io.nisfeb.talon.ai.DailyDigest
        private set
    lateinit var updateState: UpdateState
        private set

    // Ship-scoped — rebuilt on ship switch. lateinit so the first
    // access after startup is valid (initialized in onCreate for the
    // last-active ship, or a placeholder when none is logged in).
    lateinit var db: AppDatabase
        private set
    lateinit var session: UrbitSession
        private set
    lateinit var repo: TlonChatRepo
        private set
    lateinit var drafts: DraftStore
        private set
    lateinit var shortcuts: ShortcutsPublisher
        private set
    lateinit var embeddingIndexer: io.nisfeb.talon.ai.EmbeddingIndexer
        private set
    lateinit var watchwords: io.nisfeb.talon.ai.Watchwords
        private set

    // Both lazy so neither touches Context until after attachBaseContext()
    // / onCreate() — eager property initializers run during the
    // Application constructor, before Context is wired up, and
    // getSharedPreferences would NPE.
    private val watchwordsPrefs by lazy {
        getSharedPreferences("talon_watchwords", MODE_PRIVATE)
    }

    private val _watchwordsSyncEnabled by lazy {
        MutableStateFlow(watchwordsPrefs.getBoolean(KEY_WATCHWORDS_SYNC, false))
    }
    val watchwordsSyncEnabled: StateFlow<Boolean>
        get() = _watchwordsSyncEnabled.asStateFlow()

    fun setWatchwordsSyncEnabled(enabled: Boolean) {
        if (_watchwordsSyncEnabled.value == enabled) return
        watchwordsPrefs.edit().putBoolean(KEY_WATCHWORDS_SYNC, enabled).apply()
        _watchwordsSyncEnabled.value = enabled
        watchwords.emitSyncToggled()
    }

    private val _activeShip = MutableStateFlow<String?>(null)
    /** Active ship patp, or null if none logged in. Changes on switch
     *  so UI can re-key its tree and pick up the new ship's data. */
    val activeShipFlow: StateFlow<String?> = _activeShip.asStateFlow()

    private val _allShips = MutableStateFlow<List<String>>(emptyList())
    /** Every logged-in ship's patp. Populated from SessionStore and
     *  kept in sync by the login / switch / signout paths. */
    val allShipsFlow: StateFlow<List<String>> = _allShips.asStateFlow()

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Cookie-jar-bearing client used by UrbitSession + S3Uploader.
        // Coil does NOT use this — coil-network-okhttp registers its
        // own default OkHttpClient via ServiceLoader. That's fine
        // today because every image URL Talon loads (Tlon avatars,
        // OG previews, S3 attachments) is public, no urbauth cookie
        // required. If anyone ever adds a Coil callsite for a
        // resource behind the ship's cookie wall, that callsite will
        // need its own ImageLoader configured with this client.
        http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS) // long-lived SSE
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
        sessionStore = SessionStore(this)
        aiSettings = AiSettings(this)
        dailyDigestSettings = io.nisfeb.talon.ai.DailyDigestSettings(this)
        uiSettings = UiSettings(this)
        shipProfiles = ShipProfileStore(this)
        aiClient = AiClient(settingsProvider = { aiSettings.state.value })
        ai = AiFeatures(aiClient)
        embedder = io.nisfeb.talon.ai.Embedder(this)
        Notifications.ensureChannel(this)
        updateState = UpdateState(
            context = this,
            scope = appScope,
            installer = UpdateInstaller(this),
        )
        val updatePrefs = getSharedPreferences("update_state", MODE_PRIVATE)
        val httpChecker = HttpUpdateChecker(
            http = http,
            url = "https://github.com/sneagan/talon/releases/latest/download/latest.json",
            now = { System.currentTimeMillis() },
            lastCheckedAtMs = { updatePrefs.getLong("last_http_check_ms", 0L) },
            recordCheckedAt = { updatePrefs.edit().putLong("last_http_check_ms", it).apply() },
            minIntervalMs = 12L * 60L * 60L * 1000L,
        )
        appScope.launch {
            val m = httpChecker.check()
            if (m != null) updateState.onManifest(m)
        }

        // Pre-warm the ML Kit Entity Extraction model so the first
        // chat to render isn't blocked on a one-off ~12MB download.
        // Best-effort; failures (no Play Services, etc.) just mean
        // chips don't appear until the model lands later.
        runCatching { io.nisfeb.talon.ai.EntityActions.warmup() }

        // Pick the ship to open with — the most-recently-active one,
        // or the single saved ship if exactly one exists, or the
        // first in the list as a safe fallback.
        val initialShip = sessionStore.activeShip()
            ?: sessionStore.all().firstOrNull()?.ship
        refreshAllShips()
        // Always instantiate the ship-scoped fields so downstream code
        // can `lateinit` access them. When no ship is saved we fall
        // through to a synthetic "none" bucket — the UI shows the
        // login screen before anyone touches these values.
        val shipForInit = initialShip ?: "none"
        buildShipScoped(shipForInit)
        _activeShip.value = initialShip

        dailyDigest = io.nisfeb.talon.ai.DailyDigest(
            context = this,
            sessionStore = sessionStore,
            activeShipFlow = activeShipFlow,
            getDb = { db },
            aiSettings = aiSettings,
            aiClient = aiClient,
            settings = dailyDigestSettings,
            http = http,
            scope = appScope,
            receiverClass = io.nisfeb.talon.DigestAlarmReceiver::class.java,
        )

        // Wire AI settings changes to %settings sync. Fires on every
        // AiSettings mutation; SettingsSync checks syncEnabled and
        // routes to the right behavior (push, no-op, or clear).
        aiSettings.onStateChange = { cfg, transitionedOffSync ->
            appScope.launch {
                runCatching {
                    when {
                        transitionedOffSync -> repo.settingsSync.clearAiSettingsOnShip()
                        cfg.syncEnabled -> repo.settingsSync.pushAiSettings()
                    }
                }
            }
        }

        watchwords.onChange = { evt, transitionedOffSync ->
            appScope.launch {
                runCatching {
                    when {
                        transitionedOffSync ->
                            repo.settingsSync.clearWatchwordsOnShip()
                        _watchwordsSyncEnabled.value -> when (evt) {
                            is io.nisfeb.talon.ai.WatchwordChange.Upsert ->
                                repo.settingsSync.pushWatchwordEntry(evt.term)
                            is io.nisfeb.talon.ai.WatchwordChange.Remove ->
                                repo.settingsSync.deleteWatchwordEntry(evt.termText)
                            is io.nisfeb.talon.ai.WatchwordChange.Exclude ->
                                repo.settingsSync.pushWatchwordExclude(evt.whom)
                            is io.nisfeb.talon.ai.WatchwordChange.Unexclude ->
                                repo.settingsSync.deleteWatchwordExclude(evt.whom)
                            is io.nisfeb.talon.ai.WatchwordChange.SyncToggled ->
                                repo.settingsSync.pushAllWatchwords()
                        }
                        else -> Unit
                    }
                }
            }
        }

        dailyDigestSettings.onChange = { evt, transitionedOffSync ->
            appScope.launch {
                runCatching {
                    when {
                        transitionedOffSync -> repo.settingsSync.clearDailyDigestOnShip()
                        else -> repo.settingsSync.pushDailyDigest(dailyDigestSettings.state.value)
                    }
                }
                // Re-arm on toggle / time change.
                runCatching { dailyDigest.scheduleNext() }
            }
        }

        // Arm the alarm if the user has enabled it (and re-arm on every
        // app start — belt-and-suspenders against the receiver being killed
        // before it finished re-arming yesterday).
        runCatching { dailyDigest.scheduleNext() }
    }

    /**
     * Build the ship-scoped instances against [ship] and install them.
     * Does not start the repo — callers do that explicitly.
     *
     * If a previous ship's instances are alive, hand them to
     * [scheduleShipScopedTeardown] for delayed close. We can't close
     * the old AppDatabase synchronously here because the UI is still
     * collecting Flows from `app.db` until `_activeShip.value` flips
     * and `key(loggedInShip) { … }` re-keys the tree — closing the
     * pool out from under live collectors throws SQLiteException
     * spam. The deferred close gives the recomposition a frame or two
     * to drop the old subscribers, then reclaims the pool — fixing
     * the `SQLiteConnectionPool: connection was leaked` warning that
     * fired on every ship-switch.
     */
    private fun buildShipScoped(ship: String) {
        val priorDb = if (::db.isInitialized) db else null
        val priorIndexer = if (::embeddingIndexer.isInitialized) embeddingIndexer else null

        db = AppDatabase.build(this, ship)
        session = UrbitSession(http, sessionStore)
        // Re-hydrate the cookie jar + baseUrl from the stored session
        // for this ship (if any). Skips silently for the placeholder
        // "none" ship used pre-login.
        if (ship != "none") runCatching { session.tryRestore(ship) }
        repo = TlonChatRepo(db, aiSettings, dailyDigestSettings, rearmDailyDigest = {
            // `dailyDigest` is lateinit and built later in onCreate; this
            // lambda only fires from inbound %settings events long after
            // initialization, so the runtime guard is sufficient.
            runCatching { dailyDigest.scheduleNext() }
        })
        watchwords = io.nisfeb.talon.ai.Watchwords(
            db = db,
            ourPatpProvider = { ship.takeIf { it != "none" } ?: "" },
            scope = appScope,
            syncEnabledProvider = { _watchwordsSyncEnabled.value },
        )
        drafts = DraftStore(this, ship)
        shortcuts = ShortcutsPublisher(this, db)
        embeddingIndexer = io.nisfeb.talon.ai.EmbeddingIndexer(db, embedder, appScope)

        if (priorDb != null || priorIndexer != null) {
            scheduleShipScopedTeardown(priorDb, priorIndexer)
        }
    }

    /**
     * Wait for the UI to drop the prior ship's collectors, then close
     * the prior `AppDatabase` and stop the prior embedding indexer. A
     * 2s delay covers the typical re-keying frame plus any in-flight
     * suspend Room call returning. Running on appScope (IO supervisor)
     * means the cleanup survives the ship-switch caller returning.
     */
    private fun scheduleShipScopedTeardown(
        priorDb: AppDatabase?,
        priorIndexer: io.nisfeb.talon.ai.EmbeddingIndexer?,
    ) {
        appScope.launch {
            delay(2_000)
            runCatching { priorIndexer?.stop() }
            runCatching { priorDb?.close() }
        }
    }

    /**
     * Switch the active ship. Tears down the current repo / shortcuts,
     * rebuilds the ship-scoped instances against [ship], and notifies
     * observers. The calling UI is expected to re-key its tree on
     * [activeShipFlow] so the new ship's data is picked up cleanly.
     *
     * Pass a patp that's already present in [SessionStore]. For a fresh
     * login, save the session first (via `session.login`) and THEN
     * call this.
     */
    fun switchShip(ship: String) {
        if (ship == _activeShip.value) return
        runCatching { repo.stop() }
        runCatching { shortcuts.stop() }
        sessionStore.setActive(ship)
        buildShipScoped(ship)
        refreshAllShips()
        // Process-wide home-list cache is ship-specific. Flush so the
        // new ship's first paint doesn't briefly show the prior ship's
        // rows / previews / counts.
        io.nisfeb.talon.ui.screens.resetHomeListSnapshot()
        _activeShip.value = ship
    }

    /**
     * Signal a sign-out for the currently active ship. Stops the
     * current repo / shortcuts and falls back to the next saved ship
     * (if any). If none remain, leaves the app unbound so the UI can
     * show the login screen.
     */
    fun signOutActive() {
        runCatching { repo.stop() }
        runCatching { shortcuts.stop() }
        session.logout()
        refreshAllShips()
        io.nisfeb.talon.ui.screens.resetHomeListSnapshot()
        val next = sessionStore.activeShip() ?: sessionStore.all().firstOrNull()?.ship
        if (next != null) {
            buildShipScoped(next)
            sessionStore.setActive(next)
            _activeShip.value = next
        } else {
            // Leave the lateinit fields pointing at the previous "none"
            // placeholder; the tree won't touch them while it renders
            // the login screen.
            _activeShip.value = null
        }
    }

    /**
     * After a fresh successful login for [ship], rebuild ship-scoped
     * state and make it active. Mirrors [switchShip] but tolerates
     * the case where the ship just became known.
     */
    fun onShipLoggedIn(ship: String) {
        runCatching { repo.stop() }
        runCatching { shortcuts.stop() }
        buildShipScoped(ship)
        sessionStore.setActive(ship)
        refreshAllShips()
        io.nisfeb.talon.ui.screens.resetHomeListSnapshot()
        _activeShip.value = ship
    }

    private fun refreshAllShips() {
        _allShips.value = sessionStore.all().map { it.ship }
    }

    private companion object {
        private const val KEY_WATCHWORDS_SYNC = "sync_enabled"
    }
}
