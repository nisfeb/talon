# CMP desktop port — Stage F follow-ups

**Status:** Stages A through E complete on `port/cmp-desktop`. Stage F
is partially complete; this doc lists what's needed before `app/` can
be deleted and the branch merged to `master`.

## What Stage F1 verified locally

- `:app:assembleDebug`, `:composeApp:compileDebugKotlinAndroid`, and
  `:composeApp:compileKotlinDesktop` all compile clean on every commit
  in the stage range.
- `:app:testDebugUnitTest` passes (36 test files, all green).
- `:composeApp:createReleaseDistributable` produces a 179 MB runnable
  directory at `composeApp/build/compose/binaries/main-release/app/Talon/`
  with native ELF launcher + bundled JRE + Linux PNG icon.
- The desktop launcher runs headless for ≥8 s without crashing on
  startup. Full GUI smoke (login, chat list, send/receive, etc.) needs
  user-driven testing on a real X11/Wayland session.

## What still blocks deleting `app/`

`composeApp/` is feature-parity with `app/` at the **UI layer only**.
The production module also owns a platform-services layer that
`composeApp` doesn't replicate yet:

### Gap 1 — Application class

- `app/.../TalonApplication.kt` is the per-process Application subclass
  that wires `aiSettings`, `watchwords`, `ai`, `notifications`,
  `digestScheduler`, the share-target singleton, and the
  `RELEASE_KEYSTORE_PROPS` keystore reading.
- `composeApp/.../compose/MainActivity.kt` constructs a minimal subset
  per-onCreate. No Application subclass, so anything that needs to
  outlive an Activity (sync service, alarms) has nowhere to live.

### Gap 2 — Foreground sync service

- `app/.../TalonSyncService.kt` is a `Service` declared with
  `foregroundServiceType="specialUse|dataSync"` that maintains the
  long-lived SSE channel. Without it, chat notifications stop arriving
  while the app is backgrounded.
- Not ported. composeApp has no `Service` subclass.

### Gap 3 — Boot + alarm receivers

- `BootReceiver` re-arms the foreground service after device reboot.
- `DigestAlarmReceiver` fires the daily-digest summary on a recurring
  AlarmManager schedule.
- Neither ported.

### Gap 4 — Update installer

- `app/.../update/UpdateInstaller.kt` wraps `PackageInstaller` so a
  downloaded APK can be installed in place via the in-app banner.
  Requires `REQUEST_INSTALL_PACKAGES` + the `FileProvider` declaration
  in the manifest.
- composeApp wires `NoopUpdateInstallerHook` instead. The banner can
  fire but pressing "Install" does nothing.

### Gap 5 — Real keystore signing

- `app/build.gradle.kts` reads `RELEASE_KEYSTORE_PROPS` env var, decodes
  the user's release keystore, and signs the APK.
- `composeApp/build.gradle.kts` uses the debug keystore. APKs from the
  current composeApp module **cannot update in place over an installed
  app/ APK** — different signing key would trigger `INSTALL_FAILED_
  UPDATE_INCOMPATIBLE`.

### Gap 6 — Application id collision

- `app/build.gradle.kts` sets `applicationId = "io.nisfeb.talon"`.
- `composeApp/build.gradle.kts` sets `applicationId =
  "io.nisfeb.talon.compose"` to coexist on-device during the port.
- Flipping composeApp to `io.nisfeb.talon` and deleting `app/`
  effectively republishes the app under the same package name. To not
  brick the user's existing 0.4.2 installation, the new APK must be
  signed with the same release key as the old one (Gap 5).

### Gap 7 — Manifest entries

`composeApp/src/androidMain/AndroidManifest.xml` is the bare-bones
spike scaffold (label "Talon (port)", one Activity, no permissions).
Production manifest has:
- 13 permissions (network, notifications, audio, location, boot, etc.)
- TalonApplication declaration
- ShareTarget intent-filter on MainActivity
- Shortcuts metadata
- TalonSyncService + 2 receivers
- FileProvider for the update installer

### Gap 8 — Manifest schema split (deferred from Stage E2)

`UpdateManifest` and `release/manifest-template.json` still use the
flat `url` / `sha256` shape. To support per-platform desktop installer
URLs, both need to grow nested `android` and `desktop.{linux,macos,
windows}` branches. Pair this with the desktop updater port (Gap 9).

### Gap 9 — Desktop updater

The desktop binary has no equivalent of `HttpUpdateChecker` +
`UpdateInstaller`. It can't self-update. Users have to download a new
installer and replace it manually. Acceptable for v0.5.0 but should be
filed for a follow-up.

### Gap 10 — Test coverage

`app/src/test/` has 36 test files. None are in `composeApp/`. Most are
JUnit-against-pure-Kotlin (parsers, helpers, story rendering) and
would compile fine if relocated to `composeApp/src/commonTest/`. Not
a blocker for shipping but the gap should be closed before app/ is
deleted, otherwise CI loses regression coverage for the duplicated
logic.

### Gap 11 — Resource bundles

`app/src/main/res/` holds drawables, mipmaps (launcher icons), themes,
strings, file_paths.xml (FileProvider config), shortcuts.xml. None of
this is in `composeApp/src/androidMain/res/`. Without these the
app would launch with a default launcher icon, default theme, and the
update installer would crash on the missing file_paths config.

## Recommended decision point

Two reasonable paths from here:

**Path A — Keep `app/` as production, use `composeApp` for desktop only.**
Smaller blast radius; the work above can be deferred indefinitely.
Drawback: dependencies live in two build files; UI changes need to
be made in both modules until the `// TEMPORARY DUPLICATE` files are
collapsed.

**Path B — Port the platform services and migrate to `composeApp`
for both Android and desktop.** Roughly another 1–2 weeks of work
across Gaps 1–7 + Gap 11. Right answer if the user wants one source
of truth.

Either path is shippable. Talk to the user before picking.

## What v0.5.0 would look like under Path A

- Bump `app/build.gradle.kts` to versionCode 20 / versionName 0.5.0.
- Tag `v0.5.0` — release.yml builds the Android APK from `app/` AND
  the desktop installers from `composeApp/` via the matrix added in
  Stage E2.
- Users on Android get a 0.5.0 APK via the existing in-app update
  banner.
- Users on Linux/macOS/Windows get a fresh installer from the GitHub
  Release.

This requires no merge of `port/cmp-desktop` if we keep both modules
side-by-side on master. The branch merge can happen and `app/` stays
intact.
