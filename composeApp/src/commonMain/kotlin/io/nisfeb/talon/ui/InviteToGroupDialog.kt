package io.nisfeb.talon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.GroupEntity
import io.nisfeb.talon.urbit.TlonChatRepo
import kotlinx.coroutines.launch

/**
 * Invite [ship] to one of our groups: what a scanned "invite me" code
 * opens. Groups we administer come first. A member of any other group
 * may still try; its host decides, and a refusal says so here.
 */
@Composable
fun InviteToGroupDialog(
    db: AppDatabase,
    repo: TlonChatRepo,
    ship: String,
    shipName: String,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val groups by remember(db) { db.groups().streamGroups() }.collectAsState(initial = emptyList())
    val admin by repo.adminGroupsFlow.collectAsState()
    LaunchedEffect(Unit) { runCatching { repo.refreshAdminGroups() } }
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    val adminFlags = remember(admin) { admin.orEmpty().map { it.flag }.toSet() }
    val shown = remember(query, groups, adminFlags) {
        matchGroups(query, groups, limit = 100).let { m -> if (query.isBlank()) m.sortedByDescending { it.flag in adminFlags } else m }
    }
    AlertDialog(
        onDismissRequest = { if (busy == null) onDismiss() },
        title = { Text("Invite $shipName") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Find a group") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(8.dp))
                if (groups.isEmpty()) {
                    Text("You are not in any groups yet.", style = MaterialTheme.typography.bodySmall)
                }
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(shown, key = { it.flag }) { g ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = busy == null) {
                                    busy = g.flag
                                    result = null
                                    scope.launch {
                                        result = runCatching { repo.inviteToGroup(g.flag, ship) }.fold(
                                            onSuccess = { "Invited $shipName to ${g.title ?: g.flag}." },
                                            onFailure = ::inviteFailure,
                                        )
                                        busy = null
                                    }
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(g.title ?: g.flag, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    if (g.flag in adminFlags) "${g.flag} · admin" else g.flag,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (busy == g.flag) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        }
                    }
                }
                result?.let {
                    Spacer(Modifier.size(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, enabled = busy == null) { Text("Done") } },
    )
}

/** Why an invite did not go out, in words someone can act on. */
fun inviteFailure(e: Throwable): String =
    if (e is io.nisfeb.talon.urbit.PokeNacked) "Invites are not permitted for members in this group; ask an admin."
    else "Could not send the invite: ${e.message ?: "no answer from your ship"}"

/** The `/invite` group dropdown, shaped like [MentionPicker]. */
@Composable
fun GroupPicker(
    groups: List<GroupEntity>,
    onPick: (GroupEntity) -> Unit,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
) {
    if (groups.isEmpty()) return
    val sel = selectedIndex.coerceIn(0, groups.lastIndex)
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        shadowElevation = 4.dp,
    ) {
        LazyColumn(Modifier.heightIn(max = 240.dp)) {
            itemsIndexed(groups, key = { _, g -> g.flag }) { i, g ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(if (i == sel) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .clickable { onPick(g) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(
                        g.title ?: g.flag,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(g.flag, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                HorizontalDivider()
            }
        }
    }
}
