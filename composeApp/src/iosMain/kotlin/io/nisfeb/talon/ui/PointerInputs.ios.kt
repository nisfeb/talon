package io.nisfeb.talon.ui

import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier

/** Touch platform — no secondary (right) click. Long-press affordances
 *  are wired separately via combinedClickable in common. */
actual fun Modifier.onSecondaryClick(onClick: () -> Unit): Modifier = this


/** iOS reserves its own edges and does not offer this to ask. */
actual fun Modifier.keepEdgeGesture(): Modifier = this

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
