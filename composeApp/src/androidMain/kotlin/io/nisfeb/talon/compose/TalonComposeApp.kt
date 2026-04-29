package io.nisfeb.talon.compose

import android.app.Application
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
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Process-scoped DI for the composeApp Android target. Holds every
 * dependency that should outlive an Activity:
 *   - http: OkHttp's dispatcher pool would leak per-rotation if
 *     constructed in MainActivity.onCreate
 *   - sessionStore / aiSettings: backed by EncryptedSharedPreferences;
 *     must be a process singleton or you race the keystore
 *   - db: Room.databaseBuilder().build() does NOT cache by name, so
 *     constructing per-Activity opens multiple connection pools
 *     against the same SQLite file
 *   - drafts: in-memory only, but must persist across Activity
 *     recreations (the user's typed-but-unsent message)
 *   - updateState + updateScope: long-lived background work
 *
 * UrbitSession and TlonChatRepo are NOT held here — they're
 * ship-scoped, rebuilt by App()'s `key(loggedInShip)` block on
 * sign-out / sign-in to keep TlonChatRepo's `started` short-circuit
 * from stranding the second login.
 */
class TalonComposeApp : Application() {
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            // long-lived SSE — no read timeout
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }
    val sessionStore: SessionStore by lazy { createSessionStore(applicationContext) }
    val aiSettings: AiSettingsRepository by lazy { createAiSettings(applicationContext) }
    val db: AppDatabase by lazy { createAppDatabase(applicationContext, "talon-port.db") }
    val drafts: DraftStore = InMemoryDraftStore()

    private val updateScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val updateState: UpdateState by lazy {
        UpdateState(updateScope, StaticUpdateRuntime(), NoopUpdateInstallerHook())
    }
}
