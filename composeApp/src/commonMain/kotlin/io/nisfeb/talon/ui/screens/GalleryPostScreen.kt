package io.nisfeb.talon.ui.screens
import io.nisfeb.talon.util.formatMonthDayYear

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.ui.Avatar
import io.nisfeb.talon.ui.ContactMap
import io.nisfeb.talon.ui.StoryRenderer
import io.nisfeb.talon.ui.contactMapFlow
import io.nisfeb.talon.urbit.StoryCache
import io.nisfeb.talon.urbit.TlonChatRepo
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Detail view for one %heap (gallery) post. Shows the item at full
 * width, author row, and a threaded comment list.
 */
@Composable
fun GalleryPostScreen(
    db: AppDatabase,
    repo: TlonChatRepo,
    ourPatp: String,
    whom: String,
    postId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    // Keep this channel "focused" while the post overlay is open. Opening a
    // post unmounts GalleryGridScreen (App.kt routes grid/post/compose as
    // mutually-exclusive branches), so its onDispose clears openWhom. Without
    // this, openWhom goes null while you view a post: the %activity
    // focus-override stops suppressing unread re-bumps for this channel, and
    // navigating straight to another channel never fires this channel's
    // markRead — so the badge sticks until you back all the way out.
    DisposableEffect(whom) {
        repo.setOpenChat(whom)
        onDispose { repo.setOpenChat(null) }
    }

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
    var actionError by remember { mutableStateOf<String?>(null) }
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
                "Post",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(start = 4.dp).weight(1f),
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
            val parts = remember(p.id, p.contentJson) {
                StoryCache.partsFor(p.id, p.contentJson)
            }
            StoryRenderer(parts = parts, modifier = Modifier.fillMaxWidth())

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Avatar(
                    label = contactMap.nickname(p.author) ?: p.author,
                    url = contactMap.avatar(p.author),
                    colorHex = contactMap.shipColor(p.author),
                    size = 28.dp,
                )
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

            HorizontalDivider()
            Text(
                if (replies.isEmpty()) "No comments yet" else "Comments · ${replies.size}",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            )
            for (r in replies) {
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
                                label = contactMap.nickname(r.author) ?: r.author,
                                url = contactMap.avatar(r.author),
                                colorHex = contactMap.shipColor(r.author),
                                size = 24.dp,
                            )
                            Text(
                                contactMap.nickname(r.author) ?: r.author,
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Medium,
                                ),
                            )
                            Text(
                                formatDate(r.sentMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        val rParts = remember(r.id, r.contentJson) {
                            StoryCache.partsFor(r.id, r.contentJson)
                        }
                        StoryRenderer(parts = rParts)
                    }
                }
            }
        }

        HorizontalDivider()
        actionError?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Send accent. Theme primary already carries the user's
            // chosen accent (per App.kt's TalonTheme override).
            val sendAccent = MaterialTheme.colorScheme.primary
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
                    actionError = null
                    sending = true
                    scope.launch {
                        // A failed send restores the text — swallowing a
                        // typed comment is the one unforgivable outcome.
                        runCatching { repo.reply(whom, postId, text) }
                            .onFailure {
                                replyText = text
                                actionError = "Couldn't send — check your connection."
                            }
                        sending = false
                    }
                },
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = if (replyText.trim().isNotEmpty() && !sending) sendAccent
                    else LocalContentColor.current,
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete post?") },
            text = { Text("This is permanent.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        runCatching { repo.delete(whom, postId) }
                            .onSuccess { onBack() }
                            .onFailure { actionError = "Couldn't delete — check your connection." }
                    }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

private fun formatDate(ms: Long): String = formatMonthDayYear(ms)
