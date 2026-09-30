package io.nisfeb.talon.ui

import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.ui.Modifier

actual fun Modifier.onSecondaryClick(onClick: () -> Unit): Modifier = this


/**
 * The real thing on Android: gesture navigation reserves a strip at
 * each side for the back swipe, and a grip inside it is a grip that
 * leaves the app instead of being dragged.
 */
actual fun Modifier.keepEdgeGesture(): Modifier =
    systemGestureExclusion()

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
internal actual fun LinkMenuItems(url: () -> String?, copy: (String) -> Unit, modifier: Modifier, content: @Composable () -> Unit) =
    Box(
        modifier.appendTextContextMenuComponents {
            val u = url() ?: return@appendTextContextMenuComponents
            separator()
            item(key = CopyLinkKey, label = "Copy link", onClick = { copy(u); close() })
        },
        propagateMinConstraints = true,
    ) { content() }

actual val ResizeLeftRightIcon: PointerIcon = PointerIcon(android.view.PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW)
actual val ResizeUpDownIcon: PointerIcon = PointerIcon(android.view.PointerIcon.TYPE_VERTICAL_DOUBLE_ARROW)
actual val ResizeCornerIcon: PointerIcon = PointerIcon(android.view.PointerIcon.TYPE_TOP_LEFT_DIAGONAL_DOUBLE_ARROW)
