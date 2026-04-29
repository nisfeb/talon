// TEMPORARY DUPLICATE of app/src/main/java/io/nisfeb/talon/ui/screens/NotebookPostScreen.kt
// Adapted: TalonApplication / LocalContext-injected db+repo replaced with
// AppDatabase + TlonChatRepo Composable parameters. The
// `app.activeShipFlow.collectAsState()` lookup that gates the Edit/Delete
// menu is replaced with an `ourPatp: String` parameter (same pattern as
// DmChatScreen).
// Keep in sync with production until app/ is removed in Stage F.
package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.ui.Avatar
import io.nisfeb.talon.ui.ContactMap
import io.nisfeb.talon.ui.StoryRenderer
import io.nisfeb.talon.ui.contactMapFlow
import io.nisfeb.talon.urbit.StoryCache
import io.nisfeb.talon.urbit.TlonChatRepo
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Single notebook-post detail view: title, cover, author, rendered
 * body, plus a thread-style reply list below.
 */
@Composable
fun NotebookPostScreen(
    db: AppDatabase,
    repo: TlonChatRepo,
    ourPatp: String,
    whom: String,
    postId: String,
    onBack: () -> Unit,
    onEdit: (title: String, image: String, bodyText: String, sentMs: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    // Suppress Room's invalidation-tracker re-emissions on unrelated
    // messages-table writes — without this the post body and replies
    // re-collect every time any other channel's SSE event lands.
    val post by remember(whom, postId) {
        db.messages().streamOne(whom, postId).distinctUntilChanged()
    }.collectAsState(initial = null)

    val replies by remember(whom, postId) {
        db.messages().streamReplies(whom, postId).distinctUntilChanged()
    }.collectAsState(initial = emptyList())

    val contactMap by remember {
        contactMapFlow(
            db.contacts().stream(),
            db.clubs().stream(),
            db.groups().streamGroups(),
            db.groups().streamChannelGroups(),
        )
    }.collectAsState(initial = ContactMap.EMPTY)

    var replyText by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val isOurs = post?.author == ourPatp

    Column(modifier = modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                post?.title?.takeIf { it.isNotBlank() } ?: "Post",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(start = 4.dp).weight(1f),
                maxLines = 1,
            )
            if (isOurs) {
                androidx.compose.foundation.layout.Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Edit") },
                            onClick = {
                                menuOpen = false
                                val p = post ?: return@DropdownMenuItem
                                val bodyText = io.nisfeb.talon.urbit.StoryCache
                                    .textFor(p.id, p.contentJson)
                                onEdit(
                                    p.title.orEmpty(),
                                    p.image.orEmpty(),
                                    bodyText,
                                    p.sentMs,
                                )
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            onClick = {
                                menuOpen = false
                                confirmDelete = true
                            },
                        )
                    }
                }
            }
        }
        HorizontalDivider()

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val p = post ?: return@Column

            p.image?.takeIf { it.isNotBlank() }?.let { url ->
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(12.dp)),
                )
            }

            Text(
                p.title?.takeIf { it.isNotBlank() } ?: "(untitled)",
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold,
                ),
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Avatar(
                    label = contactMap.nickname(p.author) ?: p.author,
                    url = contactMap.avatar(p.author),
                    colorHex = contactMap.shipColor(p.author),
                    size = 32.dp,
                )
                Column {
                    Text(
                        contactMap.nickname(p.author) ?: p.author,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Medium,
                        ),
                    )
                    Text(
                        formatDate(p.sentMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            HorizontalDivider()

            // Body.
            val parts = remember(p.id, p.contentJson) {
                StoryCache.partsFor(p.id, p.contentJson)
            }
            StoryRenderer(parts = parts, modifier = Modifier.fillMaxWidth())

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Text(
                if (replies.isEmpty()) "No comments yet" else "Comments · ${replies.size}",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            )
            for (r in replies) {
                CommentRow(r, contactMap)
            }
        }

        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = replyText,
                onValueChange = { replyText = it },
                placeholder = { Text("Write a comment") },
                modifier = Modifier.weight(1f),
                enabled = !sending,
            )
            IconButton(
                enabled = replyText.trim().isNotEmpty() && !sending,
                onClick = {
                    val text = replyText.trim()
                    replyText = ""
                    sending = true
                    scope.launch {
                        runCatching { repo.reply(whom, postId, text) }
                        sending = false
                    }
                },
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete post?") },
            text = { Text("This is permanent and visible to every reader.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        runCatching { repo.delete(whom, postId) }
                            .onSuccess { onBack() }
                    }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun CommentRow(
    reply: io.nisfeb.talon.data.MessageEntity,
    contactMap: ContactMap,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 1.dp,
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Avatar(
                    label = contactMap.nickname(reply.author) ?: reply.author,
                    url = contactMap.avatar(reply.author),
                    colorHex = contactMap.shipColor(reply.author),
                    size = 24.dp,
                )
                Text(
                    contactMap.nickname(reply.author) ?: reply.author,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Medium,
                    ),
                )
                Text(
                    formatDate(reply.sentMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            val parts = remember(reply.id, reply.contentJson) {
                StoryCache.partsFor(reply.id, reply.contentJson)
            }
            StoryRenderer(parts = parts)
        }
    }
}

private val DATE_FMT = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
private fun formatDate(ms: Long): String = DATE_FMT.format(Date(ms))
