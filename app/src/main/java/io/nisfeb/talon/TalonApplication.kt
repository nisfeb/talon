package io.nisfeb.talon

import android.app.Application
import io.nisfeb.talon.ai.AiClient
import io.nisfeb.talon.ai.AiFeatures
import io.nisfeb.talon.ai.AiSettings
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.ui.DraftStore
import io.nisfeb.talon.urbit.SessionStore
import io.nisfeb.talon.urbit.TlonChatRepo
import io.nisfeb.talon.urbit.UrbitSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class TalonApplication : Application() {
    lateinit var http: OkHttpClient
        private set
    lateinit var db: AppDatabase
        private set
    lateinit var sessionStore: SessionStore
        private set
    lateinit var session: UrbitSession
        private set
    lateinit var repo: TlonChatRepo
        private set
    lateinit var drafts: DraftStore
        private set
    lateinit var shortcuts: ShortcutsPublisher
        private set
    lateinit var aiSettings: AiSettings
        private set
    lateinit var aiClient: AiClient
        private set
    lateinit var ai: AiFeatures
        private set

    override fun onCreate() {
        super.onCreate()
        http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS) // long-lived SSE
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
        db = AppDatabase.build(this)
        sessionStore = SessionStore(this)
        session = UrbitSession(http, sessionStore)
        aiSettings = AiSettings(this)
        aiClient = AiClient(settingsProvider = { aiSettings.state.value })
        ai = AiFeatures(aiClient)
        repo = TlonChatRepo(db, aiSettings)
        drafts = DraftStore(this)
        shortcuts = ShortcutsPublisher(this, db)
        Notifications.ensureChannel(this)

        // Wire AI settings changes to %settings sync. Fires on every
        // AiSettings mutation; SettingsSync checks syncEnabled and
        // routes to the right behavior (push, no-op, or clear).
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
}
