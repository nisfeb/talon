package io.nisfeb.talon.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.call.MediaState
import io.nisfeb.talon.call.PartyLine
import io.nisfeb.talon.call.PartyState

/**
 * The "who's on the line" strip, shown under a channel header while a
 * party line is running. Deliberately a strip and not a screen: a
 * party line is something you leave open while you keep reading the
 * channel.
 */
@Composable
fun PartyLineBar(party: PartyLine, modifier: Modifier = Modifier) {
    val state by party.state.collectAsState()
    if (state is PartyState.Idle) return

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (val s = state) {
                is PartyState.Connecting ->
                    Text("Joining the line…", style = MaterialTheme.typography.bodyMedium)

                is PartyState.Failed ->
                    Text(
                        "Party line: ${s.why}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )

                is PartyState.Live -> {
                    val who = s.members.joinToString(", ") { it.ship }
                    val label = when {
                        s.media != MediaState.Live -> "Connecting audio…"
                        s.members.isEmpty() -> "On the line — waiting for others"
                        else -> "On the line: $who"
                    }
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { party.setMuted(!s.muted) }) {
                            Icon(
                                if (s.muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                                contentDescription = if (s.muted) "Unmute" else "Mute",
                            )
                        }
                        FilledIconButton(
                            onClick = { party.leave() },
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ),
                        ) {
                            Icon(Icons.Filled.CallEnd, contentDescription = "Leave the line")
                        }
                    }
                }

                PartyState.Idle -> {}
            }
        }
    }
}
