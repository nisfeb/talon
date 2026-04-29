package io.nisfeb.talon.compose

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.nisfeb.talon.ai.createAiSettings
import io.nisfeb.talon.data.createAppDatabase
import io.nisfeb.talon.ui.InMemoryDraftStore
import io.nisfeb.talon.update.NoopUpdateInstallerHook
import io.nisfeb.talon.update.StaticUpdateRuntime
import io.nisfeb.talon.update.UpdateState
import io.nisfeb.talon.urbit.createSessionStore
import kotlinx.coroutines.MainScope
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

fun main() = application {
    val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // long-lived SSE
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
    val sessionStore = createSessionStore()
    val aiSettings = createAiSettings()
    val db = createAppDatabase()
    val drafts = InMemoryDraftStore()
    val updateState = UpdateState(
        scope = MainScope(),
        runtime = StaticUpdateRuntime(),
        installer = NoopUpdateInstallerHook(),
    )
    Window(onCloseRequest = ::exitApplication, title = "Talon") {
        App(
            http = http,
            sessionStore = sessionStore,
            aiSettings = aiSettings,
            db = db,
            drafts = drafts,
            updateState = updateState,
        )
    }
}
