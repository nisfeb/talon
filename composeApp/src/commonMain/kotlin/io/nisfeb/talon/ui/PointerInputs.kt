package io.nisfeb.talon.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.Modifier

/**
 * Maps a right-click on desktop to [onClick]. No-op on Android — touch
 * platforms have no secondary-click affordance and existing
 * `onLongClick` paths cover hold-to-reveal-menu.
 *
 * Implemented as expect/actual rather than a runtime branch so the
 * desktop pointer-input doesn't consume the pointer slot on Android,
 * where it would silently break drag/scroll on the same node.
 */
expect fun Modifier.onSecondaryClick(onClick: () -> Unit): Modifier

/**
 * Drop-in replacement for [combinedClickable] that ALSO fires
 * [onLongClick] on a desktop right-click. Use this anywhere a
 * long-press currently opens a context menu so mouse users get the
 * same affordance without holding the mouse button.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.combinedClickableWithSecondary(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
): Modifier {
    val base = this.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    return if (onLongClick != null) base.onSecondaryClick(onLongClick) else base
}
