package io.nisfeb.talon.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// File-drop and in-app clipboard-image paste are desktop affordances;
// iOS has no Compose equivalent, so the modifiers pass through and the
// clipboard read yields nothing (imagePasteTarget never invokes it).
actual fun Modifier.fileDropTarget(
    enabled: Boolean,
    onFiles: (List<DroppedFile>) -> Unit,
): Modifier = this

actual fun readClipboardImageOrNull(): DroppedFile? = null

@Composable
actual fun Modifier.imagePasteTarget(
    enabled: Boolean,
    onImage: (DroppedFile) -> Unit,
): Modifier = this
