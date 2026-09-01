package io.nisfeb.talon.ui.screens
import io.nisfeb.talon.util.formatMonthDayYear

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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
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
import io.nisfeb.talon.urbit.RawMarkdown
import io.nisfeb.talon.urbit.Story
import io.nisfeb.talon.urbit.StoryCache
import io.nisfeb.talon.urbit.TlonChatRepo
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray

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

    // Keep this channel "focused" while the post is open. The notebook list
    // unmounts underneath (mutually-exclusive App.kt branches), so without
    // this openWhom goes null and the channel's unread can re-bump / not
    // clear on navigate-away. See GalleryPostScreen for the full rationale.
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
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    /** Send / delete failures — repo throws when offline, and losing
     *  the tap silently is indistinguishable from success. */
    var actionError by remember { mutableStateOf<String?>(null) }
    /** False only while we're still waiting for the row to show up. */
    var settled by remember(postId) { mutableStateOf(false) }

    LaunchedEffect(postId, post) {
        if (post != null) {
            settled = true
        } else {
            // Long enough to cover a sync on a slow ship, short enough
            // that a deleted post doesn't read as a hang. Mirrors NoteScreen.
            kotlinx.coroutines.delay(6_000)
            settled = true
        }
    }

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
                                val bodyText = editSeedMarkdown(p.id, p.contentJson)
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
            // Mirror NoteScreen: bounded spinner, then say the post is
            // gone — a blank body with a live comment box reads as broken.
            val p = post
            if (p == null) {
                androidx.compose.foundation.layout.Box(
                    Modifier.fillMaxWidth().padding(top = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (!settled) {
                        CircularProgressIndicator()
                    } else {
                        Text(
                            "This post isn't here — it may have been deleted.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                return@Column
            }

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
                // No target to reply to while the post row is missing.
                enabled = !sending && post != null,
            )
            IconButton(
                enabled = replyText.trim().isNotEmpty() && !sending && post != null,
                onClick = {
                    val text = replyText.trim()
                    replyText = ""
                    actionError = null
                    sending = true
                    scope.launch {
                        // repo.reply throws when offline / not connected —
                        // put the draft back so a failed send doesn't eat
                        // the comment.
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
            text = { Text("This is permanent and visible to every reader.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    actionError = null
                    scope.launch {
                        runCatching { repo.delete(whom, postId) }
                            .onSuccess { onBack() }
                            .onFailure {
                                actionError = "Couldn't delete — check your connection."
                            }
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

private fun formatDate(ms: Long): String = formatMonthDayYear(ms)

/**
 * Markdown source to seed the edit composer. [RawMarkdown] is the
 * composer's inverse — headings, blockquotes, fences and inline styles
 * survive the round trip, where [StoryCache.textFor] flattened them so
 * a single edit-and-save silently rewrote the published post. Listing
 * and table verses are rendered here because RawMarkdown doesn't emit
 * the composer's `{"item": […]}` / `block.table` shapes; any verse
 * neither can express falls back to plain text so no content is
 * dropped. Known limit: nested sub-lists flatten (the composer only
 * writes flat lists) and cite/link blocks degrade to their text label.
 */
private fun editSeedMarkdown(id: String, contentJson: String): String {
    val story = runCatching {
        Json.parseToJsonElement(contentJson) as? JsonArray
    }.getOrNull() ?: return StoryCache.textFor(id, contentJson)
    val md = story.mapNotNull { verse ->
        val block = (verse as? JsonObject)?.get("block") as? JsonObject
        val table = block?.get("table") as? JsonObject
        val rendered = when {
            // Listings go through RawMarkdown.fromStory's own renderer
            // (the else branch): it accepts both the composer's
            // {"item": [...]} wrapper and the older direct-array item
            // shape — a local re-implementation here handled only one
            // and silently dropped the other's items.
            table != null -> {
                fun cellsOf(arr: JsonElement?): List<String> =
                    ((arr as? JsonArray) ?: JsonArray(emptyList())).map {
                        RawMarkdown.renderInlines((it as? JsonArray) ?: JsonArray(emptyList()))
                    }
                fun line(cells: List<String>) = cells.joinToString(" | ", "| ", " |")
                val header = cellsOf(table["header"])
                val rows = ((table["rows"] as? JsonArray) ?: JsonArray(emptyList()))
                    .map { cellsOf(it) }
                (listOf(line(header), line(List(header.size) { "---" })) + rows.map(::line))
                    .joinToString("\n")
            }
            else -> {
                val single = buildJsonArray { add(verse) }
                RawMarkdown.fromStory(single).ifBlank { Story.plainText(single) }
            }
        }
        rendered.takeIf { it.isNotBlank() }
    }.joinToString("\n\n").trim()
    return md.ifBlank { StoryCache.textFor(id, contentJson) }
}
