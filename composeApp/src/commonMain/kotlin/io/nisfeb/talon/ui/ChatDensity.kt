package io.nisfeb.talon.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Three density modes for the message-list surfaces. The user picks
 * one in Settings and the chat list / message rows / thread rows
 * retune their padding + inter-row spacing accordingly. "Comfortable"
 * matches the current values — existing users see no change unless
 * they opt into Compact (denser, more rows on screen) or Cozy
 * (looser, easier to scan).
 *
 * Stored per-device (not synced) — matches accentSettings /
 * hideComposerButtons. A future rc could add a %settings bucket if
 * users ask for cross-device parity.
 */
enum class Density { Compact, Comfortable, Cozy }

/**
 * Resolved spacing values for the active density. Lives in a
 * CompositionLocal so any row composable can opt in via
 * `LocalChatDensity.current` without plumbing the enum through every
 * call site. Centralizing the multiplier→Dp mapping here also keeps
 * the three modes visually consistent across surfaces — change the
 * shape once and every chat list / message row updates together.
 */
data class ChatDensity(
    val mode: Density,
    /** Vertical padding above + below a chat-list row (folder /
     *  channel / DM list). The current Comfortable value matches
     *  what DmListScreen has been using. */
    val listRowVertical: Dp,
    /** Inter-row spacing between adjacent messages in a chat. */
    val messageSpacing: Dp,
    /** Padding inside a message bubble (around the text content). */
    val bubblePadding: Dp,
    /** Avatar size in chat-list rows. */
    val listAvatarSize: Dp,
    /** Multiplier applied to the app's `LocalDensity.fontScale` so
     *  text + icons sized in `sp` scale across the whole app. 1.0 =
     *  current size; <1.0 shrinks; >1.0 grows. The Comfortable value
     *  is exactly 1.0 so existing users see no change unless they
     *  pick a different mode. */
    val fontScaleMultiplier: Float,
) {
    companion object {
        val Compact = ChatDensity(
            mode = Density.Compact,
            listRowVertical = 6.dp,
            messageSpacing = 4.dp,
            bubblePadding = 6.dp,
            listAvatarSize = 36.dp,
            fontScaleMultiplier = 0.90f,
        )
        val Comfortable = ChatDensity(
            mode = Density.Comfortable,
            listRowVertical = 12.dp,
            messageSpacing = 8.dp,
            bubblePadding = 10.dp,
            listAvatarSize = 44.dp,
            fontScaleMultiplier = 1.0f,
        )
        val Cozy = ChatDensity(
            mode = Density.Cozy,
            listRowVertical = 16.dp,
            messageSpacing = 12.dp,
            bubblePadding = 14.dp,
            listAvatarSize = 52.dp,
            fontScaleMultiplier = 1.12f,
        )

        fun forMode(mode: Density): ChatDensity = when (mode) {
            Density.Compact -> Compact
            Density.Comfortable -> Comfortable
            Density.Cozy -> Cozy
        }
    }
}

/** Composition-local for the active density. Default is Comfortable so
 *  a screen rendered without an explicit provider (tests, isolated
 *  previews) gets the existing layout. App.kt provides the real value
 *  via `CompositionLocalProvider`. */
val LocalChatDensity = staticCompositionLocalOf { ChatDensity.Comfortable }
