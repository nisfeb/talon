package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.urbit.TlonChatRepo
import kotlinx.coroutines.launch

/**
 * The list body of [StatusFeedScreen], extracted so the desktop /
 * tablet-landscape rail can render it without the screen-level
 * header. Mobile + compact-mode wide go through [StatusFeedScreen]
 * which wraps this with the existing TopAppBar + back-arrow.
 */
@Composable
fun StatusFeedList(
    db: AppDatabase,
    repo: TlonChatRepo,
    ourPatp: String,
    onOpenContact: (ship: String) -> Unit,
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

    Column(modifier = modifier) {
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
