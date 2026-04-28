package io.nisfeb.talon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.update.UpdateStatus

@Composable
fun UpdateBanner(
    status: UpdateStatus,
    onTap: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (status) {
        is UpdateStatus.Idle -> Unit
        is UpdateStatus.Available -> BannerSurface(
            primary = "Talon ${status.manifest.versionName} available",
            secondary = status.manifest.changelog.takeIf { it.isNotBlank() }
                ?: "Tap to update.",
            onTap = onTap,
            onDismiss = if (status.manifest.mandatory) null else onDismiss,
        )
        is UpdateStatus.Downloading -> BannerSurface(
            primary = "Downloading ${status.manifest.versionName}…",
            secondary = "${status.progress}%",
            onTap = null,
            onDismiss = null,
            progress = status.progress,
        )
        is UpdateStatus.Ready -> BannerSurface(
            primary = "Tap to install ${status.manifest.versionName}",
            secondary = "Verified — Android will ask you to confirm.",
            onTap = onTap,
            onDismiss = null,
        )
        is UpdateStatus.Failed -> BannerSurface(
            primary = "Update failed",
            secondary = status.message + " · tap to retry",
            onTap = onTap,
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun BannerSurface(
    primary: String,
    secondary: String,
    onTap: (() -> Unit)?,
    onDismiss: (() -> Unit)?,
    progress: Int? = null,
) {
    val rowMod = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 6.dp)
        .clip(RoundedCornerShape(10.dp))
        .background(MaterialTheme.colorScheme.primaryContainer)
        .let { if (onTap != null) it.clickable(onClick = onTap) else it }
        .padding(horizontal = 12.dp, vertical = 10.dp)
    Row(
        modifier = rowMod,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                primary,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                secondary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
        }
        if (onDismiss != null) {
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Dismiss",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}
