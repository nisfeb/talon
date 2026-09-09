package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.Badge
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.call.CallController
import io.nisfeb.talon.call.PartyLine
import io.nisfeb.talon.call.PartyState
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.ui.ContactMap
import io.nisfeb.talon.ui.PartyLineRow
import io.nisfeb.talon.ui.partyLineRows
import io.nisfeb.talon.ui.partyRollCall
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Every party line we know about across our groups, with who is on
 * each. Tapping a row lands in that group's most recent channel and
 * joins the line, exactly like the channel header's button.
 *
 * [onBack] null renders the wide-layout pane variant (no header arrow),
 * mirroring StatusFeedScreen / StatusFeedList.
 */
@Composable
fun PartyLinesScreen(
    db: AppDatabase,
    callController: CallController?,
    partyLine: PartyLine?,
    contacts: ContactMap,
    onOpenLine: (whom: String) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val rooms by remember(callController) {
        callController?.rooms ?: MutableStateFlow(emptyMap())
    }.collectAsState()
    val invites by remember(callController) {
        callController?.invites ?: MutableStateFlow(emptyMap())
    }.collectAsState()
    val presence by remember(callController) {
        callController?.presence ?: MutableStateFlow(emptyMap())
    }.collectAsState()
    val onLine by remember(callController) {
        callController?.onLine ?: MutableStateFlow(emptyMap())
    }.collectAsState()
    val live = partyLine?.state?.collectAsState()?.value as? PartyState.Live
    val latest by remember(db) { db.messages().conversationLatest() }
        .collectAsState(initial = emptyList())
    val groups = contacts.allGroups()
    val rows = remember(rooms, invites, groups) { partyLineRows(rooms, invites, groups) }

    // Refresh counts and names while the list is on screen. Old hosts
    // answer the count only; older own-ships answer neither.
    LaunchedEffect(rows.map { it.key }) {
        while (true) {
            for (r in rows) {
                callController?.occupancyOf(r.host, r.name)
                callController?.whoIsOn(r.host, r.name)
            }
            delay(20_000)
        }
    }

    Column(modifier = modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
            Text(
                "Party lines",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(start = if (onBack != null) 4.dp else 8.dp),
            )
        }
        HorizontalDivider()
        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "No party lines yet. A group admin turns one on from the group's info page.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(rows, key = { it.key }) { row ->
                val ships = live?.takeIf { it.room == row.name }?.members?.map { it.ship }
                    ?: onLine[row.key].orEmpty().toList()
                val count = maxOf(presence[row.key] ?: 0, ships.size)
                // The channel to land in: the group's most recently active
                // one, per the same latest-message feed the chat list uses.
                val whom = row.groupFlag?.let { flag ->
                    val channels = contacts.channelsOfGroup(flag)
                    latest.firstOrNull { it.whom in channels }?.whom ?: channels.firstOrNull()
                }
                PartyLineListRow(
                    row = row,
                    count = count,
                    detail = partyRollCall(count, ships) { contacts.displayName(it) },
                    onClick = whom?.let { { onOpenLine(it) } },
                )
            }
        }
    }
}

@Composable
private fun PartyLineListRow(
    row: PartyLineRow,
    count: Int,
    detail: String,
    onClick: (() -> Unit)?,
) {
    ListItem(
        headlineContent = { Text(row.title) },
        supportingContent = {
            Text(
                if (onClick == null) "$detail. Join the group to open this line." else detail,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingContent = { Icon(Icons.Filled.Call, contentDescription = null) },
        trailingContent = { if (count > 0) Badge { Text("$count") } },
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    )
}
