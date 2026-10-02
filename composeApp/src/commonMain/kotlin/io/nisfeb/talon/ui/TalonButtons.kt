package io.nisfeb.talon.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape

// Material's buttons with Talon's corners. Material draws a button as a
// pill and takes no shape for it from the theme, so beside the chips,
// fields and cards, all drawn at 6 to 12, they were the one round thing
// on the screen. Same parameters as Material's; only the shape defaults
// differently. SectionFlagsGuardTest's sibling, MaterialButtonsGuardTest,
// keeps app code on these.

@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = MaterialTheme.shapes.small,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) = androidx.compose.material3.Button(onClick, modifier, enabled, shape, colors, elevation, border, contentPadding, interactionSource, content)

@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = MaterialTheme.shapes.small,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = ButtonDefaults.outlinedButtonBorder(enabled),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) = androidx.compose.material3.OutlinedButton(onClick, modifier, enabled, shape, colors, elevation, border, contentPadding, interactionSource, content)

/**
 * The confirm of something that cannot be taken back (delete, remove,
 * kick, ban, leave, discard), in the error colour: one look for every
 * such button, where some were red and most were not.
 */
@Composable
fun DestructiveTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = TextButton(
    onClick = onClick,
    modifier = modifier,
    enabled = enabled,
    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
    content = content,
)

@Composable
fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = MaterialTheme.shapes.small,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) = androidx.compose.material3.TextButton(onClick, modifier, enabled, shape, colors, elevation, border, contentPadding, interactionSource, content)

/**
 * Asked before something that cannot be taken back: [title] ("Delete
 * this theme?"), [text] saying what goes, [confirm] in the error colour.
 */
@Composable
fun ConfirmDestructive(
    title: String,
    text: String,
    confirm: String = "Delete",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) = androidx.compose.material3.AlertDialog(
    onDismissRequest = onDismiss,
    title = { androidx.compose.material3.Text(title) },
    text = { androidx.compose.material3.Text(text) },
    confirmButton = { DestructiveTextButton(onClick = { onDismiss(); onConfirm() }) { androidx.compose.material3.Text(confirm) } },
    dismissButton = { TextButton(onClick = onDismiss) { androidx.compose.material3.Text("Cancel") } },
)

/**
 * Copies [text] and reads "Copied" for a moment: most copy buttons said
 * nothing, and people pasted somewhere to see whether they had worked.
 */
@Composable
fun CopyButton(text: () -> String, label: String = "Copy", modifier: Modifier = Modifier) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var copied by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(copied) {
        if (copied) { kotlinx.coroutines.delay(COPIED_FOR_MS); copied = false }
    }
    TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(text())); copied = true }, modifier = modifier) {
        androidx.compose.material3.Text(if (copied) "Copied" else label)
    }
}

/** How long a copy says "Copied". */
const val COPIED_FOR_MS = 2_000L

/**
 * An icon button whose [tip], the icon's own description, shows on
 * hover where there is a pointer: an icon alone said nothing on desktop
 * until it was clicked.
 */
@Composable
fun IconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tip: String? = null,
    content: @Composable () -> Unit,
) {
    if (tip == null) {
        androidx.compose.material3.IconButton(onClick = onClick, modifier = modifier, enabled = enabled, content = content)
    } else {
        HoverTip(tip) {
            androidx.compose.material3.IconButton(onClick = onClick, modifier = modifier, enabled = enabled, content = content)
        }
    }
}

/**
 * [content] with [tip] shown while a pointer rests on it; [content] alone
 * where nothing hovers (touch). Hover only: material3's TooltipBox takes
 * the anchor's presses, and a button under it stopped answering touches.
 */
@Composable
expect fun HoverTip(tip: String, content: @Composable () -> Unit)
