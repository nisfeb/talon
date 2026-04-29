# Release Channels

Talon currently ships with a self-update mechanism (`io.nisfeb.talon.update.*` + `REQUEST_INSTALL_PACKAGES` + `FileProvider`) that downloads signed APKs from GitHub Releases. **That mechanism is sideload-only.** Distributing the same code through Google Play violates two policies and would either be rejected at review or force a removal-update later.

This doc captures the three plausible distribution shapes, what each costs to support, and what the day-to-day release flow looks like for each.

## The Play policy problem in one paragraph

Play's [Device and Network Abuse policy](https://support.google.com/googleplay/android-developer/answer/9888379) prohibits apps that "download executable code (such as dex, JAR, .so files) from a source other than Google Play." `UpdateInstaller.download()` does exactly that — fetches a `.apk` from `releases/download/...` and invokes `PackageInstaller`. Even if you disable that flow at runtime, the `REQUEST_INSTALL_PACKAGES` permission alone triggers Play's [restricted permissions review](https://support.google.com/googleplay/android-developer/answer/12085295) which only approves apps in narrow categories (file managers, OEM updaters, third-party stores). Talon is none of those.

This is not a "we might get caught" risk. It's a hard policy violation. The only way to ship to Play is to remove the offending code path from the Play artifact.

## The three shapes

### Shape A — sideload only (current state)

What you have today. Releases are tagged on GitHub, the `release.yml` workflow signs and publishes an APK + `latest.json`, the in-app banner appears on every device that sees a higher `versionCode`, and users tap to update.

- **Pros:** zero gatekeepers. Push a tag, the new build is in users' hands minutes later.
- **Cons:** users have to enable "Install from unknown sources" once. No reach beyond the people you tell about it.
- **Code state:** ✅ shipped. Tasks 1–9 of the update-delivery plan cover this end to end.

### Shape B — Play Store only

Drop the self-update mechanism. Distribute exclusively through Play. Updates land via Play's normal staged-rollout system; users see the update notification in Play, not an in-app banner.

- **Pros:** mainstream reach, automatic update prompts, Play handles signing-key escrow (Play App Signing) so a lost upload key isn't fatal.
- **Cons:** every release blocks on Play review (~hours, sometimes a day for new permission-bearing builds). Privacy policy URL, content rating, Data Safety form, screenshots, $25 dev fee, closed-test track with ≥12 testers for ≥14 days before public beta. See `RELEASE.md` for the full pre-release checklist.
- **Code state:** the `update/` package, `REQUEST_INSTALL_PACKAGES`, and the FileProvider must be removed. Optional: integrate the [Play In-App Updates API](https://developer.android.com/guide/playcore/in-app-updates) so the in-app "update available" UX still exists, just powered by Play's mechanism.

### Shape C — both, via build flavors

Most realistic if you want both audiences. One codebase, two artifacts.

- **`direct` flavor:** the current sideload build — `REQUEST_INSTALL_PACKAGES` permission, FileProvider, `update/` package, GitHub Releases as the manifest source. Goes to GitHub, F-Droid, anywhere that accepts sideloaded APKs.
- **`play` flavor:** strips the permission, FileProvider, and `update/` package. Optionally pulls in Play's In-App Updates SDK so users still get an "update available" banner, just rendered by Play's library and updating via Play's staged-rollout pipeline. Goes to Google Play.

You ship both from the same `master`. Tagging a release builds both APKs. Each goes to its own store.

## What changes per shape

| Concern | Shape A (sideload only) | Shape B (Play only) | Shape C (both) |
|---|---|---|---|
| `app/build.gradle.kts` flavors | none | none | `productFlavors { create("direct"); create("play") }` |
| `update/` package | included | **deleted** | `direct` source set only |
| `REQUEST_INSTALL_PACKAGES` | declared | **removed** | `src/direct/AndroidManifest.xml` only |
| FileProvider for APKs | declared | **removed** | `src/direct/AndroidManifest.xml` only |
| `release.yml` build target | `:app:assembleRelease` | `:app:bundlePlayRelease` (AAB) | both: `:app:assembleDirectRelease` + `:app:bundlePlayRelease` |
| GitHub Release artifacts | APK + `latest.json` | none (or unsigned mapping for crash-symbolication) | `talon-direct-X.Y.Z.apk` + `latest.json` only — Play AAB stays out of public releases |
| Manifest discovery in-app | HTTP fetch + Urbit push (Stage C) | Play In-App Updates API (or nothing) | flavor-conditional |
| Pre-release ceremony | tag, push | tag, push, wait for Play review, fill out console forms | tag, push, ALSO Play review for the `play` artifact |
| Time from tag to user update | 5–10 min (CI + cold-start banner) | hours (Play staged rollout) | per-flavor |
| Signing key | one keystore, you control entirely | Play App Signing + upload key | one local upload key (used by both flavors); Play re-signs the `play` AAB with its managed key |

## Day-to-day release flows

### Flow A — sideload only

```bash
# 1. Bump version
$EDITOR app/build.gradle.kts   # versionCode + versionName

# 2. Commit + tag
git add app/build.gradle.kts
git commit -m "release: 0.5.0"
git tag v0.5.0
git push origin master
git push origin v0.5.0

# 3. Wait ~3 min for the release.yml workflow.
# 4. Verify the Release at https://github.com/nisfeb/talon/releases/tag/v0.5.0
# 5. On any device running an older Talon, force-stop + reopen → banner appears.
```

That's the whole release.

### Flow B — Play only

```bash
# 1. Bump version
$EDITOR app/build.gradle.kts

# 2. Build the AAB locally (Play needs an AAB, not APK)
RELEASE_KEYSTORE_PROPS=$HOME/.config/talon/keystore.properties \
    ./gradlew :app:bundleRelease

# 3. Upload app/build/outputs/bundle/release/app-release.aab to Play Console.
# 4. Promote through tracks: internal → closed → open → production.
#    First time on a track usually requires filling out:
#      - What's new (release notes, ≤500 chars)
#      - Tester list (closed track requires ≥12 testers for 14 days
#        before promoting to production)
# 5. Hit Review → Send for review. Wait hours. Maybe a policy team
#    asks about REQUEST_INSTALL_PACKAGES (if not yet removed) or
#    your privacy policy.
# 6. Once approved, staged rollout 10% → 50% → 100% over a few days.
```

For Shape B you'd remove the GitHub Releases workflow entirely or repurpose it to publish only the source-mapping/symbol artifacts, never APKs.

### Flow C — both flavors

```bash
# 1. Bump version
$EDITOR app/build.gradle.kts

# 2. Commit + tag
git tag v0.5.0
git push origin master
git push origin v0.5.0

# 3. release.yml builds the direct APK (sideload), publishes it to
#    GitHub Releases with latest.json. The CI also builds the play
#    AAB but does NOT upload it to GitHub — only stores it as a
#    workflow artifact for the next step.

# 4. (Manual, one of these:)
#    a. Download the play AAB from the workflow run, upload to Play
#       Console manually. Same flow as Shape B from there.
#    b. Use a deploy action like r0adkll/upload-google-play to
#       auto-publish to an internal track. Still gates on Play review
#       for production promotion.

# 5. Sideload users get the update in ~10 min via the in-app banner.
#    Play users get it whenever Play review finishes + their device
#    rolls out. The two audiences see new versions at different times.
#    That's a feature, not a bug — Play's review acts as a quality gate.
```

## When to make the call

You don't need to decide today. The current code state is correct for sideload-only and is reversible.

**Triggers to revisit:**

- You want to be installable from a search on a phone people don't yet own.
- You want to refer people who can't enable "Install from unknown sources" (some MDM-managed devices, locked-down family setups, etc.).
- You want crash analytics, install attribution, or staged rollout — Play gives those for free; rolling them yourself is real work.
- The sideload audience plateaus and you want to grow.

If none of those apply, Shape A is fine indefinitely. The current code is not blocking anything.

**If/when you decide to add Play:**

The cleanest order is:
1. Read `RELEASE.md` for the pre-release prep that's the same regardless of channel (privacy policy, screenshots, store listing).
2. Add the `direct` / `play` flavor split to `app/build.gradle.kts`.
3. Move `update/`, `REQUEST_INSTALL_PACKAGES`, and the FileProvider into `src/direct/`.
4. (Optional) integrate Play In-App Updates in `src/play/` so the banner UX still exists for Play users.
5. Update `release.yml` to build both flavors.
6. Add a Play Console upload step (manual or scripted via `r0adkll/upload-google-play`).

That's the migration. None of it is blocked by the current Stage A/B work — in fact, the current architecture (UpdateChecker as an interface, UpdateState as a singleton, UpdateBanner as a Composable that reads the flow) makes the flavor split easy: the `play` flavor would supply a different `UpdateChecker` implementation and the rest of the chain stays the same.

## What this means for Stage C

Stage C (the Urbit `%talon-updates` push channel) is the same mechanism — a different *discovery* path for the same `UpdateInstaller`-driven install flow. So Stage C is **also sideload-only**. If you ever go Play, the entire Urbit push path moves into the `direct` flavor alongside `HttpUpdateChecker`.

That doesn't preclude building Stage C — it just means Stage C and Stage B have the same Play-compatibility constraint, and the flavor split (when it comes) covers both at once.
