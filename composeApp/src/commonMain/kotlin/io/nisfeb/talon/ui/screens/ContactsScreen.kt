package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.ContactEntity
import io.nisfeb.talon.ui.Avatar
import io.nisfeb.talon.urbit.PATP_REGEX

/**
 * Curated contact book — the ships in our %contacts `/v1/book`, not
 * the broad `/v1/all` peer directory. Lists book members (searchable),
 * adds a ship by ~patp (+ optional nickname), opens a member's profile,
 * and removes a member.
 *
 * [bookContacts] is the set of `~ship` patps in the book (from
 * `TlonChatRepo.bookContacts`); contact display data is read from the
 * `contacts` table and filtered to that set.
 */
@Composable
fun ContactsScreen(
    db: AppDatabase,
    bookContacts: Set<String>,
    onAddContact: (patp: String, nickname: String?) -> Unit,
    onRemoveContact: (patp: String) -> Unit,
    onOpenContact: (patp: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val allContacts by remember { db.contacts().stream() }.collectAsState(initial = emptyList())
    var query by remember { mutableStateOf("") }
    var newPatp by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf("") }

    val asPatp = run {
        val t = newPatp.trim()
        if (t.startsWith("~")) t else "~$t"
    }
    val isValidPatp = newPatp.isNotBlank() && PATP_REGEX.matches(asPatp)
    val alreadyInBook = asPatp in bookContacts

    // Book members, joined to their cached contact rows, filtered by search.
    val members = remember(allContacts, bookContacts, query) {
        val q = query.trim().lowercase().removePrefix("~")
        allContacts
            .filter { it.ship in bookContacts }
            .filter {
                q.isEmpty() ||
                    it.ship.lowercase().removePrefix("~").contains(q) ||
                    (it.nickname?.lowercase()?.contains(q) == true)
            }
            .sortedBy { (it.nickname ?: it.ship).lowercase() }
    }

    Column(modifier = modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                "Contacts",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        HorizontalDivider()

        // Add row.
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = newPatp,
                    onValueChange = { newPatp = it },
                    placeholder = { Text("~patp") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    placeholder = { Text("nickname") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    enabled = isValidPatp && !alreadyInBook,
                    onClick = {
                        onAddContact(asPatp, newName.trim().takeIf { it.isNotBlank() })
                        newPatp = ""
                        newName = ""
                    },
                ) { Text("Add") }
            }
            if (isValidPatp && alreadyInBook) {
                Text(
                    "Already in your contacts.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        HorizontalDivider()

        if (bookContacts.isNotEmpty()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search contacts") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            )
            HorizontalDivider()
        }

        when {
            bookContacts.isEmpty() -> Text(
                "No contacts yet. Add someone by ~patp above, or tap " +
                    "\"Add to contacts\" on a profile.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
            members.isEmpty() -> Text(
                "No contacts match \"$query\".",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(items = members, key = { it.ship }) { c ->
                    ContactRow(
                        c = c,
                        onClick = { onOpenContact(c.ship) },
                        onRemove = { onRemoveContact(c.ship) },
                    )
                    HorizontalDivider()
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun ContactRow(c: ContactEntity, onClick: () -> Unit, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(label = c.nickname ?: c.ship, url = c.avatarUrl, colorHex = c.color, size = 36.dp)
        Column(Modifier.weight(1f)) {
            if (!c.nickname.isNullOrBlank()) {
                Text(
                    c.nickname,
                    style = MaterialTheme.typography.bodyMedium
                        .copy(fontWeight = FontWeight.SemiBold),
                )
            }
            Text(
                c.ship,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onRemove) { Text("Remove") }
    }
}
