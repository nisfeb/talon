# Talon — agent guidance

Talon is a Compose Multiplatform Urbit chat client targeting Android +
JVM Desktop (Linux/macOS/Windows). Source layout:

- `composeApp/src/commonMain` — most features (~26k lines, 139 files)
- `composeApp/src/androidMain` — Android-only deps + background services
  (BootReceiver, DigestAlarmReceiver, TalonSyncService, ShortcutsPublisher)
  that have no desktop concept
- `composeApp/src/desktopMain` — leaf impls only
- `composeApp/src/desktopTest` — most automated tests run here

Common is the default. Reach for a leaf source set only when the work
genuinely can't live in common.

## Cross-platform feature discipline

These principles are load-bearing — apply them when adding features,
debugging, or reviewing PRs. Drift here causes silent platform gaps.

### 1. Capability-flag at the boundary, not in the leaf

Every user-visible feature that one platform doesn't support needs an
`expect val isFooSupported: Boolean` in `composeApp/src/commonMain/.../ui/Capabilities.kt`.
The gating UI lives in `commonMain` as `if (isFooSupported) { … }`.
The non-supporting platform returns `false`.

Existing flags: `isDailyDigestSupported`, `isVoiceMessagesSupported`,
`isOnDeviceAiSupported`, `isOnDeviceAiFeatureSupported(...)`.

When you add a feature only one platform can do, the capability flag
is part of the same change — not a follow-up.

### 2. Same-shape interface in common, impl per leaf — with a Noop default

When the *API* is the same but the *backend* differs, write
`interface Foo` in `commonMain` with platform impls in
`androidMain` / `desktopMain`. Always include a `NoopFoo` companion
right next to the interface so a leaf that hasn't implemented it can
wire the no-op and the app still compiles + runs.

Examples: `Notifier` / `NoopNotifier`, `SessionStore`, `UiSettings`,
`AiSettingsRepository`, `SearchEmbedderClient`.

This is the preferred pattern. Use `expect`/`actual` only when every
platform *must* implement (image pickers, `AppDatabaseConstructor`,
`Log`, `Modifier.onSecondaryClick`).

### 3. Don't fake a feature; gate it

If desktop can't do Daily Digest because there's no AlarmManager, the
desktop UI should *not show* the digest section. Don't grey it out;
don't show "coming soon"; don't ship a stub that emits a Toast. Faking
creates expectation; gating creates clarity. The capability flag plus
the `if` block in the screen handles this.

### 4. Document new capability flags in `Capabilities.kt`

Every `expect val is*Supported` lives in or near `Capabilities.kt`.
The file's top comment is the registry — add a row when you add a
flag: name, which platforms are true, why the off-platform doesn't
support it. Five lines per flag, max. Grep `expect val is` to find
the inventory.

### 5. Audit by greppability

`grep "expect val is" composeApp/src/commonMain` is the inventory.
After significant work or before a release, scan it: is each flag
still correct? Has the missing capability landed? When DJL ONNX
shipped on desktop, `isOnDeviceAiSupported` flipped from `false` to
`true` — that flag was the *only* place to make the change.

### 6. Android-only code needs a deliberate decision

The compiler won't catch new code in `androidMain` that has no
`commonMain` reference (e.g., a new background service, intent
receiver, or Android-system integration). The mitigation is
convention: when you add Android-only code, decide explicitly
whether it has a desktop analog.

- Yes → add a tracking note in the file's top comment ("desktop
  port pending — needs equivalent for X") so it's discoverable.
- No → say so in the top comment ("Android-only: no desktop analog
  — depends on AlarmManager / BootReceiver / etc.") so the next
  reader doesn't burn time looking for a missing impl.

The 8× imbalance in code volume between `androidMain` and
`desktopMain` is real and growing. New Android-only additions
should be infrequent and intentional.

## Test coverage caveat

`desktopTest` (~7k lines) carries most of the automated coverage.
`commonTest` only has 3 files. Pure-logic tests should land in
`commonTest` so both targets exercise them, not in `desktopTest`.
Bugs that only manifest under the Room Android driver, MediaPipe
text task, or Android activity-lifecycle won't be caught by CI —
that's the gap to be aware of when shipping Android-specific changes.

## Build artifacts

- Android release APKs: `composeApp/build/outputs/apk/release/`
  (per-ABI splits + universal — see ABI splits in build.gradle.kts)
- Desktop AppImage: `dist/Talon-x86_64.AppImage` via
  `scripts/build-appimage.sh` (depends on `slimReleaseDistributable`
  Gradle task, which strips non-host native libs and the unused
  Material Icons Extended classes)

## Releasing — let CI ship, don't `gh release create`

The full ship workflow is bump version → commit → tag → push tag.
That triggers `.github/workflows/release.yml`, which signs APKs with
the real release keystore, builds the desktop matrix, generates
`latest.json`, and publishes the GitHub Release. Local builds are
debug-signed because `RELEASE_KEYSTORE_PROPS` isn't set in dev envs;
uploading them via `gh release create` puts mismatched-cert artifacts
on the same release and breaks the in-app upgrade path with a generic
"App not installed" error. rc27/28/29 all shipped this way before the
mistake was caught — see RELEASE.md "CI publishes the release" for
the post-mortem.

Local `assembleRelease` / `build-appimage.sh` are for smoke testing
the build still works before pushing the tag. Do not upload their
output.

## Notification path (desktop)

`SystemNotifier` (desktopMain) shells out to `notify-send` → `gdbus`
on Linux, `osascript` on macOS, falls through to AWT tray on
Windows (already native there) and as final fallback. The inline
`trayState.sendNotification(...)` lambda was replaced because AWT
balloons on Linux don't go through libnotify and look out of place.
Manual smoke: `./gradlew :composeApp:notifierSmoke`.
