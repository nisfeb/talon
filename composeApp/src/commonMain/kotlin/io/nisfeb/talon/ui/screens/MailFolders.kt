package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.mail.MailFolder
import io.nisfeb.talon.mail.MailView

/**
 * The mailbox column.
 *
 * Mail is a mail client, and a mail client has this. Inbox, Sent,
 * Archived and All are the ship's own views; Drafts is the local store;
 * a label is a folder as soon as you can click it. They sit in one list
 * because a person choosing where to look does not care which of those
 * three things they are picking.
 */
@Composable
fun MailFolderColumn(
    selected: MailFolder,
    labels: List<String>,
    onSelect: (MailFolder) -> Unit,
    onOrganise: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
            .verticalScroll(rememberScrollState())
            .padding(vertical = 6.dp),
    ) {
        FOLDERS.forEach { (f, icon) ->
            FolderRow(folderName(f), icon, selected == f) { onSelect(f) }
        }
        if (labels.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
            Text(
                "Labels",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 14.dp, bottom = 2.dp),
            )
            labels.forEach { l ->
                val f = MailFolder.Label(l)
                FolderRow(l, Icons.Filled.Label, selected == f) { onSelect(f) }
            }
        }
        HorizontalDivider(Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
        FolderRow("Filters and lists", Icons.Filled.Tune, false, onOrganise)
    }
}

/** The same list, where there is no room for a column of its own. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MailFolderSheet(
    selected: MailFolder,
    labels: List<String>,
    onSelect: (MailFolder) -> Unit,
    onOrganise: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).padding(bottom = 24.dp)) {
            MailFolderColumn(
                selected = selected,
                labels = labels,
                onSelect = onSelect,
                onOrganise = onOrganise,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private val FOLDERS: List<Pair<MailFolder, ImageVector>> = listOf(
    MailFolder.View(MailView.INBOX) to Icons.Filled.Inbox,
    MailFolder.Drafts to Icons.Filled.Edit,
    MailFolder.View(MailView.SENT) to Icons.AutoMirrored.Filled.Send,
    MailFolder.View(MailView.ARCHIVED) to Icons.Filled.Archive,
    MailFolder.View(MailView.ALL) to Icons.Filled.MailOutline,
)

@Composable
private fun FolderRow(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val ground =
        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent
    val ink =
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(ground)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            ),
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
