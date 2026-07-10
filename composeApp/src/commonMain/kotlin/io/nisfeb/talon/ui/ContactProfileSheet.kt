package io.nisfeb.talon.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.data.ContactEntity

/**
 * Bottom sheet showing a peer's profile, or our own. For peers, offers
 * a "Message" action that routes into the 1:1 DM. For self, offers
 * "Edit profile" — the caller decides which screen to push.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactProfileSheet(
    ship: String,
    self: Boolean,
    contact: ContactEntity?,
    onMessage: () -> Unit,
    onEditSelf: () -> Unit,
    onDismiss: () -> Unit,
    /** Add this ship to %contacts. When non-null and the ship isn't
     *  already in the contact book, an "Add to contacts" button shows. */
    onAddContact: (() -> Unit)? = null,
    /** Remove this ship from the contact book. When non-null and the
     *  ship IS in the book, a "Remove" affordance shows next to the
     *  already-a-contact indicator. */
    onRemoveContact: (() -> Unit)? = null,
    /** Whether this ship is in our curated contact book. Drives the
     *  add button vs. the "✓ In your contacts" indicator — based on
     *  book membership, not mere presence in the /v1/all peer cache. */
    isInBook: Boolean = false,
) {
    val sheetState = rememberModalBottomSheetState()
    val label = remember(contact, ship) { contact?.nickname ?: ship }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .windowInsetsPadding(WindowInsets.navigationBars),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Avatar(
                label = label,
                url = contact?.avatarUrl,
                colorHex = contact?.color,
                size = 96.dp,
            )
            if (!contact?.nickname.isNullOrBlank()) {
                Text(
                    contact!!.nickname!!,
                    style = MaterialTheme.typography.headlineSmall
                        .copy(fontWeight = FontWeight.SemiBold),
                )
            }
            // @p and (when the naming setting is on) the full mnemonym,
            // each tap-to-copy — the profile sheet is where you go to
            // grab someone's exact name.
            val clipboard = LocalClipboardManager.current
            var copied by remember { mutableStateOf<String?>(null) }
            fun copyRow(text: String): () -> Unit = {
                clipboard.setText(AnnotatedString(text))
                copied = text
            }
            Text(
                ship,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable(onClick = copyRow(ship)),
            )
            val mnemonymOn by MnemonymNames.enabled.collectAsState()
            val nym = remember(ship) { Mnemonym.forShip(ship) }
            if (mnemonymOn && nym != null) {
                Text(
                    nym,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable(onClick = copyRow(nym)),
                )
            }
            copied?.let {
                Text(
                    "Copied $it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (!contact?.status.isNullOrBlank()) {
                Text(
                    text = linkifyStatus(contact!!.status!!),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Medium,
                    ),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (!contact?.bio.isNullOrBlank()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    contact!!.bio!!,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(4.dp))
            // Contact-book state for a peer (never for self):
            //  - not in book → "Add to contacts" action
            //  - in book → "✓ In your contacts" indicator, with an
            //    optional "Remove" next to it.
            if (!self) {
                when {
                    isInBook -> Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "✓ In your contacts",
                            style = MaterialTheme.typography.bodyMedium
                                .copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        if (onRemoveContact != null) {
                            TextButton(onClick = onRemoveContact) { Text("Remove") }
                        }
                    }
                    onAddContact != null -> OutlinedButton(
                        onClick = onAddContact,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Add to contacts") }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (self) {
                    Button(
                        onClick = onEditSelf,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.Edit, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Edit profile")
                    }
                } else {
                    Button(
                        onClick = onMessage,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Message")
                    }
                    OutlinedButton(onClick = onDismiss) { Text("Close") }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
