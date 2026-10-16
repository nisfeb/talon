package io.nisfeb.talon

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.nisfeb.talon.ai.DictationRun
import io.nisfeb.talon.ui.theme.talonColors
import kotlinx.coroutines.delay

/**
 * What the owner sees of a dictation: a card over whatever was on screen,
 * the lock screen included, saying Talon is listening. It starts
 * [DictationService] (the microphone may only be opened by an app on
 * screen) and goes once the listening ends; the service carries on with
 * the rest. The widget, the tile and the assist gesture all open this.
 *
 * Android-only: no desktop analog (see [QuickDictation]).
 */
class QuickDictationActivity : ComponentActivity() {
    /** Why it cannot listen, in words for the owner; null while it can. */
    private val problem = mutableStateOf<String?>(null)

    /** The service was asked to listen. */
    private val listening = mutableStateOf(false)

    private val askForMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) listen() else problem.value = "Talon needs the microphone to hear a dictation."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this) {
            QuickDictation.cancel()
            finish()
        }
        // Not again after a recreation, or while one is under way.
        if (savedInstanceState == null && QuickDictation.level.value == null) start()
        // The app's colours without TalonTheme, which paints the window's
        // background and would hide what is behind the card.
        setContent { MaterialTheme(colorScheme = talonColors(isSystemInDarkTheme())) { Sheet() } }
    }

    private fun start() {
        val app = application as TalonApplication
        problem.value = DictationRun.blocker(app.aiSettings.state.value, app.sessionStore.activeShip() != null)
        if (problem.value != null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            listen()
        } else {
            askForMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun listen() {
        QuickDictation.listen(this)
        listening.value = true
    }

    @androidx.compose.runtime.Composable
    private fun Sheet() {
        val level by QuickDictation.level.collectAsState()
        val why by problem
        // Gone once listening ends, or if it never begins: the service
        // could not start, and says why in a notification of its own.
        var seen by remember { mutableStateOf(false) }
        LaunchedEffect(level) {
            if (level != null) seen = true else if (seen) finish()
        }
        val asked by listening
        LaunchedEffect(asked) {
            if (!asked) return@LaunchedEffect
            delay(START_WAIT_MS)
            if (!seen) finish()
        }
        Box(Modifier.fillMaxSize().navigationBarsPadding().padding(16.dp), contentAlignment = Alignment.BottomCenter) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (why != null) {
                        Text("Dictation", style = MaterialTheme.typography.titleMedium)
                        Text(why!!, style = MaterialTheme.typography.bodyMedium)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = {
                                startActivity(Intent(this@QuickDictationActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                finish()
                            }) { Text("Open Talon") }
                            TextButton(onClick = { finish() }) { Text("Close") }
                        }
                    } else {
                        Text("Listening…", style = MaterialTheme.typography.titleMedium)
                        LinearProgressIndicator(progress = { level ?: 0f }, modifier = Modifier.fillMaxWidth())
                        Text(
                            "Say it; Talon acts when you stop.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { QuickDictation.cancel(); finish() }) { Text("Cancel") }
                            TextButton(onClick = { QuickDictation.done() }) { Text("Done") }
                        }
                    }
                }
            }
        }
    }

    private companion object {
        /** How long the card waits for the microphone to open before it goes. */
        const val START_WAIT_MS = 5_000L
    }
}
