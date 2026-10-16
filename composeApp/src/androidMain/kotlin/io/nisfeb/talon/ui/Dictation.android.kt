package io.nisfeb.talon.ui

import android.app.Activity
import android.app.StatusBarManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.provider.Settings
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.nisfeb.talon.DictationTileService
import io.nisfeb.talon.DictationWidgetProvider
import io.nisfeb.talon.R

/** The system's speech recogniser, through its own prompt. */
@Composable
actual fun rememberDictation(onResult: (String) -> Unit): (() -> Unit)? {
    val current = rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val text = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!text.isNullOrBlank()) current.value(text)
    }
    return remember {
        {
            runCatching {
                launcher.launch(
                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_PROMPT, "Tell your assistant")
                    },
                )
            }
        }
    }
}

/** The tile, the widget and the assistant role, each asked of the system. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
actual fun QuickDictationButtons() {
    val ctx = LocalContext.current
    // Spoken highlights: the card says the gist and shows the rest.
    var speaks by remember { mutableStateOf(io.nisfeb.talon.QuickDictation.speaks(ctx)) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text("Speak answers", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Says the highlights aloud, on the phone's own voice, and asks before sending a message.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = speaks, onCheckedChange = { speaks = it; io.nisfeb.talon.QuickDictation.setSpeaks(ctx, it) })
    }
    FlowRow {
        // Below 13 there is no asking; the tile is in the Quick Settings editor.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            TextButton(onClick = {
                ctx.getSystemService(StatusBarManager::class.java)?.requestAddTileService(
                    ComponentName(ctx, DictationTileService::class.java),
                    ctx.getString(R.string.dictate_label),
                    Icon.createWithResource(ctx, R.drawable.ic_mic),
                    ctx.mainExecutor,
                ) {}
            }) { Text("Add tile") }
        }
        val widgets = AppWidgetManager.getInstance(ctx)
        if (widgets.isRequestPinAppWidgetSupported) {
            TextButton(onClick = {
                widgets.requestPinAppWidget(ComponentName(ctx, DictationWidgetProvider::class.java), null, null)
            }) { Text("Add widget") }
        }
        // The assistant role cannot be asked for; the owner picks it in
        // the system's default apps, where Talon is listed.
        TextButton(onClick = {
            runCatching { ctx.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .recoverCatching { ctx.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }) { Text("Assistant settings") }
    }
}
