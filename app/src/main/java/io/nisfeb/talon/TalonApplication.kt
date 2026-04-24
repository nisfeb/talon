package io.nisfeb.talon

import android.app.Application
import io.nisfeb.talon.ai.AiClient
import io.nisfeb.talon.ai.AiFeatures
import io.nisfeb.talon.ai.AiSettings
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.ui.DraftStore
import io.nisfeb.talon.ui.UiSettings
import io.nisfeb.talon.urbit.SessionStore
import io.nisfeb.talon.urbit.TlonChatRepo
import io.nisfeb.talon.urbit.UrbitSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    lateinit var uiSettings: UiSettings
        private set
    lateinit var aiClient: AiClient
        private set
    lateinit var ai: AiFeatures
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
        http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS) // long-lived SSE
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
        sessionStore = SessionStore(this)
        aiSettings = AiSettings(this)
        uiSettings = UiSettings(this)
        aiClient = AiClient(settingsProvider = { aiSettings.state.value })
        ai = AiFeatures(aiClient)
        Notifications.ensureChannel(this)

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
    }

    /**
     * Build the ship-scoped instances against [ship] and install them.
     * Does not start the repo — callers do that explicitly.
     */
    private fun buildShipScoped(ship: String) {
        db = AppDatabase.build(this, ship)
        session = UrbitSession(http, sessionStore)
        // Re-hydrate the cookie jar + baseUrl from the stored session
        // for this ship (if any). Skips silently for the placeholder
        // "none" ship used pre-login.
        if (ship != "none") runCatching { session.tryRestore(ship) }
        repo = TlonChatRepo(db, aiSettings)
        drafts = DraftStore(this, ship)
        shortcuts = ShortcutsPublisher(this, db)
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
}
