// Adapted: TalonApplication / LocalContext-injected repo replaced with
// TlonChatRepo Composable parameter. Image picker swapped from
// rememberLauncherForActivityResult(PickVisualMedia) + ContentResolver +
// BitmapFactory.decodeByteArray to rememberImagePicker() +
// decodeImageDimensions (same idiom as ProfileEditScreen in commonMain).
// Keep in sync with production until app/ is removed in Stage F.
package io.nisfeb.talon.ui.screens

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.urbit.TlonChatRepo
import io.nisfeb.talon.util.decodeImageDimensions
import io.nisfeb.talon.util.rememberImagePicker
import kotlinx.coroutines.launch

/**
 * Compose a new notebook (%diary) post. Plain markdown body: paragraphs,
 * `# heading` / `## subhead` / `### sub-sub`, fenced code (```), and
 * `> blockquote`. Anything else passes through as plain text.
 */
@Composable
fun NotebookComposeScreen(
    repo: TlonChatRepo,
    whom: String,
    onBack: () -> Unit,
    onPosted: () -> Unit,
    modifier: Modifier = Modifier,
    /** When non-null, edit that post instead of creating a new one. */
    editPostId: String? = null,
    initialTitle: String = "",
    initialImage: String = "",
    initialBody: String = "",
    originalSentMs: Long = 0L,
) {
    val scope = rememberCoroutineScope()
    val isEdit = editPostId != null

    var title by remember { mutableStateOf(initialTitle) }
    var imageUrl by remember { mutableStateOf(initialImage) }
    var body by remember { mutableStateOf(initialBody) }
    var sending by remember { mutableStateOf(false) }
    var uploading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val pickImage = rememberImagePicker()
    val onPickCover: () -> Unit = {
        scope.launch {
            val picked = pickImage() ?: return@launch
            uploading = true
            error = null
            runCatching {
                // Bounds-only decode validates the bytes are a real image
                // without allocating the full bitmap. Null means the
                // picker handed us something we can't decode — bail
                // before uploading. Mirrors ProfileEditScreen.
                if (decodeImageDimensions(picked.bytes) == null) {
                    error("not a valid image")
                }
                repo.uploadImage(picked.bytes, picked.mimeType, picked.displayName)
            }.onSuccess { imageUrl = it }
                .onFailure { error = "upload failed: ${it.message ?: it::class.simpleName}" }
            uploading = false
        }
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
                if (isEdit) "Edit notebook post" else "New notebook post",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(start = 4.dp).weight(1f),
            )
            TextButton(
                enabled = title.trim().isNotEmpty() && !sending && !uploading,
                onClick = {
                    sending = true
                    error = null
                    scope.launch {
                        runCatching {
                            if (isEdit) {
                                repo.editNotebookPost(
                                    nest = whom,
                                    postId = editPostId!!,
                                    title = title.trim(),
                                    image = imageUrl.trim(),
                                    bodyMarkdown = body,
                                    originalSentMs = originalSentMs,
                                )
                            } else {
                                repo.sendNotebookPost(
                                    nest = whom,
                                    title = title.trim(),
                                    image = imageUrl.trim(),
                                    bodyMarkdown = body,
                                )
                            }
                        }.onSuccess {
                            sending = false
                            onPosted()
                        }.onFailure {
                            error = it.message ?: it::class.simpleName
                            sending = false
                        }
                    }
                },
            ) {
                Text(
                    when {
                        sending && isEdit -> "Saving…"
                        sending -> "Posting…"
                        isEdit -> "Save"
                        else -> "Post"
                    },
                )
            }
        }
        HorizontalDivider()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = imageUrl,
                    onValueChange = { imageUrl = it },
                    label = { Text("Cover image URL (optional)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    enabled = !uploading,
                    onClick = onPickCover,
                ) { Text("Upload") }
            }
            if (uploading) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 4.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(
                        "Uploading…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                label = { Text("Body (markdown)") },
                placeholder = {
                    Text(
                        "Write your post.\n\n" +
                            "Use # / ## / ### for headings,\n" +
                            "``` fences for code, > for quotes.",
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(400.dp),
            )
            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
