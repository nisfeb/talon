# Talon — Design System

This document is the canonical reference for Talon's visual identity. When
building new features, pull from `MaterialTheme.colorScheme` and
`MaterialTheme.typography`; do not hardcode hex values or font sizes in
composables. If something here stops matching the Play Store art, the
store art wins — update this doc and the theme to track it.

## Brand

**Talon** is a native Android Urbit chat client. The visual identity is:
sharp, modern, slightly brutalist — "a tool, not a toy." The icon is a
stylized raptor talon curling around a negative-space chat bubble. Two
dominant colors: amber on deep indigo.

## Palette

### Brand anchors

| Role          | Hex        | Meaning                                         |
|---------------|------------|-------------------------------------------------|
| Amber 500     | `#F59E0B`  | Primary. Talon mark, call-to-action.            |
| Amber 400     | `#FBBF24`  | Primary on dark backgrounds.                    |
| Indigo 950    | `#1E1B4B`  | Store-art background, deep secondary container. |
| Indigo 700    | `#4338CA`  | Secondary. Mentions, quotes, links.             |
| Emerald 600   | `#059669`  | Tertiary. Success, delivered, online.           |
| Red 600       | `#DC2626`  | Destructive. Delete, send failures.             |

### Neutrals (stone ramp)

Warm neutrals, not pure grays — they sit better next to amber.

| Stone | Hex        | Light-mode use               | Dark-mode use           |
|-------|------------|------------------------------|-------------------------|
| 50    | `#FAFAF9`  | background                   | onBackground / onSurface|
| 100   | `#F5F5F4`  | surfaceVariant               | —                       |
| 200   | `#E7E5E4`  | outlineVariant               | —                       |
| 400   | `#A8A29E`  | outline                      | onSurfaceVariant        |
| 600   | `#57534E`  | onSurfaceVariant             | —                       |
| 900   | `#1C1917`  | onBackground, onPrimary      | onPrimary               |

### Material3 mapping

See `app/src/main/java/io/nisfeb/talon/ui/theme/Theme.kt` for the full
`lightColorScheme` / `darkColorScheme` wiring. Role assignments:

- `primary` — send button, selected reaction pill, AI pick, pin indicator,
  catch-me-up banner, focus ring.
- `secondary` — mention tokens, link color, quote accent bar, citation chips.
- `tertiary` — sync-confirmed checkmarks, voice-message accent, delivery ticks.
- `surface` — message list background.
- `surfaceVariant` — incoming message bubble, quote preview row, inline code bg.
- `error` — delete button, send failures, form validation.

### Dynamic color is off

Android's Material You dynamic theming is explicitly disabled. Brand
consistency with the store listing takes priority over per-device wallpaper
integration. If we later want user-facing customization, expose it as an
opt-in setting, not the default.

## Typography

Type families (downloadable fonts, to be wired in `res/font/`):

- **Space Grotesk** — display / titles. Matches the Talon wordmark in the
  feature graphic. Weights: 500, 700.
- **Inter** — body, labels, all UI text. Weights: 400, 500, 600.
- **JetBrains Mono** — patps, ship IDs, inline code, code blocks.

Until fonts are wired, `FontFamily.Default` is used — layout/spacing is
already final in `Typography.kt` so switching families is a one-line swap.

### Scale (Material3 roles)

| Token          | Family  | Weight    | Size | Line | Use                               |
|----------------|---------|-----------|------|------|-----------------------------------|
| displayLarge   | Display | Bold      | 40   | 48   | Splash screens, empty states.     |
| headlineMedium | Display | SemiBold  | 24   | 30   | Settings sections.                |
| titleLarge     | Display | SemiBold  | 20   | 26   | Screen titles.                    |
| titleMedium    | Body    | SemiBold  | 16   | 22   | Row titles, sheet headers.        |
| titleSmall     | Body    | SemiBold  | 14   | 20   | Subsection labels.                |
| bodyLarge      | Body    | Regular   | 16   | 22   | Message body (primary reading).   |
| bodyMedium     | Body    | Regular   | 14   | 20   | Composer, most body text.         |
| bodySmall      | Body    | Regular   | 12   | 16   | Timestamps, hint text.            |
| labelLarge     | Body    | Medium    | 14   | 18   | Buttons, toggles.                 |
| labelMedium    | Body    | Medium    | 12   | 16   | Counters, small pills.            |
| labelSmall     | Body    | Medium    | 11   | 14   | Badges, tiny metadata.            |

## Icon & store assets

Masters live in `branding/` at the repo root. Don't regenerate from
scratch — work from these if refining.

- `branding/ic_launcher_full_1024.png` — full composite with indigo background.
- `branding/ic_launcher_foreground_1024.png` — talon only, transparent bg.
- `branding/ic_launcher_monochrome_1024.png` — silhouette for themed icons.
- `branding/play_store_icon_512.png` — exact file to upload to Play Console.

Installed into the APK at:

- `mipmap-<density>/ic_launcher.png` + `ic_launcher_round.png` — legacy
  launcher icons (48/72/96/144/192 dp).
- `mipmap-<density>/ic_launcher_foreground.png` — adaptive foreground
  (108/162/216/324/432 px).
- `mipmap-<density>/ic_launcher_monochrome.png` — Android 13+ themed icon.
- `mipmap-anydpi-v26/ic_launcher.xml` + `ic_launcher_round.xml` — adaptive
  icon definitions wiring background color + foreground + monochrome.
- `values/colors.xml` — `ic_launcher_background` = `#1E1B4B`.

**Feature graphic** — 1024×500, icon left ~40%, wordmark right ~60%,
radial indigo-to-black gradient background with scattered amber accent
shapes. Not yet generated.

**Screenshots** — live device captures via `adb exec-out screencap -p`,
2–8 per Play listing. Never AI-generated (Play Store policy + authenticity).

If refreshing the icon, regenerate all density variants from the 1024 masters
using ImageMagick `-filter Lanczos` and the density ratios above.

## How to extend

1. **Needing a new semantic color?** Add it to both `LightColors` and
   `DarkColors` (or reuse an existing role if it fits). Document the new
   role assignment in the Material3 mapping table above.
2. **Needing a new font style?** Add to `TalonTypography`; don't build
   one-off `TextStyle`s inline. Update the scale table.
3. **Tempted to hardcode a hex?** Stop. If there genuinely isn't a role
   that fits, add one — don't drift from the palette.
4. **Shipping a feature that touches color/type?** Update this doc in the
   same PR. Treat `DESIGN.md` as non-optional documentation, not a nice-to-have.
