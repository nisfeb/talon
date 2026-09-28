package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.ui.Avatar
import io.nisfeb.talon.urbit.TlonChatRepo
import kotlinx.coroutines.launch

@Composable
fun GroupInvitesScreen(
    repo: TlonChatRepo,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cached by repo.invitesFlow.collectAsState()
    val invites = cached ?: emptyList()
    val joining by repo.joiningFlow.collectAsState()
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // A failed first load is an error to show, not a spinner to leave running.
    val loading = cached == null && error == null
    // An accept or decline the ship refused: its own words, since it was
    // shown as "Couldn't refresh" and read as a network problem.
    var actionError by remember { mutableStateOf<String?>(null) }
    var pendingAction by remember { mutableStateOf<Pair<String, String>?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        refreshing = cached == null
        runCatching { repo.refreshInvites() }
            .onFailure { error = it.message ?: it::class.simpleName }
        refreshing = false
    }

    Column(modifier = modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            io.nisfeb.talon.ui.NavIcon(onBack = onBack)
            Text(
                "Invites",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(start = 4.dp).weight(1f),
            )
            if (refreshing && !loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp).padding(end = 8.dp),
                    strokeWidth = 2.dp,
                )
            }
            IconButton(
                enabled = !refreshing,
                onClick = {
                    scope.launch {
                        refreshing = true
                        error = null
                        runCatching { repo.refreshInvites() }
                            .onFailure { error = it.message ?: it::class.simpleName }
                        refreshing = false
                    }
                },
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
            }
        }
        HorizontalDivider()
        when {
            loading -> Row(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalArrangement = Arrangement.Center,
            ) { CircularProgressIndicator() }

            // Full-screen error only when the cache has nothing to show;
            // a failed refresh over a populated list becomes a banner.
            error != null && invites.isEmpty() && joining.isEmpty() -> Text(
                "Couldn't load invites: $error",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(24.dp),
            )

            invites.isEmpty() && joining.isEmpty() -> Text(
                "No pending invites.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )

            else -> Column {
                listOfNotNull(error?.let { "Couldn't refresh: $it" }, actionError).forEach {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                // Accepted and not got into yet: accepting again does
                // nothing while the ship waits, so the way out is to stop.
                if (joining.isNotEmpty()) {
                    item(key = "joining-head") {
                        Text(
                            "Joining",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                        )
                    }
                    items(items = joining, key = { "j-" + it.flag }) { j ->
                        JoiningRow(
                            invite = j,
                            busy = pendingAction?.first == j.flag,
                            onCancel = {
                                pendingAction = j.flag to "cancel"
                                actionError = null
                                scope.launch {
                                    runCatching { repo.cancelJoin(j.flag) }
                                        .onFailure { actionError = "Couldn't stop joining ${j.title ?: j.flag}: ${it.message ?: it::class.simpleName}" }
                                    pendingAction = null
                                }
                            },
                        )
                        HorizontalDivider()
                    }
                }
                items(items = invites, key = { it.flag }) { inv ->
                    InviteRow(
                        invite = inv,
                        busy = pendingAction?.first == inv.flag,
                        onAccept = {
                            pendingAction = inv.flag to "accept"
                            actionError = null
                            scope.launch {
                                runCatching { repo.acceptInvite(inv.flag) }
                                    .onFailure { actionError = "Couldn't join ${inv.title ?: inv.flag}: ${it.message ?: it::class.simpleName}" }
                                pendingAction = null
                            }
                        },
                        onReject = {
                            pendingAction = inv.flag to "reject"
                            actionError = null
                            scope.launch {
                                runCatching { repo.rejectInvite(inv.flag) }
                                    .onFailure { actionError = "Couldn't decline ${inv.title ?: inv.flag}: ${it.message ?: it::class.simpleName}" }
                                pendingAction = null
                            }
                        },
                    )
                    HorizontalDivider()
                }
                }
            }
        }
    }
}

/** A group the ship is joining: whose answer it waits on, and a way to stop. */
@Composable
private fun JoiningRow(invite: TlonChatRepo.InviteSummary, busy: Boolean, onCancel: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(invite.title ?: invite.flag, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
            Text(
                "Your ship is waiting for ${invite.flag.substringBefore('/')} to let it in.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(enabled = !busy, onClick = onCancel) { Text("Stop joining") }
    }
}

@Composable
private fun InviteRow(
    invite: TlonChatRepo.InviteSummary,
    busy: Boolean,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    val isHexTint = invite.image?.startsWith("#") == true
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(
            label = invite.title ?: invite.flag,
            url = if (isHexTint) null else invite.image,
            colorHex = if (isHexTint) invite.image else null,
            size = 40.dp,
        )
        Column(Modifier.weight(1f)) {
            Text(
                invite.title ?: invite.flag,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            if (invite.failed) {
                Text(
                    "Joining it failed last time. Accept tries again.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            invite.inviter?.let {
                Text(
                    "from $it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            invite.description?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            // What you're joining, before you commit: how many people are
            // in it and whether it's public. Both come from the group
            // preview the invite already carries.
            val stats = listOfNotNull(
                invite.memberCount?.let { "$it ${if (it == 1) "member" else "members"}" },
                invite.privacy?.takeIf { it != "public" }?.replaceFirstChar(Char::uppercase),
            )
            if (stats.isNotEmpty()) {
                Text(
                    stats.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Button(enabled = !busy, onClick = onAccept) { Text("Accept") }
            OutlinedButton(enabled = !busy, onClick = onReject) { Text("Reject") }
        }
    }
}
