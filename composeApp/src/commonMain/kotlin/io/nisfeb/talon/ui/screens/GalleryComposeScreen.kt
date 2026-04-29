// TEMPORARY DUPLICATE of app/src/main/java/io/nisfeb/talon/ui/screens/GalleryComposeScreen.kt
// Adapted: TalonApplication coupling replaced with constructor
// injection (repo). Image picker switched from
// rememberLauncherForActivityResult(PickVisualMedia) +
// BitmapFactory.decodeByteArray to rememberImagePicker() +
// decodeImageDimensions — the bounds-only decode validates that bytes
// are a real image without allocating a Bitmap (mirrors
// ProfileEditScreen). Keep in sync with production until app/ is
// removed in Stage F.
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private enum class GalleryTab(val label: String) {
    Image("Image"),
    Link("Link"),
    Text("Text");
}

/**
 * Compose a new gallery (%heap) post. Three modes: upload an image,
 * paste a link (server-side enriches the preview), or write a short
 * text snippet. Exactly one of those goes out as the post content.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun GalleryComposeScreen(
    repo: TlonChatRepo,
    whom: String,
    onBack: () -> Unit,
    onPosted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    var tab by remember { mutableStateOf(GalleryTab.Image) }

    // Image state
    var imageSrc by remember { mutableStateOf<String?>(null) }
    var imageWidth by remember { mutableStateOf(0) }
    var imageHeight by remember { mutableStateOf(0) }
    var imageAlt by remember { mutableStateOf("") }
    var uploading by remember { mutableStateOf(false) }

    // Link + text state
    var linkUrl by remember { mutableStateOf("") }
    var textBody by remember { mutableStateOf("") }

    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val pickImage = rememberImagePicker()
    val onPickImage: () -> Unit = {
        scope.launch {
            val picked = pickImage() ?: return@launch
            uploading = true
            error = null
            runCatching {
                // Bounds-only decode validates the bytes are a real
                // image without allocating the full bitmap. Null means
                // we got handed something unparseable — bail before
                // uploading.
                val dims = decodeImageDimensions(picked.bytes)
                    ?: error("not a valid image")
                val url = repo.uploadImage(picked.bytes, picked.mimeType, picked.displayName)
                imageSrc = url
                imageWidth = dims.first
                imageHeight = dims.second
                imageAlt = picked.displayName
            }.onFailure { error = "upload failed: ${it.message ?: it::class.simpleName}" }
            uploading = false
        }
    }

    val canPost = when (tab) {
        GalleryTab.Image -> imageSrc != null && !uploading
        GalleryTab.Link -> linkUrl.trim().isNotEmpty()
        GalleryTab.Text -> textBody.trim().isNotEmpty()
    } && !sending

    Column(modifier = modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                "New gallery post",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(start = 4.dp).weight(1f),
            )
            TextButton(
                enabled = canPost,
                onClick = {
                    sending = true
                    error = null
                    val content = when (tab) {
                        GalleryTab.Image -> buildJsonArray {
                            add(buildJsonObject {
                                put("block", buildJsonObject {
                                    put("image", buildJsonObject {
                                        put("src", imageSrc!!)
                                        put("width", imageWidth)
                                        put("height", imageHeight)
                                        put("alt", imageAlt)
                                    })
                                })
                            })
                        }
                        GalleryTab.Link -> buildJsonArray {
                            // Tlon's LinkInput posts a link block so the
                            // server-side previewer replaces it with an
                            // enriched meta bag. Plain URLs in inline
                            // arrays only render as literal text.
                            add(buildJsonObject {
                                put("block", buildJsonObject {
                                    put("link", buildJsonObject {
                                        put("url", linkUrl.trim())
                                        put("meta", buildJsonObject { })
                                    })
                                })
                            })
                        }
                        GalleryTab.Text -> buildJsonArray {
                            val lines = textBody.trim().split("\n")
                            add(buildJsonObject {
                                put("inline", buildJsonArray {
                                    lines.forEachIndexed { i, l ->
                                        if (i > 0) add(buildJsonObject { put("break", buildJsonObject { }) })
                                        add(JsonPrimitive(l))
                                    }
                                })
                            })
                        }
                    }
                    scope.launch {
                        runCatching { repo.sendGalleryPost(whom, content) }
                            .onSuccess {
                                sending = false
                                onPosted()
                            }.onFailure {
                                error = it.message ?: it::class.simpleName
                                sending = false
                            }
                    }
                },
            ) { Text(if (sending) "Posting…" else "Post") }
        }
        HorizontalDivider()

        PrimaryTabRow(selectedTabIndex = tab.ordinal) {
            GalleryTab.values().forEach { t ->
                Tab(
                    selected = tab == t,
                    onClick = { tab = t },
                    text = { Text(t.label) },
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (tab) {
                GalleryTab.Image -> {
                    TextButton(
                        enabled = !uploading,
                        onClick = onPickImage,
                    ) {
                        Text(if (imageSrc == null) "Pick image" else "Replace image")
                    }
                    imageSrc?.let { src ->
                        Text(
                            src,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                        OutlinedTextField(
                            value = imageAlt,
                            onValueChange = { imageAlt = it },
                            label = { Text("Alt text (optional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
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
                }
                GalleryTab.Link -> {
                    OutlinedTextField(
                        value = linkUrl,
                        onValueChange = { linkUrl = it },
                        label = { Text("URL") },
                        placeholder = { Text("https://...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "The ship fetches a title + preview automatically once posted.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                GalleryTab.Text -> {
                    OutlinedTextField(
                        value = textBody,
                        onValueChange = { textBody = it },
                        label = { Text("Text") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                    )
                }
            }
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
