package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
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
import io.nisfeb.talon.ui.DraftStore
import io.nisfeb.talon.ui.LocationProvider
import io.nisfeb.talon.urbit.TlonChatRepo
import okhttp3.OkHttpClient

@Composable
fun ThreadScreen(
    db: AppDatabase,
    repo: TlonChatRepo,
    http: OkHttpClient,
    drafts: DraftStore,
    ourPatp: String,
    whom: String,
    parentId: String,
    onBack: () -> Unit,
    onOpenConversation: (whom: String) -> Unit,
    onOpenImage: (url: String) -> Unit,
    /** When non-null, the screen anchors its initial scroll on this
     *  reply id rather than the default "newest" position. Consumed
     *  once, so re-entering the thread later goes back to default. */
    initialScrollReplyId: String? = null,
    onScrollConsumed: () -> Unit = {},
    /** Android-only platform widget slots forwarded to the composer.
     *  Desktop passes null; the composer surface degrades gracefully
     *  (no mic button, no /loc, no inline voice playback). */
    voiceComposer: (@Composable (
        enabled: Boolean,
        onRecorded: (path: String, durationMs: Long) -> Unit,
    ) -> Unit)? = null,
    voicePlayer: (@Composable (path: String, sending: Boolean) -> Unit)? = null,
    locationProvider: LocationProvider? = null,
    onSlashMic: (() -> Unit)? = null,
    hideComposerButtons: Boolean = false,
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
                "Thread",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        HorizontalDivider()
        ThreadList(
            db = db,
            repo = repo,
            http = http,
            drafts = drafts,
            ourPatp = ourPatp,
            whom = whom,
            parentId = parentId,
            initialScrollReplyId = initialScrollReplyId,
            onScrollConsumed = onScrollConsumed,
            onOpenConversation = onOpenConversation,
            onOpenImage = onOpenImage,
            voiceComposer = voiceComposer,
            voicePlayer = voicePlayer,
            locationProvider = locationProvider,
            onSlashMic = onSlashMic,
            hideComposerButtons = hideComposerButtons,
            modifier = Modifier.weight(1f).fillMaxSize(),
        )
    }
}
