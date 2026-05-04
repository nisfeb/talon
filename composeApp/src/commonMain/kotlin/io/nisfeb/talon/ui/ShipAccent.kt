package io.nisfeb.talon.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
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

/**
 * `OutlinedTextField` colors that paint the focus ring and caret in
 * [accent], leaving every other (unfocused / disabled / error) state
 * at the Material3 defaults. Used by send-proximate composer fields
 * (DM, thread reply, notebook body, gallery body) so the focus ring
 * matches the send-icon tint when the user is logged into multiple
 * ships — same peripheral "you're about to post as <ship>" cue that
 * already drives the send-button color.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun accentTextFieldColors(accent: Color): TextFieldColors =
    OutlinedTextFieldDefaults.colors(
        focusedBorderColor = accent,
        focusedLabelColor = accent,
        cursorColor = accent,
    )
