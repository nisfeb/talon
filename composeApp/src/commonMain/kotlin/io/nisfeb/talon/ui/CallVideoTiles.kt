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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.call.PartyMember
import io.nisfeb.talon.call.PeerLink

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
    videoOnShips: Set<String>,
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
                videoOn = m.ship in videoOnShips,
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
    videoOn: Boolean = false,
    focused: Boolean = false,
    onTap: (() -> Unit)? = null,
) {
    // Camera on/off is signalled explicitly (videoOn), not inferred from
    // the track: a down link always carries an empty video transceiver,
    // so track presence would light every tile up as "on".
    val on = videoOn
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
