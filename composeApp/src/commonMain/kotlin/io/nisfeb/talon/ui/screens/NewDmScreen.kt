package io.nisfeb.talon.ui.screens

import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.material3.HorizontalDivider
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
import io.nisfeb.talon.ui.Avatar

@Composable
fun NewDmScreen(
    db: AppDatabase,
    onPickPeer: (patp: String) -> Unit,
    onBack: () -> Unit,
    /** Add the entered ~patp to %contacts (with an optional nickname).
     *  Default no-op for call sites that haven't wired it. */
    onAddContact: (patp: String, nickname: String?) -> Unit = { _, _ -> },
    /** Ships already in the curated contact book — drives whether the
     *  "Add contact" affordance shows. NOT the broad /v1/all table
     *  (which includes every known peer). */
    bookContacts: Set<String> = emptySet(),
    modifier: Modifier = Modifier,
) {
    val fieldFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fieldFocus.requestFocus() } }
    var query by remember { mutableStateOf("") }
    var newContactName by remember { mutableStateOf("") }
    val contacts by remember { db.contacts().stream() }.collectAsState(initial = emptyList())

    val q = query.trim().lowercase().removePrefix("~")
    val filtered = remember(q, contacts) {
        if (q.isEmpty()) contacts
        else contacts.filter { c ->
            c.ship.lowercase().removePrefix("~").contains(q) ||
                (c.nickname?.lowercase()?.contains(q) == true) ||
                // The name a comet actually goes by on every other
                // screen. Leaving it out meant the list could not find
                // somebody by the only name the reader had seen.
                io.nisfeb.talon.ui.shipHandle(c.ship).lowercase().contains(q) ||
                io.nisfeb.talon.ui.shipHandleLong(c.ship)?.lowercase()?.contains(q) == true
        }
    }

    val trimmedInput = query.trim()
    // A comet answers to its @p, its twelve-word name and the two-word
    // one every screen shows. Only the first used to be accepted, so
    // the name people were actually given was the one this box refused.
    val namesGen by io.nisfeb.talon.ui.AzimuthNames.generation.collectAsState()
    val byShip = remember(contacts) { contacts.associateBy { it.ship } }
    val resolved = remember(trimmedInput, byShip, namesGen) {
        io.nisfeb.talon.ui.NameToShip.resolve(
            typed = trimmedInput,
            known = byShip.keys,
            nicknameOf = { ship -> byShip[ship]?.nickname },
        )
    }
    val asPatp = (resolved as? io.nisfeb.talon.ui.NameToShip.Result.One)?.ship
        ?: if (trimmedInput.startsWith("~")) trimmedInput else "~$trimmedInput"
    val isValidPatp = resolved is io.nisfeb.talon.ui.NameToShip.Result.One
    val resolveHint = io.nisfeb.talon.ui.NameToShip.hint(resolved, trimmedInput)
    val alreadyContact = remember(asPatp, bookContacts) { asPatp in bookContacts }

    Column(modifier = modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            io.nisfeb.talon.ui.NavIcon(onBack = onBack)
            Text(
                "New message",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("~ship, or a word name") },
                // Not single-line: a comet's @p is fifty-six characters
                // and its full name is twelve words, and either one
                // scrolled off the end of a single line with no way to
                // see what you had typed.
                singleLine = false,
                maxLines = 3,
                modifier = Modifier.weight(1f).focusRequester(fieldFocus),
            )
            TextButton(
                onClick = { onPickPeer(asPatp) },
                enabled = isValidPatp,
            ) { Text("Start") }
        }
        resolveHint?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
            )
        }
        // Add-to-contacts row — only when a valid ~patp that isn't
        // already a contact is entered. Optional nickname; tracks the
        // peer via %contacts so they show up in the contact list.
        if (isValidPatp && !alreadyContact) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = newContactName,
                    onValueChange = { newContactName = it },
                    placeholder = { Text("nickname (optional)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        onAddContact(asPatp, newContactName.trim().takeIf { it.isNotBlank() })
                        newContactName = ""
                        // Keep `query`: the natural flow is add-then-Start,
                        // and clearing it would disable the Start button.
                    },
                ) { Text("Add contact") }
            }
        }
        HorizontalDivider()
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            items(items = filtered, key = { it.ship }) { c ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPickPeer(c.ship) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Avatar(
                        label = c.nickname ?: c.ship,
                        url = c.avatarUrl,
                        size = 36.dp,
                    )
                    Column(Modifier.weight(1f)) {
                        if (c.nickname != null) {
                            Text(
                                c.nickname,
                                style = MaterialTheme.typography.bodyMedium
                                    .copy(fontWeight = FontWeight.SemiBold),
                            )
                        }
                        Text(
                            io.nisfeb.talon.ui.shipHandle(c.ship),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider()
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}
