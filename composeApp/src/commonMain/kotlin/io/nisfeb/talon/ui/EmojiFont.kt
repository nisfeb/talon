package io.nisfeb.talon.ui

import androidx.compose.ui.text.font.FontFamily

/**
 * Color-emoji-aware FontFamily applied to Text composables that
 * render *only* emoji glyphs (reaction chips, the picker palette,
 * AI-suggestion buttons).
 *
 * - **Android**: returns [FontFamily.Default]. The system already
 *   renders emojis in color via the platform's emoji font.
 * - **Desktop**: returns a FontFamily backed by a bundled
 *   `NotoColorEmoji.ttf`. Compose Desktop's text renderer doesn't
 *   reliably pick up the system color emoji font on Linux even when
 *   `fc-match emoji` resolves correctly — fontconfig falls back to
 *   monochrome glyphs (e.g., DejaVu Sans's emoji outlines) before it
 *   gets to NotoColorEmoji. Forcing the family on emoji-only Text
 *   composables bypasses the broken fallback chain.
 *
 * Don't apply this to mixed text/emoji surfaces (chat bodies,
 * statuses, message previews) — NotoColorEmoji has no letter glyphs,
 * so non-emoji characters would render as tofu boxes. Mixed-content
 * emoji rendering requires AnnotatedString with per-span fontFamily
 * overrides, which is a separate larger change.
 */
expect val EmojiFontFamily: FontFamily
