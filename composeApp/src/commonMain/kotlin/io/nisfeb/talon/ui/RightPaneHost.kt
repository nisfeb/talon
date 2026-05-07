package io.nisfeb.talon.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import io.nisfeb.talon.ui.screens.GroupInfoPane
import io.nisfeb.talon.ui.screens.MediaListPane
import io.nisfeb.talon.ui.screens.ThreadList

/**
 * Right-column content dispatcher. Used as [DesktopShell.rightSidebar]
 * on wide windows. On compact, the same surfaces are wrapped by their
 * full-screen `*Screen` siblings (`GroupInfoScreen`, `MediaListScreen`,
 * `ThreadScreen`) and the host is unused.
 *
 * Each branch renders a header (title + close button) and the matching
 * pane body. The header is the host's responsibility, not the pane's,
 * so the same pane composables can be reused full-screen with their
 * own back-arrow header on compact.
 */
@Composable
fun RightPaneHost(
    content: RightPaneContent,
    db: AppDatabase,
    repo: TlonChatRepo,
    http: okhttp3.OkHttpClient,
    drafts: DraftStore,
    ourPatp: String,
    onClose: () -> Unit,
    onOpenCategory: (MediaCategory) -> Unit,
    onLeaveCategoryDrilldown: () -> Unit,
    onOpenConversation: (whom: String) -> Unit,
    onOpenImage: (url: String) -> Unit,
    onOpenImageList: (urls: List<String>, initialIndex: Int) -> Unit,
    onOpenMembers: (whom: String) -> Unit,
    powerFeaturesEnabled: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val title = when (content) {
                is RightPaneContent.Thread -> "Thread"
                is RightPaneContent.GroupInfo -> "Info"
                is RightPaneContent.GroupInfoDrilldown -> categoryLabel(content.category)
            }
            Text(
                title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            IconButton(
                onClick = if (content is RightPaneContent.GroupInfoDrilldown) onLeaveCategoryDrilldown
                          else onClose,
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Close")
            }
        }
        HorizontalDivider()
        Box(modifier = Modifier.fillMaxSize()) {
            when (content) {
                is RightPaneContent.Thread -> ThreadList(
                    db = db,
                    repo = repo,
                    http = http,
                    drafts = drafts,
                    ourPatp = ourPatp,
                    whom = content.whom,
                    parentId = content.parentId,
                    initialScrollReplyId = content.replyAnchor,
                    onOpenConversation = onOpenConversation,
                    onOpenImage = onOpenImage,
                    powerFeaturesEnabled = powerFeaturesEnabled,
                )
                is RightPaneContent.GroupInfo -> GroupInfoPane(
                    db = db,
                    repo = repo,
                    whom = content.whom,
                    onOpenCategory = onOpenCategory,
                    onOpenMembers = { onOpenMembers(content.whom) },
                )
                is RightPaneContent.GroupInfoDrilldown -> MediaListPane(
                    db = db,
                    repo = repo,
                    http = http,
                    whom = content.whom,
                    category = content.category,
                    onOpenImageList = onOpenImageList,
                )
            }
        }
    }
}

private fun categoryLabel(c: MediaCategory): String = when (c) {
    MediaCategory.Photo -> "Photos"
    MediaCategory.Video -> "Videos"
    MediaCategory.Gif -> "GIFs"
    MediaCategory.Voice -> "Voice messages"
    MediaCategory.Audio -> "Audio"
    MediaCategory.File -> "Files"
    MediaCategory.Link -> "Links"
}
