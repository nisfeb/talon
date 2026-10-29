package io.nisfeb.talon.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.ui.icons.TalonIcons
import io.nisfeb.talon.urbit.TlonChatRepo
import io.nisfeb.talon.util.nowMs
import kotlinx.coroutines.delay

/** How long the chat list waits before it says something is wrong; shortened in tests. */
val LocalSyncThresholds = staticCompositionLocalOf { SyncThresholds() }

/**
 * [repo]'s [SyncStatus], read every few seconds: the stream's freshness
 * is a property, not a flow. Read inside the small composables below,
 * so only they recompose on the tick, not the chat list.
 */
@Composable
fun rememberSyncStatus(repo: TlonChatRepo): SyncStatus {
    val signedOut by repo.signedOut.collectAsState()
    val syncing by repo.bootstrapping.collectAsState()
    val pending by remember(repo) { repo.queuedWrites }.collectAsState(initial = 0)
    val thresholds = LocalSyncThresholds.current
    val now by produceState(nowMs(), thresholds) {
        while (true) {
            value = nowMs()
            delay(minOf(SYNC_TICK_MS, thresholds.heardRecentlyMs / 2))
        }
    }
    return syncStatus(signedOut, repo.lastHeardMs(), repo.startedMs, now, syncing, pending, thresholds)
}

private const val SYNC_TICK_MS = 3_000L

/**
 * The header's sync mark: a cloud with a tick when connected, arrows
 * while syncing or sending, struck through when the ship is out of
 * reach or the login has lapsed. A tap says what it knows and offers
 * what helps.
 */
@Composable
fun SyncStatusIcon(repo: TlonChatRepo, ship: String, onSignIn: (() -> Unit)?) {
    val status = rememberSyncStatus(repo)
    val handle = shipHandle(ship)
    val headline = syncHeadline(status, handle, nowMs())
    var open by remember { mutableStateOf(false) }
    val problem = status is SyncStatus.Unreachable || status is SyncStatus.SignedOut
    Box {
        IconButton(tip = headline, onClick = { open = true }) {
            Icon(
                when {
                    problem -> TalonIcons.CloudOff
                    status is SyncStatus.Connected && !status.syncing && status.pending == 0 -> TalonIcons.CloudDone
                    else -> TalonIcons.CloudSync
                },
                contentDescription = headline,
                tint = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(Modifier.widthIn(max = 320.dp).padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(headline, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                syncDetail(status).takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SyncAction(status, repo, onSignIn) { open = false }
            }
        }
    }
}

/**
 * The line under the header while something is wrong, kept up until it
 * is put right: "Can't reach ~zod since 2:02 PM · Try now", or the
 * lapsed login's "Sign in". Nothing while all is well.
 */
@Composable
fun SyncStatusBanner(repo: TlonChatRepo, ship: String, onSignIn: (() -> Unit)?) {
    val status = rememberSyncStatus(repo)
    if (status !is SyncStatus.Unreachable && status !is SyncStatus.SignedOut) return
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(TalonIcons.CloudOff, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(syncHeadline(status, shipHandle(ship), nowMs()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                Text(syncDetail(status), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            }
            SyncAction(status, repo, onSignIn) {}
        }
    }
}

/** What helps: try now for a ship out of reach, sign in for a lapsed login. */
@Composable
private fun SyncAction(status: SyncStatus, repo: TlonChatRepo, onSignIn: (() -> Unit)?, done: () -> Unit) {
    when (status) {
        is SyncStatus.Unreachable -> TextButton(onClick = { done(); repo.forceReconnect() }) { Text("Try now") }
        SyncStatus.SignedOut -> if (onSignIn != null) TextButton(onClick = { done(); onSignIn() }) { Text("Sign in") }
        else -> Unit
    }
}
