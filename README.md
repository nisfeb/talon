# Talon

A fast native chat client for [Urbit](https://urbit.org). Android,
Linux, macOS, and Windows. Built around the daily-use chat loop: DMs,
group channels, threads, reactions.

## Install

Pick your platform on the [latest release page](https://github.com/nisfeb/talon/releases/latest):

| Platform | File | How to install |
|---|---|---|
| Android | `talon-X.Y.Z.apk` | Tap to install. May require enabling "Install unknown apps" for your browser/file manager. Android 8+ (API 26). |
| macOS | `Talon-X.Y.Z.dmg` | Open the DMG, drag Talon to Applications. **First launch**: right-click the app → Open → Open. (Unsigned, so a normal double-click is blocked by Gatekeeper.) |
| Windows | `Talon-X.Y.Z.msi` | Double-click. SmartScreen may warn — click "More info" → "Run anyway". |
| Linux (any) | `Talon-x86_64.AppImage` | `chmod +x Talon-x86_64.AppImage && ./Talon-x86_64.AppImage`. Needs FUSE 2 (default on most desktops). |
| Debian / Ubuntu | `talon_X.Y.Z-1_amd64.deb` | `sudo apt install ./talon_X.Y.Z-1_amd64.deb` |

Everything is self-contained — desktop builds bundle a JRE, so you
don't need Java installed.

## What it does

- Sign in to your ship and use it for chat. Multiple ships supported —
  switch between them from the top-left.
- DMs (single + group), %chat group channels, threads, reactions,
  edits, deletes, image / file attachments, link previews, location
  + calendar tags.
- AI catch-up summaries, daily digests, watchwords (highlight terms
  across chats), semantic search — all opt-in, BYO API key.
- OS notifications, system tray, dark / light / system theme, in-app
  updater (Android), per-chat mute, folder organization.

## Need help / found a bug?

File an issue at <https://github.com/nisfeb/talon/issues>. Please
include your platform + version, what you were doing, and what
happened. Screenshots help a lot.

---

## Building from source

You only need this section if you're contributing or building Talon
yourself.

### Requirements

- JDK 17 (Temurin or any full JDK with the standard jmods)
- Android SDK 34, build-tools 34 (Android target only)
- Gradle wrapper handles its own version

### Build

```bash
# Android debug install on a connected device
./gradlew installDebug

# Android signed release APK (requires keystore — see RELEASE.md)
./gradlew :app:assembleRelease

# Desktop self-contained app dir
./gradlew :composeApp:createReleaseDistributable

# Desktop installers / AppImage — see scripts/build-appimage.sh and
# the package* tasks (packageReleaseDeb, packageReleaseDmg,
# packageReleaseMsi). Each builds for the host OS only.
```

### Layout

```
app/                       — Android-only production app
composeApp/                — KMP module (Android + desktop)
  src/commonMain/          — shared screens, repos, data layer
  src/androidMain/         — Android actuals (file picker, etc.)
  src/desktopMain/         — Desktop actuals + Main.kt entry
.github/workflows/         — release CI (Android APK, .deb, .dmg, .msi, AppImage)
scripts/build-appimage.sh  — AppImage packaging
RELEASE.md                 — keystore + tagging procedure
```

### Releases

Tag `vX.Y.Z` (matching `versionName` in `app/build.gradle.kts`) and
push. CI builds all platform artifacts and publishes a GitHub Release.
Configure the four `RELEASE_KEYSTORE_*` repo secrets to enable APK
signing — without them, desktop artifacts still ship.

### Pre-commit hook

After cloning, install the in-repo hooks once:

```bash
./scripts/install-hooks.sh
```

That sets `core.hooksPath` to `scripts/hooks/`, where the versioned
`pre-commit` lives. The hook scans staged changes for personal info
and secret patterns (private keys, AWS / Anthropic / OpenAI / GitHub
tokens, personal emails, machine-specific paths, etc.) — see
[scripts/hooks/pre-commit](scripts/hooks/pre-commit) for the ruleset.
Bypass for one commit with `git commit --no-verify` when you need to
(e.g. adding a test fixture that intentionally contains a real patp).
