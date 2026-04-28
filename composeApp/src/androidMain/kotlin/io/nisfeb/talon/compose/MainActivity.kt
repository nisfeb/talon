package io.nisfeb.talon.compose

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.nisfeb.talon.ai.createAiSettings
import io.nisfeb.talon.data.createAppDatabase
import io.nisfeb.talon.ui.InMemoryDraftStore
import io.nisfeb.talon.update.NoopUpdateInstallerHook
import io.nisfeb.talon.update.StaticUpdateRuntime
import io.nisfeb.talon.update.UpdateState
import io.nisfeb.talon.urbit.TlonChatRepo
import io.nisfeb.talon.urbit.UrbitSession
import io.nisfeb.talon.urbit.createSessionStore
import kotlinx.coroutines.MainScope
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS) // long-lived SSE
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
        val sessionStore = createSessionStore(applicationContext)
        val session = UrbitSession(http, sessionStore)
        val aiSettings = createAiSettings(applicationContext)
        val db = createAppDatabase(applicationContext, "talon-port.db")
        val repo = TlonChatRepo(db = db)
        val drafts = InMemoryDraftStore()
        val updateState = UpdateState(
            scope = MainScope(),
            runtime = StaticUpdateRuntime(),
            installer = NoopUpdateInstallerHook(),
        )
        setContent {
            App(
                session = session,
                sessionStore = sessionStore,
                aiSettings = aiSettings,
                db = db,
                repo = repo,
                drafts = drafts,
                updateState = updateState,
            )
        }
    }
}
