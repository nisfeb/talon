package io.nisfeb.talon.compose

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.nisfeb.talon.ai.createAiSettings
import io.nisfeb.talon.urbit.UrbitSession
import io.nisfeb.talon.urbit.createSessionStore
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
        setContent {
            App(session = session, sessionStore = sessionStore, aiSettings = aiSettings)
        }
    }
}
