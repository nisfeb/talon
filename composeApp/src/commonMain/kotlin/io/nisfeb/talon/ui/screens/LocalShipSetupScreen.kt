package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.comet.LocalShip
import io.nisfeb.talon.comet.LocalShipState

/**
 * First-run setup of a comet on this computer: fetch the runtime,
 * boot, and hand the [LocalShipState.Ready] to the caller, who logs
 * in with it. Desktop only, gated upstream by `isLocalCometSupported`.
 */
@Composable
fun LocalShipSetupScreen(
    localShip: LocalShip,
    onReady: (LocalShipState.Ready) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by localShip.state.collectAsState()
    var attempt by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(attempt) {
        error = null
        runCatching { localShip.setup() }
            .onSuccess(onReady)
            .onFailure { error = it.message ?: it::class.simpleName }
    }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = 440.dp).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Setting up your ship",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
            )
            Text(
                "A comet is an Urbit identity that lives on this computer. " +
                    "It is yours as long as this computer keeps it; Talon runs it whenever the app is open.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            val failed = error ?: (state as? LocalShipState.Failed)?.why
            when {
                failed != null -> {
                    Text(
                        failed,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    Button(onClick = { attempt++ }) { Text("Try again") }
                }
                state is LocalShipState.Downloading -> {
                    val d = state as LocalShipState.Downloading
                    val total = d.total
                    if (total != null && total > 0) {
                        LinearProgressIndicator(
                            progress = { (d.bytes.toFloat() / total).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Text(
                        "Downloading the Urbit runtime, ${d.bytes / 1_048_576} MB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state is LocalShipState.Booting -> {
                    val b = state as LocalShipState.Booting
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        if (b.firstBoot) "Booting the ship. This takes a couple of minutes the first time."
                        else "Starting the ship.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        b.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                    )
                }
                state is LocalShipState.Ready -> {
                    Text("Signing in…", style = MaterialTheme.typography.bodyMedium)
                }
                else -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}
