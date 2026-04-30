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
- Required: fully open-source repo, reproducible build, `fastlane/metadata/` under `app/`.
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
versionCode = 2   // monotonic, +1 per published build (Play requires)
versionName = "0.2.0"   // user-visible
```

Tag and push:

```sh
git tag v0.2.0
git push --tags
```

## Pre-release verification checklist

Run through this every tag. It's not automated — don't skip it.

**Offline unit tests:**
- [ ] `./gradlew test` → all green.

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

**Build artifacts:**
- [ ] `./gradlew :composeApp:assembleRelease` produces a signed APK.
- [ ] `./gradlew :composeApp:bundleRelease` produces an AAB (Play).
- [ ] Install the release APK fresh on a phone that never had the app
  → login → open chat → send a message. (Catches proguard / R8
  stripping bugs. We've already hit these once; R8 is configured but
  worth the smoke.)

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
