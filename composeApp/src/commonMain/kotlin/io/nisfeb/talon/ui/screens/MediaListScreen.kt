package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.urbit.MediaCategory
import io.nisfeb.talon.urbit.TlonChatRepo

@Composable
fun MediaListScreen(
    db: AppDatabase,
    repo: TlonChatRepo,
    http: okhttp3.OkHttpClient,
    whom: String,
    category: MediaCategory,
    onBack: () -> Unit,
    onOpenImageList: (urls: List<String>, initialIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                title(category),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        HorizontalDivider()
        MediaListPane(
            db = db,
            repo = repo,
            http = http,
            whom = whom,
            category = category,
            onOpenImageList = onOpenImageList,
        )
    }
}

private fun title(c: MediaCategory): String = when (c) {
    MediaCategory.Photo -> "Photos"
    MediaCategory.Video -> "Videos"
    MediaCategory.Gif -> "GIFs"
    MediaCategory.Voice -> "Voice messages"
    MediaCategory.Audio -> "Audio"
    MediaCategory.File -> "Files"
    MediaCategory.Link -> "Links"
}
