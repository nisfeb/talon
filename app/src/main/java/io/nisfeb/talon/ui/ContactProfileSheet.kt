package io.nisfeb.talon.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
) {
    val sheetState = rememberModalBottomSheetState()
    val label = remember(contact, ship) { contact?.nickname ?: ship }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
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
            Text(
                ship,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!contact?.status.isNullOrBlank()) {
                Text(
                    contact!!.status!!,
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
