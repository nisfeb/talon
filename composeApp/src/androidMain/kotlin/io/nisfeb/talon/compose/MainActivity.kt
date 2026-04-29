package io.nisfeb.talon.compose

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // All long-lived deps come from the process-scoped
        // TalonComposeApp graph so configuration changes (rotation,
        // theme switch, locale) don't leak OkHttp dispatchers,
        // Room connection pools, or the SSE drain.
        val app = application as TalonComposeApp
        setContent {
            App(
                http = app.http,
                sessionStore = app.sessionStore,
                aiSettings = app.aiSettings,
                db = app.db,
                drafts = app.drafts,
                updateState = app.updateState,
            )
        }
    }
}
