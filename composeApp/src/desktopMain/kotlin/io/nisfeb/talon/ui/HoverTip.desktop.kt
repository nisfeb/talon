package io.nisfeb.talon.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
actual fun HoverTip(tip: String, content: @Composable () -> Unit) = TooltipArea(
    tooltip = {
        Surface(color = MaterialTheme.colorScheme.inverseSurface, shape = MaterialTheme.shapes.small) {
            Text(tip, color = MaterialTheme.colorScheme.inverseOnSurface, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
        }
    },
    delayMillis = 600,
    content = content,
)
