# Talon

A fast native chat client for [Urbit](https://urbit.org). Android,
Linux, macOS, and Windows. Built around the daily-use chat loop: DMs,
group channels, threads, reactions.

## Install

Pick your platform on the [latest release page](https://github.com/nisfeb/talon/releases/latest):

| Platform | File | How to install |
|---|---|---|
| Android (most phones) | `talon-X.Y.Z-arm64-v8a.apk` | Tap to install. May require enabling "Install unknown apps" for your browser/file manager. Android 8+ (API 26). |
| Android (older 32-bit ARM) | `talon-X.Y.Z-armeabi-v7a.apk` | Same; pick this only if your device reports `armeabi-v7a` and not `arm64-v8a`. |
| Android (any architecture) | `talon-X.Y.Z-universal.apk` | Larger fallback — works on every supported ABI. Use if you're not sure which split to grab. |
| macOS | `Talon-X.Y.Z.dmg` | Open the DMG, drag Talon to Applications. **First launch**: right-click the app → Open → Open. (Unsigned, so a normal double-click is blocked by Gatekeeper.) |
| Windows | `Talon-X.Y.Z.msi` | Double-click. SmartScreen may warn — click "More info" → "Run anyway". |
| Linux (any) | `Talon-x86_64.AppImage` | `chmod +x Talon-x86_64.AppImage && ./Talon-x86_64.AppImage`. Needs FUSE 2 (default on most desktops). |
| Debian / Ubuntu | `talon_X.Y.Z-1_amd64.deb` | `sudo apt install ./talon_X.Y.Z-1_amd64.deb` |

Everything is self-contained — desktop builds bundle a JRE, so you
don't need Java installed.

## What it does

- Sign in to your ship and use it for chat. Multiple ships supported —
  switch between them from the top-left.
- DMs (single + group), %chat group channels, threads, reactions
  (in chats and threads), edits, deletes, image / file attachments,
  link previews, location + calendar tags. Tap an image to open the
  fullscreen viewer with pinch-to-zoom, save it to Photos / Downloads.
  Channel posts you send carry a small clock / "!" indicator until
  the host echoes them back, so a silently-failing poke is visible.
- AI catch-up summaries, watchwords (highlight terms across chats),
  emoji-react suggestions — all opt-in, BYO API key.
- Daily digests (Android only — uses AlarmManager).
- Semantic search across chat history. Android uses MediaPipe; desktop
  uses an on-device sentence-transformer (DJL ONNX, model auto-cached
  on first use under `~/.djl.ai/cache`, ~30 MB).
- Native OS notifications (libnotify on Linux, Notification Center on
  macOS, system toasts on Windows / Android), system tray (desktop),
  dark / light / system theme, in-app updater (Android sideload),
  per-chat mute, folder organization.

## Notifications (Android)

For pushes that arrive when Talon's process has been killed by Android,
force-stopped, or evicted by the dataSync background-cap. Without this,
notifications still work *while Talon is running* — they just stop the
moment Android decides to reclaim the foreground service. This setup
plugs that gap.

The architecture is server-relay-plus-distributor — your ship's chat
events flow through a relay holding a 24×7 SSE connection, which POSTs
to a per-device endpoint minted by a local push distributor app
([UnifiedPush](https://unifiedpush.org)). No FCM, no Play Services.

### Setup (~2 minutes)

1. **Install ntfy from F-Droid** (or any other UnifiedPush distributor —
   NextPush, Conversations, etc.). The Play Store build of ntfy strips
   the UnifiedPush distributor; F-Droid's doesn't.

   F-Droid → search "ntfy" → install.

2. **Open ntfy once**, tap the ⋮ menu → Settings → scroll to the
   **UnifiedPush** section → confirm the toggle is on. Default server
   is `ntfy.sh`; leave that for now unless you self-host an ntfy server.

3. **In Talon**: Settings → Notifications → **Push relay** section.
   - Endpoint: `https://relay.nisfeb.com` (the relay I run; see below
     to self-host instead)
   - Tap **Save endpoint**, then **Register this device** → paste your
     ship's `+code` → Register
   - Status should jump to `Registered (deviceId=…)` within a second
   - If it doesn't, tap **Diagnose distributor** for an inline report
     of what's broken (PackageManager / connector / cached endpoint)

That's it. Test by killing Talon (swipe from recents) and having
someone DM you — push should arrive on the lock screen.

### About the `+code`

The relay needs your ship's `+code` to log in once and derive a session
cookie. The `+code` is encrypted at rest (AES-GCM, key derived from a
relay-side master secret via PBKDF2). Same trust model as any hosted
ship — if you're not comfortable with this, self-host the relay below.

### Self-host the relay

The relay is a Kotlin/JVM service in [`relay/`](relay/). Single
`docker build` from the repo root, `RELAY_MASTER_SECRET` env var,
volume-mount `/data` for the SQLite store. Full deploy notes in
[`relay/README.md`](relay/README.md). Behind nginx or Caddy with
TLS — anything reachable over HTTPS works. Point Talon's endpoint
field at your URL and you're off the operator path entirely.

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
./gradlew :composeApp:installDebug

# Android signed release APKs — produces per-ABI splits +
# universal fallback under composeApp/build/outputs/apk/release/
./gradlew :composeApp:assembleRelease

# Desktop self-contained app dir (host OS only)
./gradlew :composeApp:createReleaseDistributable

# Desktop installers — package* tasks build for the host OS only.
./gradlew :composeApp:packageReleaseDeb     # .deb on Linux
./gradlew :composeApp:packageReleaseDmg     # .dmg on macOS
./gradlew :composeApp:packageReleaseMsi     # .msi on Windows
scripts/build-appimage.sh                   # AppImage on Linux
```

The desktop release build runs `slimReleaseDistributable` automatically
to strip non-host native libs and unused Material Icons Extended
classes — see `composeApp/build.gradle.kts` and `CLAUDE.md` for
details.

### Layout

```
composeApp/                — KMP module (Android + Linux/macOS/Windows desktop)
  src/commonMain/          — shared screens, repos, data layer (~26k lines)
  src/androidMain/         — Android-only impls + background services
  src/desktopMain/         — Desktop impls + Main.kt entry
  src/commonTest/          — pure-logic tests (run on every target)
  src/desktopTest/         — JVM-only tests (where most coverage lives)
.github/workflows/         — release CI (per-ABI APKs + .deb/.dmg/.msi/.AppImage)
scripts/build-appimage.sh  — AppImage packaging
CLAUDE.md                  — architecture + cross-platform discipline
RELEASE.md                 — keystore + tagging procedure
```

### Releases

Tag `vX.Y.Z` (matching `versionName` in `composeApp/build.gradle.kts`)
and push. CI builds all platform artifacts and publishes a GitHub
Release. Configure the four `RELEASE_KEYSTORE_*` repo secrets to
enable APK signing — without them, desktop artifacts still ship.

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
