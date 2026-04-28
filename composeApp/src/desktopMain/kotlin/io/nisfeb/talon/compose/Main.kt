package io.nisfeb.talon.compose

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.nisfeb.talon.ai.createAiSettings
import io.nisfeb.talon.urbit.UrbitSession
import io.nisfeb.talon.urbit.createSessionStore
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

fun main() = application {
    val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
    val sessionStore = createSessionStore()
    val session = UrbitSession(http, sessionStore)
    val aiSettings = createAiSettings()
    Window(onCloseRequest = ::exitApplication, title = "Talon") {
        App(session = session, sessionStore = sessionStore, aiSettings = aiSettings)
    }
}
