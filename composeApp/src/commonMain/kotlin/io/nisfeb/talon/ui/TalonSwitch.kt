package io.nisfeb.talon.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp

/**
 * Groundwire's switch, used in place of Material's everywhere: a slim
 * 40 x 22 track with a white knob that slides across, the accent when on
 * and a quiet grey when off. Material's was a tall outlined track with a
 * knob that changed size, and read heavy in a list of settings.
 *
 * Same call shape as Material's (checked, onCheckedChange, modifier,
 * enabled), so a screen swaps it by import alone. With no
 * [onCheckedChange] it only shows a state, as Material's did.
 */
@Composable
fun Switch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val on = MaterialTheme.colorScheme.primary
    val off = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
    val track by animateColorAsState(if (checked) on else off, tween(150), label = "switch track")
    val knobX by animateDpAsState(if (checked) 20.dp else 2.dp, tween(150), label = "switch knob")
    val interactive = onCheckedChange?.let { change ->
        Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = change)
    } ?: Modifier.semantics {
        role = Role.Switch
        toggleableState = ToggleableState(checked)
    }
    // A 48dp touch target around the slim track, as Material's had.
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .then(interactive)
            .padding(horizontal = 4.dp, vertical = 13.dp)
            .alpha(if (enabled) 1f else 0.38f),
    ) {
        Box(
            modifier = Modifier
                .size(width = 40.dp, height = 22.dp)
                .clip(CircleShape)
                .background(track),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = knobX)
                    .size(18.dp)
                    .shadow(1.dp, CircleShape)
                    .clip(CircleShape)
                    .background(Color.White),
            )
        }
    }
}
