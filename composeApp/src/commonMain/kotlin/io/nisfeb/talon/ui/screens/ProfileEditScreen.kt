package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.ui.Avatar
import io.nisfeb.talon.urbit.TlonChatRepo
import io.nisfeb.talon.util.decodeImageDimensions
import io.nisfeb.talon.util.rememberImagePicker
import kotlinx.coroutines.launch

@Composable
fun ProfileEditScreen(
    db: AppDatabase,
    repo: TlonChatRepo,
    ourPatp: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    var nickname by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var bio by remember { mutableStateOf("") }
    var avatarUrl by remember { mutableStateOf<String?>(null) }
    var color by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var uploading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(ourPatp) {
        val c = db.contacts().get(ourPatp) ?: return@LaunchedEffect
        nickname = c.nickname.orEmpty()
        status = c.status.orEmpty()
        bio = c.bio.orEmpty()
        avatarUrl = c.avatarUrl
        color = c.color
    }

    val pickImage = rememberImagePicker()
    val onPickAvatar: () -> Unit = {
        scope.launch {
            val picked = pickImage() ?: return@launch
            uploading = true
            error = null
            runCatching {
                // Bounds-only decode validates the bytes are a real
                // image without allocating the full bitmap. Null means
                // the picker handed us something we can't decode —
                // bail before uploading.
                if (decodeImageDimensions(picked.bytes) == null) {
                    error("not a valid image")
                }
                avatarUrl = repo.uploadImage(picked.bytes, picked.mimeType, picked.displayName)
            }.onFailure { e ->
                error = "avatar upload failed: ${e.message ?: e::class.simpleName}"
            }
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
                "Edit profile",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        HorizontalDivider()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Avatar(
                    label = nickname.ifBlank { ourPatp },
                    url = avatarUrl,
                    colorHex = color,
                    size = 112.dp,
                    onClick = onPickAvatar,
                )
                if (uploading) {
                    CircularProgressIndicator(strokeWidth = 3.dp)
                }
            }
            TextButton(
                onClick = onPickAvatar,
                enabled = !uploading,
            ) { Text("Change photo") }

            Text(
                ourPatp,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = nickname,
                onValueChange = { nickname = it },
                label = { Text("Nickname") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = status,
                onValueChange = { status = it },
                label = { Text("Status") },
                placeholder = { Text("What are you up to?") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = bio,
                onValueChange = { bio = it },
                label = { Text("Bio") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )

            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Color",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                ColorSwatchRow(
                    selected = color,
                    onPick = { color = it },
                )
            }

            error?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = {
                    saving = true
                    error = null
                    scope.launch {
                        runCatching {
                            repo.updateProfile(
                                nickname = nickname,
                                bio = bio,
                                avatarUrl = avatarUrl,
                                status = status,
                                color = color,
                            )
                        }.onFailure { e ->
                            error = "save failed: ${e.message ?: e::class.simpleName}"
                        }.onSuccess { onBack() }
                        saving = false
                    }
                },
                enabled = !saving && !uploading,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (saving) "Saving…" else "Save") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorSwatchRow(
    selected: String?,
    onPick: (String?) -> Unit,
) {
    val palette = remember {
        listOf(
            "#F44336", "#E91E63", "#9C27B0", "#673AB7", "#3F51B5",
            "#2196F3", "#00BCD4", "#009688", "#4CAF50", "#8BC34A",
            "#CDDC39", "#FFC107", "#FF9800", "#FF5722", "#795548",
            "#607D8B", "#222222", "#666666",
        )
    }
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .border(
                    width = if (selected.isNullOrBlank()) 2.dp else 1.dp,
                    color = if (selected.isNullOrBlank()) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline,
                    shape = CircleShape,
                )
                .clickable { onPick(null) },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "—",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        for (hex in palette) {
            val isSel = selected?.equals(hex, ignoreCase = true) == true
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(parseSwatch(hex))
                    .border(
                        width = if (isSel) 3.dp else 0.dp,
                        color = if (isSel) MaterialTheme.colorScheme.primary else Color.Transparent,
                        shape = CircleShape,
                    )
                    .clickable { onPick(hex) },
            )
        }
    }
}

private fun parseSwatch(hex: String): Color {
    val h = hex.removePrefix("#")
    val r = h.substring(0, 2).toInt(16)
    val g = h.substring(2, 4).toInt(16)
    val b = h.substring(4, 6).toInt(16)
    return Color(r, g, b)
}
