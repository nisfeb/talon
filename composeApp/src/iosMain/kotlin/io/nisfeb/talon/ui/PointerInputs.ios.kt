package io.nisfeb.talon.ui

import androidx.compose.ui.Modifier

/** Touch platform — no secondary (right) click. Long-press affordances
 *  are wired separately via combinedClickable in common. */
actual fun Modifier.onSecondaryClick(onClick: () -> Unit): Modifier = this
