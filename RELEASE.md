# Releasing Talon publicly

## Current state

- Sideload-only via [GitHub Releases](https://github.com/nisfeb/talon/releases).
  Each tagged push runs the CI matrix in `.github/workflows/release.yml`
  and publishes Android `.apk`, Linux `.deb` + `.AppImage`, macOS
  `.dmg`, and Windows `.msi` artifacts.
- Android APK is signed with a real release keystore (kept outside
  the repo, decoded on the runner from the `RELEASE_KEYSTORE_BASE64`
  secret). Once you've installed a release-signed build, future
  versions install as updates without uninstall.
- Desktop builds are unsigned for now — Gatekeeper / SmartScreen will
  warn on first launch. See README's install table for the per-OS
  bypass.
- No Play Store / F-Droid listing yet; the rest of this doc captures
  what'd be required to add one.

## Decide: Play Store or F-Droid (or both)

**Play Store**
- Required: privacy policy URL, app screenshots, a target SDK ≥ the
  "latest - 1" Android version (Nov 2026 that's 35, you're already on 35),
  a closed test track with ≥ 12 testers for ≥ 14 days before open beta.
- $25 one-time developer fee.
- You ship a signed AAB (`./gradlew :composeApp:bundleRelease`), not APK.
- Updates gate behind Play review (~hours).
- **Your situation:** viable. The crypto library (`androidx.security.crypto`)
  and the API-key storage warrant a tight privacy policy ("AI keys
  stored encrypted on device; never transmitted except to the
  user-selected provider"). Spell out that the app talks to a
  user-provided Urbit ship over user-configured HTTP.

**F-Droid**
- Required: fully open-source repo, reproducible build, F-Droid metadata under `fastlane/metadata/android/` (or alongside `composeApp/`, depending on the layout F-Droid's metadata fork ends up using).
- F-Droid's build server handles signing.
- Updates usually lag ~1 week.
- **Your situation:** the AI providers (Anthropic / OpenAI / custom)
  hit third-party servers — F-Droid flags this with an anti-feature
  tag, not blocking but noted.

**Recommendation:** ship both. F-Droid reaches the Urbit-native crowd
(who prefer it), Play reaches everyone else. F-Droid is less work if
you're already on GitHub.

## Pre-release keystore

```sh
keytool -genkey -v \
    -keystore release.keystore \
    -alias talon \
    -keyalg RSA -keysize 4096 \
    -validity 36500 \
    -storetype PKCS12
```

Keep a second copy in cold storage (printed QR of the keystore +
password, or a hardware token). Losing it means you can never update
this app on Play again.

Wire it into `composeApp/build.gradle.kts`:

```kotlin
signingConfigs {
    create("release") {
        storeFile = file(System.getenv("TALON_KEYSTORE") ?: "release.keystore")
        storePassword = System.getenv("TALON_KEYSTORE_PASS")
        keyAlias = "talon"
        keyPassword = System.getenv("TALON_KEY_PASS")
    }
}

buildTypes {
    release {
        signingConfig = signingConfigs.getByName("release")
        // …existing minify/shrink config…
    }
}
```

Never commit the keystore or passwords — `*.keystore` and `*.jks` are
already in `.gitignore`.

## Version bumping

In `composeApp/build.gradle.kts`:

```kotlin
versionCode = 23      // monotonic, +1 per published build (Play requires)
versionName = "0.6.2" // user-visible
```

`derivePackageVersion()` in the same file rewrites `0.M.P → 1.M.P`
when handing to jpackage (jpackage rejects MAJOR=0). After the
project crosses 1.0 that mapping becomes identity. Don't update it
prematurely.

Tag and push:

```sh
git tag v0.6.2
git push --tags
```

## Pre-release verification checklist

Run through this every tag. It's not automated — don't skip it.

**Offline unit tests:**
- [ ] `./gradlew :composeApp:desktopTest` → all green (this runs every
  test the JVM can run, ~558 methods, sub-second total).

**Fakezod smoke test** (see `scripts/fakezod/README.md`):
- [ ] `./boot.sh && ./install-tlon.sh` land cleanly on current
  `tlon-apps` main.
- [ ] Fresh install of the release APK on a clean-state device.
- [ ] Sign in against the fakezod.
- [ ] Create a group, chat channel, notebook channel, gallery channel.
- [ ] Post one of each content type; edit and delete each.
- [ ] React / unreact.
- [ ] Invite a ghost ship; revoke the invite.
- [ ] Administration → view one of your real groups, Members list
  populates correctly.
- [ ] Switch ships (if you have two logged in) and verify no data
  bleed (previews, unread counts).
- [ ] Kill the app mid-SSE, reopen, confirm backfill catches anything
  missed.

**Real-ship smoke test:**
- [ ] Same steps on your actual daily-driver ship, just to catch
  anything fakezod-specific.
- [ ] Watch `adb logcat -s TlonChatRepo` — no `poke nack` lines should
  appear during normal use.

**Desktop split-pane smoke (≥0.9.0):**
- [ ] Wide window (≥840dp): chat list left, chat detail right.
- [ ] Drag handle resizes panes; range clamps at ~20% / ~50%.
- [ ] Quit + relaunch: same chat selected on the right pane.
- [ ] Resize narrow (<840dp): collapses to stacked nav, current chat preserved.
- [ ] Tablet landscape (Android): split-pane visible.
- [ ] Tablet portrait (Android): stacked nav.
- [ ] Ctrl+K opens search screen.
- [ ] Ctrl+N opens new-DM dialog.
- [ ] Esc closes thread → chat → settings, in that order. Final Esc is no-op.
- [ ] Ctrl+, opens Settings.
- [ ] Ctrl+1..9 switches ships when multiple are signed in.
- [ ] Cmd+ variants on macOS work the same.

**Desktop rail + secondary surfaces (≥0.9.0):**
- [ ] Wide window (≥840dp): 64dp icon rail visible on the far left.
- [ ] Rail has four icons (Chats, Statuses, Bookmarks, Activity) — hover shows a tooltip with the label.
- [ ] Selected rail icon has a primary-colour pill on its left edge.
- [ ] Tapping a rail icon swaps only the list pane; the chat detail pane stays.
- [ ] Activity-tab → tap a row → opens the chat in the right pane (rail tab does NOT auto-switch back to Chats).
- [ ] Bookmarks-tab → tap a bookmark → opens the chat in the right pane (same).
- [ ] Statuses-tab → tap a contact → opens the contact profile (existing full-screen replace, same as today).
- [ ] Kebab menu (more menu in chat list header) → "Statuses" item → on wide, flips rail to Statuses; on narrow, full-screen StatusFeedScreen with back arrow.
- [ ] Resize wide → narrow: rail disappears, current rail tab is preserved but invisible; kebab menu still works.
- [ ] Resize narrow → wide: rail re-appears with the previously-selected tab.
- [ ] Quit + relaunch: same rail tab selected (per ship).
- [ ] Switch ships (multi-ship users): rail tab preference is per-ship.
- [ ] Tablet landscape (Android): same behaviour as desktop wide.
- [ ] Tablet portrait (Android): rail not visible; kebab menu navigation as on phone.

**Build artifacts:**
- [ ] `./gradlew :composeApp:assembleRelease` produces signed APKs
  under `composeApp/build/outputs/apk/release/`. With ABI splits
  enabled, you get four artifacts:
  `composeApp-arm64-v8a-release.apk` (~25 MB),
  `composeApp-armeabi-v7a-release.apk` (~20 MB),
  `composeApp-x86_64-release.apk` (~18 MB), and
  `composeApp-universal-release.apk` (~54 MB fallback).
- [ ] `./gradlew :composeApp:bundleRelease` produces an AAB (Play).
- [ ] Install the **arm64-v8a** APK fresh on a phone that never had
  the app → login → open chat → send a message. Most modern Android
  devices are arm64-v8a; that's the artifact most users get. Also
  smoke the universal APK if you've changed anything that could vary
  by ABI (native deps, packaging rules). Catches proguard / R8
  stripping bugs.
- [ ] `scripts/build-appimage.sh` produces `dist/Talon-x86_64.AppImage`
  (~91 MB). Launch once: window opens, native notification fires
  via `notify-send` / `gdbus` (not the AWT balloon). The
  `:composeApp:notifierSmoke` Gradle task fires a single notification
  for manual UX verification.
- [ ] `./gradlew :composeApp:packageReleaseDeb` (Linux),
  `packageReleaseDmg` (macOS), `packageReleaseMsi` (Windows) — each
  runs only on its host OS via CI matrix.

**Legal:**
- [ ] Privacy policy URL reachable.
- [ ] "Open source licenses" screen present (add in Settings if not).
- [ ] Screenshots updated if UI changed.

## Crash reporting

Not wired yet. Recommend Sentry (there's a free tier that's fine for
this volume). Keep it opt-in — Urbit users tend to object to
unconditional telemetry. Add the toggle in Settings and gate the
Sentry init on it.

## Post-release

- Watch Play Console's "Android vitals" (ANR + crash rate). Anything >1%
  should be investigated.
- Subscribe to `tlon-apps` GitHub releases; any time they push a
  desk change, re-run the fakezod smoke test before users notice.
