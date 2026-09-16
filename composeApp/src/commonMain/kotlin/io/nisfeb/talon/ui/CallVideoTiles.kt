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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.call.PartyMember
import io.nisfeb.talon.call.PeerLink

/**
 * The conference pictures, shared by the mobile full-screen view and
 * the desktop expanded bar so both show the same video.
 *
 * One picture is front and centre: whoever is pinned, else whoever
 * spoke last, else the first camera on. Everyone else's camera is a
 * row of small tiles underneath; tapping one pins it, tapping the
 * pinned one lets go. Ourselves only when nobody else has a camera on.
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
    // Who spoke last, kept until somebody else does: the level flag
    // flickers with every pause, and a picture that swapped on each
    // one would be unwatchable.
    var lastSpeaker by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(members) {
        members.firstOrNull { it.ship != selfShip && it.speaking && it.ship in videoOnShips }
            ?.let { lastSpeaker = it.ship }
    }
    val onCamera = members.filter { it.ship in videoOnShips }
    val featured = featuredVideo(onCamera.map { it.ship }, selfShip, focusedShip, lastSpeaker)
        ?: return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        onCamera.firstOrNull { it.ship == featured }?.let { m ->
            val isSelf = m.ship == selfShip
            VideoTile(
                member = m,
                isSelf = isSelf,
                nameFor = nameFor,
                link = if (isSelf) localVideoLink else videoLinkFor(m.ship),
                videoOn = true,
                focused = m.ship == focusedShip,
                onTap = if (isSelf) null else {
                    { onFocusVideo(if (m.ship == focusedShip) null else m.ship) }
                },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
        val rest = onCamera.filter { it.ship != featured }
        if (rest.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(rest, key = { it.ship }) { m ->
                    val isSelf = m.ship == selfShip
                    VideoTile(
                        member = m,
                        isSelf = isSelf,
                        nameFor = nameFor,
                        link = if (isSelf) localVideoLink else videoLinkFor(m.ship),
                        videoOn = true,
                        focused = false,
                        onTap = if (isSelf) null else {
                            { onFocusVideo(m.ship) }
                        },
                        modifier = Modifier.size(96.dp),
                    )
                }
            }
        }
    }
}

/**
 * Which camera goes front and centre: the pinned one, else the last
 * to speak, else the first that is not our own, else our own. Null
 * when nobody has a camera on.
 */
internal fun featuredVideo(
    onCamera: List<String>,
    selfShip: String,
    focusedShip: String?,
    lastSpeaker: String?,
): String? =
    focusedShip?.takeIf { it in onCamera }
        ?: lastSpeaker?.takeIf { it in onCamera }
        ?: onCamera.firstOrNull { it != selfShip }
        ?: onCamera.firstOrNull()

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
    /** The caller's shape; the renderers fit the picture into it. */
    modifier: Modifier = Modifier.fillMaxWidth().aspectRatio(1f),
) {
    // Camera on/off is signalled explicitly (videoOn), not inferred from
    // the track: a down link always carries an empty video transceiver,
    // so track presence would light every tile up as "on".
    val on = videoOn
    Box(
        modifier
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
