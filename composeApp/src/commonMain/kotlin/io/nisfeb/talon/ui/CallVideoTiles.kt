package io.nisfeb.talon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.call.PartyMember
import io.nisfeb.talon.call.PeerLink
import io.nisfeb.talon.call.VideoState
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The conference tile grid, shared by the mobile full-screen view and
 * the desktop expanded bar so both show the same video. One tile per
 * person: their camera when on, avatar otherwise. Tapping a remote tile
 * pins it to full resolution (see PartyLine.setFocusedVideo).
 */
@Composable
internal fun PartyVideoGrid(
    members: List<PartyMember>,
    selfShip: String,
    nameFor: (String) -> String,
    localVideoLink: PeerLink?,
    videoLinkFor: (String) -> PeerLink?,
    focusedShip: String?,
    onFocusVideo: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(160.dp),
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(members, key = { it.ship }) { m ->
            val isSelf = m.ship == selfShip
            VideoTile(
                member = m,
                isSelf = isSelf,
                nameFor = nameFor,
                link = if (isSelf) localVideoLink else videoLinkFor(m.ship),
                focused = m.ship == focusedShip,
                onTap = if (isSelf) null else {
                    { onFocusVideo(if (m.ship == focusedShip) null else m.ship) }
                },
            )
        }
    }
}

/** One conference tile: camera when on, avatar otherwise, with a name,
 *  mic-off marker, and a ring while speaking or pinned. */
@Composable
internal fun VideoTile(
    member: PartyMember,
    isSelf: Boolean,
    nameFor: (String) -> String,
    link: PeerLink?,
    focused: Boolean = false,
    onTap: (() -> Unit)? = null,
) {
    val videoFlow = remember(link) { link?.video ?: MutableStateFlow(VideoState()) }
    val video by videoFlow.collectAsState()
    val on = if (isSelf) video.localOn else video.remoteOn
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(4f / 3f)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (link != null && on) {
            VideoSurface(link, local = isSelf, Modifier.fillMaxSize())
        } else {
            Avatar(label = nameFor(member.ship), url = null, size = 56.dp)
        }
        Row(
            modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (member.muted || member.mutedByAdmin) {
                Icon(
                    Icons.Filled.MicOff,
                    contentDescription = "Muted",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(3.dp))
            }
            Text(
                nameFor(member.ship) + if (isSelf) " (you)" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val ring = when {
            focused -> MaterialTheme.colorScheme.tertiary
            member.speaking -> MaterialTheme.colorScheme.primary
            else -> null
        }
        if (ring != null) {
            Box(
                Modifier.matchParentSize()
                    .border(if (focused) 3.dp else 2.dp, ring, RoundedCornerShape(10.dp)),
            )
        }
    }
}
