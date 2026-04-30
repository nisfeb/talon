package io.nisfeb.talon.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color

/**
 * Returns the active ship's accent color (the hex stored on the
 * user's own %contacts entry), or null when no contact entry exists
 * yet, the color is malformed, or [ship] is null.
 *
 * Used as a per-ship visual hint in the top bar and composer send
 * button so a user logged into multiple ships at once can tell which
 * one they're about to post as. Brand amber is the fallback at the
 * call site — `accent ?: MaterialTheme.colorScheme.primary`.
 */
@Composable
fun rememberShipAccent(ship: String?, contactMap: ContactMap): Color? =
    remember(ship, contactMap) {
        ship?.let(contactMap::shipColor)?.let(::parseHexColor)
    }
