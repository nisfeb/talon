package io.nisfeb.talon.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * "New" divider rendered above the first unread row in a chat or
 * thread list. Color reads `colorScheme.primary` so the tint
 * follows the user's accent setting (brand amber by default, profile
 * color or custom hex when accent is on).
 */
@Composable
fun UnreadDividerRow() {
    val tint = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = tint)
        Text(
            "New",
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            modifier = Modifier.padding(horizontal = 10.dp),
        )
        HorizontalDivider(modifier = Modifier.weight(1f), color = tint)
    }
}
