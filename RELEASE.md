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

**Right column — threads + group info + media drilldowns (≥0.10.0):**
- [ ] Wide window, group chat: tap info icon in chat header → group info opens in right column.
- [ ] Wide window, DM chat: no info icon visible.
- [ ] Wide window, club chat: info icon visible.
- [ ] Wide window: tap a thread reply → thread opens in right column; chat detail stays.
- [ ] Wide window: open group info, then open a thread → group info closes, thread takes its place.
- [ ] Wide window: open thread, then tap info icon → thread closes, group info takes its place.
- [ ] Group info pane: only categories with count > 0 render in the stats grid.
- [ ] Group info pane: tapping a category swaps the right pane to the drilldown list (header changes to "Photos" / "Videos" / etc., X closes back to group info).
- [ ] Group info pane: mute toggle reads + writes per-chat notify level.
- [ ] Group info pane: View members (N) opens the existing GroupAdminScreen.
- [ ] Group info pane: Leave group leaves the chat.
- [ ] Drilldown — Photos: tap a row → image viewer opens.
- [ ] Drilldown — Links: tap a row → URL opens in system browser.
- [ ] Drilldown — Voice: voice-message rows show "🎙 Voice Ns" labels and play via the system handler.
- [ ] Mobile (<840dp): tap info icon → full-screen GroupInfoScreen.
- [ ] Mobile: tap a category → full-screen MediaListScreen; back returns to GroupInfoScreen.
- [ ] Mobile: thread replies still go to full-screen ThreadScreen with back arrow (unchanged from prior versions).
- [ ] Backfill: install over an existing 0.9.x install with chat history → group info shows correct counts within seconds (live updates).
- [ ] Cold open of a fresh group with one shared image: count appears on first render.
- [ ] New message arrives while group info is open: count increments live.

**Customizable rail items (≥0.11.0):**
- [ ] Wide window: rail shows all 10 items by default (Chats, Statuses, Bookmarks, Activity, My profile, Watchwords, Today's brief — when digest enabled, Administration, Invites, Settings).
- [ ] Today's brief icon hides automatically when daily digest is disabled.
- [ ] Tap rail icon for a list-pane item (Statuses / Bookmarks / Activity) → list pane swaps; chat detail stays.
- [ ] Tap rail icon for a modal item (Settings / Profile / etc.) → corresponding screen opens full-screen.
- [ ] Selected pill highlights only the active pane-tab item; modal items never get the pill.
- [ ] Open Settings → Sidebar → toggles for each item. Chats row is always-on (badge "On"). Today's brief row is greyed out + explainer when digest is disabled.
- [ ] Toggle an item off in Sidebar settings → its rail icon disappears within a frame.
- [ ] Toggled-off item appears in the kebab dropdown.
- [ ] Toggle back on → rail icon reappears, kebab item disappears.
- [ ] All items toggled off (except Chats which is always-on): rail still renders Chats; kebab on wide collapses to "Sign out" + every other item.
- [ ] Sign out always present in kebab regardless of any toggle state.
- [ ] Cross-device sync: toggle off on desktop → mobile's kebab shows the item within seconds (via %settings rebroadcast). Verify with mobile app open during the toggle.
- [ ] Multi-ship: toggling on Ship A doesn't affect Ship B's rail (per-ship sync — each ship has its own %settings).
- [ ] Compact (mobile / narrow desktop): kebab shows every item regardless of rail visibility.
- [ ] Mobile-only path: open Sidebar settings → toggle items → switch to desktop later → rail reflects the mobile-set visibility.

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
