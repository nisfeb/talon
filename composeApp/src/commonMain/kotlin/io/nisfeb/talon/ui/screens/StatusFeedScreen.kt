// TEMPORARY DUPLICATE of app/src/main/java/io/nisfeb/talon/ui/screens/StatusFeedScreen.kt
// Verbatim copy — pure Compose, no Android-only deps. Keep in sync
// with production until app/ is removed in Stage F.
package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.ContactEntity
import io.nisfeb.talon.ui.Avatar
import io.nisfeb.talon.urbit.TlonChatRepo
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Rolling feed of contact statuses sorted by when we last noticed
 * them change. Tap a row to open that peer's profile sheet (handled
 * by the caller).
 */
@Composable
fun StatusFeedScreen(
    db: AppDatabase,
    repo: TlonChatRepo,
    ourPatp: String,
    onOpenContact: (ship: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contacts by remember {
        db.contacts().streamStatusFeed()
    }.collectAsState(initial = emptyList())
    // Stream our own contact row so the header reflects edits made from
    // this screen the moment the optimistic upsert lands.
    val self by remember(ourPatp) {
        db.contacts().streamOne(ourPatp)
    }.collectAsState(initial = null)

    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }

    Column(modifier = modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                "Statuses",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        HorizontalDivider()
        SelfStatusRow(
            self = self,
            ourPatp = ourPatp,
            onEdit = { editing = true },
        )
        HorizontalDivider()
        if (contacts.isEmpty()) {
            Text(
                "No statuses yet. They'll show up here as your contacts update theirs.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(items = contacts, key = { it.ship }) { c ->
                    StatusRow(c) { onOpenContact(c.ship) }
                    HorizontalDivider()
                }
            }
        }
    }

    if (editing) {
        EditStatusDialog(
            initial = self?.status.orEmpty(),
            onDismiss = { editing = false },
            onSave = { next ->
                editing = false
                scope.launch {
                    runCatching { repo.updateProfile(status = next) }
                }
            },
        )
    }
}

@Composable
private fun SelfStatusRow(
    self: ContactEntity?,
    ourPatp: String,
    onEdit: () -> Unit,
) {
    val label = self?.nickname?.takeIf { !it.isNullOrBlank() } ?: ourPatp
    val status = self?.status?.takeIf { !it.isNullOrBlank() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(
            label = label,
            url = self?.avatarUrl,
            colorHex = self?.color,
            size = 40.dp,
        )
        Column(Modifier.weight(1f)) {
            Text(
                "Your status",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                status ?: "Tap to set a status",
                style = MaterialTheme.typography.bodyMedium,
                color = if (status != null) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.Filled.Edit,
            contentDescription = "Edit status",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EditStatusDialog(
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    // Field opens empty — the user is replacing the status, not
    // editing in place. The current value sits in the placeholder so
    // they can see what they're replacing without having to delete it.
    var field by remember { mutableStateOf(TextFieldValue("")) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val placeholder = initial.takeIf { it.isNotBlank() }
        ?: "e.g. heads-down on a thing"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Update status") },
        text = {
            OutlinedTextField(
                value = field,
                onValueChange = { field = it },
                placeholder = { Text(placeholder) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(field.text.trim()) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun StatusRow(c: ContactEntity, onClick: () -> Unit) {
    val label = c.nickname ?: c.ship
    val stamp = remember(c.statusUpdatedMs) {
        c.statusUpdatedMs?.let { formatRelative(it) }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(label = label, url = c.avatarUrl, size = 40.dp)
        Column(Modifier.weight(1f)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                )
                if (!c.nickname.isNullOrBlank()) {
                    Text(
                        c.ship,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            c.status?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        stamp?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val TIME_TODAY = SimpleDateFormat("HH:mm", Locale.getDefault())
private val DATE_OLD = SimpleDateFormat("MMM d", Locale.getDefault())

private fun formatRelative(ms: Long): String {
    val diff = System.currentTimeMillis() - ms
    return when {
        diff < 60_000L -> "now"
        diff < 3600_000L -> "${diff / 60_000L}m"
        diff < 24 * 3600_000L -> TIME_TODAY.format(Date(ms))
        else -> DATE_OLD.format(Date(ms))
    }
}
