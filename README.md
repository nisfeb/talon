# Talon

Native chat client for Urbit. Android-first; desktop (Linux / macOS /
Windows) ships via Compose Multiplatform — see [PLAN.md](PLAN.md).

## Requirements

- JDK 17 (Temurin or any full JDK with the standard jmods)
- Android SDK 34, build-tools 34 (Android target only)
- Gradle wrapper handles its own version

## Build

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

## Layout

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

## Releases

Tag `vX.Y.Z` (matching `versionName` in `app/build.gradle.kts`) and
push. CI builds all platform artifacts and publishes a GitHub Release.
Configure the four `RELEASE_KEYSTORE_*` repo secrets to enable APK
signing — without them, desktop artifacts still ship.

## Pre-commit hook

After cloning, enable the in-repo hook that scans staged changes for
personal info and secret patterns (private keys, AWS / Anthropic /
OpenAI / GitHub tokens, personal emails, machine-specific paths,
etc.):

```bash
git config core.hooksPath scripts/hooks
```

See [scripts/hooks/pre-commit](scripts/hooks/pre-commit) for the
ruleset. Bypass with `git commit --no-verify` when you need to (e.g.
adding a test fixture that intentionally contains a real patp).
