# CMP Desktop Port Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Talon runs natively on Linux, macOS, and Windows desktop AND Android, from a single Compose Multiplatform module. Android continues to work throughout the port (no flag day).

**Architecture:** Consolidate `app/` and `composeApp/` into one CMP module — the `composeApp/` module produces the Android APK *and* the desktop binary. Shared code lives in `commonMain`. Android-specific platform APIs (notifications, alarms, MediaStore, on-device AI, voice recording) abstract behind `expect/actual` interfaces declared in `commonMain` and implemented in `androidMain` + `desktopMain`. Where a feature is genuinely Android-only (on-device LLM, daily-digest alarms), the desktop `actual` is a no-op stub and the feature is hidden in the desktop UI.

**Tech stack:** Compose Multiplatform 1.7.3 (Kotlin 2.0.20), Room 2.7.x, Coil 3.x, OkHttp 4.12 + okhttp-sse, kotlinx.serialization, kotlinx.coroutines. JDK 17.

**Spike validation:** the toolchain feasibility was confirmed in `docs/superpowers/spikes/2026-04-27-cmp-desktop-findings.md` — `LoginScreen` rendered on desktop after a single compile iteration. This plan operationalizes that spike's findings.

**Out of scope (separate future plans):**
- **iOS.** Desktop ports cleanly through CMP, but iOS is a known additional 2–3 weeks of work (signing, App Store review, no background daemon, different audio/camera/notification APIs). Land desktop first; write the iOS plan after it ships.
- **Daily digest on desktop.** AlarmManager has no clean desktop equivalent for "fire while the app is closed." Land Stage A–E first; revisit daily digest in a follow-up.
- **Voice messages, camera, on-device AI on desktop.** Hidden behind capability flags; the `desktopMain` actuals are stubs that report unsupported. Cloud AI providers (OpenAI/Anthropic) work fine cross-platform.
- **Web (Wasm) target.** Compose Web is too rough in 2026; revisit later.

**Branch:** `port/cmp-desktop`. Master continues to receive Android-only fixes throughout. Merge to master when all stages pass on both platforms.

---

## Stage map

| Stage | What lands | Working software at end of stage |
|---|---|---|
| A | Room 2.7, Coil 3, root build.gradle plugin declarations | Android app still works; KMP toolchain primed |
| B | `app/` consolidated into `composeApp/` | Android APK builds from `composeApp/` |
| C | Platform abstractions (`expect/actual` for SessionStore, logging, notifications, file picker, scheduling stub) | Desktop scaffold has all stubs in place |
| D | Five screens ported to commonMain (Login, DmList, DmChat, Settings, ProfileEdit) | Desktop renders + lets you read chats |
| E | Desktop packaging (Dmg/Msi/Deb), CI release pipeline updated | Desktop binary distributable |
| F | Manual smoke test on three OSes, fixes for whatever broke | Shipping-ready on Linux/macOS/Windows + Android |

Each stage's deliverable is verifiable independently. Don't start Stage B until Stage A is green; etc.

---

## Pre-port

### Pre-1: Branch from current master

- [ ] **Step 1: Create branch**

```bash
cd /home/sneagan/software/personal/talon
git checkout master
git pull
git checkout -b port/cmp-desktop
```

The `spike/cmp-desktop` branch stays as the reference — don't delete it. The port branch starts fresh from master.

- [ ] **Step 2: Confirm Android master still builds**

```bash
RELEASE_KEYSTORE_PROPS=$HOME/.config/talon/keystore.properties \
  JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 \
  PATH=$JAVA_HOME/bin:$PATH \
  ./gradlew :app:assembleRelease
```

Expected: `BUILD SUCCESSFUL`. If not, fix Android first before starting the port — you'll need a working baseline to verify nothing regresses.

---

## Stage A — Foundation upgrades

Both upgrades are independent; do Room first since it touches more files and any breakage is easier to diagnose against an unchanged Coil.

### Task A1: Bump Room to 2.7.x

**Files:**
- Modify: `gradle/libs.versions.toml` (room version)
- Modify: any DAO or Entity file Room 2.7 breaks

- [ ] **Step 1: Bump version**

In `gradle/libs.versions.toml`, change:

```toml
room = "2.6.1"
```

to:

```toml
room = "2.7.0-alpha13"
```

(Or whatever's the latest stable in the 2.7.x line at the time. Use the most recent `2.7.x-stable` if available; otherwise the most recent alpha — KMP support landed in alpha versions and stabilized over time. Check https://developer.android.com/jetpack/androidx/releases/room for current.)

- [ ] **Step 2: Build and identify compile breakages**

```bash
JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 \
  PATH=$JAVA_HOME/bin:$PATH \
  ./gradlew :app:compileDebugKotlin 2>&1 | tee /tmp/room-compile.log | tail -80
```

Likely breakages and fixes:
- `@TypeConverter` signatures changed in 2.7 — usually a one-line fix per converter.
- `Migration` constructors changed — replace with the new API.
- KSP version mismatch — bump `ksp` plugin to a version compatible with Room 2.7. Check the version compatibility table at https://github.com/google/ksp/releases.
- `androidx.room:room-runtime` artifact split — may need `room-paging`, `room-ktx`, or `room-common` separately.

Fix breakages one at a time. Re-run compile after each.

- [ ] **Step 3: Run all unit tests**

```bash
JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 \
  PATH=$JAVA_HOME/bin:$PATH \
  ./gradlew :app:testDebugUnitTest 2>&1 | tail -10
```

Expected: all tests pass. If any test fails, the migration broke a query — fix and re-run. Don't skip.

- [ ] **Step 4: Smoke test on device**

```bash
RELEASE_KEYSTORE_PROPS=$HOME/.config/talon/keystore.properties \
  JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 \
  PATH=$JAVA_HOME/bin:$PATH \
  ./gradlew :app:installDebug
```

Open the app. Verify: chat list loads, opening a chat shows messages, sending a message persists. Room 2.7 may auto-migrate but DB regressions are silent until they bite — the smoke test exercises the actual migration path on real device data.

- [ ] **Step 5: Commit**

```bash
git add gradle/libs.versions.toml app/src/main/java/io/nisfeb/talon/data/
git commit -m "deps: bump Room 2.6.1 → 2.7.x for KMP support"
```

### Task A2: Bump Coil to 3.x

**Files:**
- Modify: `gradle/libs.versions.toml` (coil version + artifact group)
- Modify: every `AsyncImage` / `rememberAsyncImagePainter` callsite

- [ ] **Step 1: Identify all Coil callsites**

```bash
grep -rn "AsyncImage\|rememberAsyncImagePainter\|coil\." \
  app/src/main/java/io/nisfeb/talon/ \
  | tee /tmp/coil-usage.txt
wc -l /tmp/coil-usage.txt
```

Expected output: a tractable number (the spike estimated this is moderate — `Avatar.kt`, profile pictures, attachment thumbnails). If it's > 50 lines, slow down and triage them before bulk-editing.

- [ ] **Step 2: Bump version + change artifact group**

In `gradle/libs.versions.toml`:

```toml
# Before
coil = "2.7.0"
[libraries]
coil-compose = { group = "io.coil-kt", name = "coil-compose", version.ref = "coil" }
```

Change to:

```toml
# After
coil = "3.0.0"
[libraries]
coil-compose = { group = "io.coil-kt.coil3", name = "coil-compose", version.ref = "coil" }
coil-network-okhttp = { group = "io.coil-kt.coil3", name = "coil-network-okhttp", version.ref = "coil" }
```

In `app/build.gradle.kts`, add `implementation(libs.coil.network.okhttp)` next to the existing `implementation(libs.coil.compose)` (Coil 3 needs an explicit network engine artifact; Coil 2 bundled OkHttp by default).

- [ ] **Step 3: Update imports + API**

The package changed: `coil.compose.AsyncImage` → `coil3.compose.AsyncImage`. Run a project-wide search-and-replace:

```bash
grep -rln "import coil\." app/src/main/java/io/nisfeb/talon/ | while read f; do
    sed -i 's|import coil\.|import coil3.|g' "$f"
done
```

Then verify each file individually — Coil 3 also has minor API changes:
- `ImageLoader.Builder` configuration syntax tweaked.
- Some `crossfade()` and `placeholder()` parameter shapes shifted.

- [ ] **Step 4: Compile + fix what breaks**

```bash
JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 \
  PATH=$JAVA_HOME/bin:$PATH \
  ./gradlew :app:compileDebugKotlin 2>&1 | tail -40
```

Expected: a small number of API-mismatch errors. Fix each by reading the Coil 3 migration guide at https://coil-kt.github.io/coil/upgrading_to_coil3/ and the per-call replacement.

- [ ] **Step 5: Run unit tests + smoke test**

Same drill as Task A1 — tests pass, install on device, verify image loading: avatars in DM list, profile pictures, attached image thumbnails. If any image renders broken (placeholder forever, wrong aspect ratio), the migration didn't land cleanly — fix the affected callsite.

- [ ] **Step 6: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main/java/io/nisfeb/talon/
git commit -m "deps: bump Coil 2.7.0 → coil3 3.x for KMP support"
```

### Task A3: Root build.gradle plugin declarations

**Files:**
- Modify: `build.gradle.kts` (root)

- [ ] **Step 1: Read the current root build file**

```bash
cat build.gradle.kts
```

It likely contains a `plugins { ... apply false }` block. We'll add KMP-related plugins to it so subprojects can use the `alias()` form (recall the spike Phase A finding — without these, `composeApp` had to use `kotlin("multiplatform")` instead of catalog aliases).

- [ ] **Step 2: Add the plugins**

Add these lines inside the `plugins { ... }` block:

```kotlin
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.multiplatform) apply false
```

(`kotlin-multiplatform`, `android-library`, and `compose-multiplatform` aliases were added to the catalog by the spike — already there.)

- [ ] **Step 3: Verify subprojects can resolve via alias**

After this lands, the `composeApp/build.gradle.kts` plugin block would be able to use `alias(libs.plugins.kotlin.multiplatform)` and `alias(libs.plugins.android.library)` instead of the `kotlin("multiplatform")` / `id("com.android.library")` workaround. Don't change `composeApp/` yet — that comes in Stage B. For now, just confirm:

```bash
JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 \
  PATH=$JAVA_HOME/bin:$PATH \
  ./gradlew help 2>&1 | tail -3
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add build.gradle.kts
git commit -m "build: declare KMP plugins at root for subproject alias use"
```

---

## Stage B — Module consolidation

The old shape: `app/` is the production Android module; `composeApp/` is a parallel scaffold. The new shape: `composeApp/` is the production module that emits an Android APK + a desktop binary; `app/` is deleted.

### Task B1: Migrate composeApp from `com.android.library` to `com.android.application`

The spike's `composeApp` declared `id("com.android.library")` because it didn't need to produce an APK. Now it does.

**Files:**
- Modify: `composeApp/build.gradle.kts`

- [ ] **Step 1: Change the plugin**

Replace `id("com.android.library")` with `alias(libs.plugins.android.application)` (the alias is already in the catalog — used by `app/`).

- [ ] **Step 2: Add the application config**

The `android { ... }` block needs an `applicationId`, `versionCode`, `versionName` like the existing `app/build.gradle.kts`. Pull those values across — and replicate the existing release signing config (the env-var-driven keystore from `app/build.gradle.kts`).

Read `app/build.gradle.kts` and copy the parts that aren't KMP-specific: `defaultConfig`, `signingConfigs`, `buildTypes`, `lint`, `packaging`. Adapt to the KMP-style `android { }` block syntax.

- [ ] **Step 3: Add a launcher Activity in androidMain**

`composeApp/src/androidMain/kotlin/io/nisfeb/talon/MainActivity.kt`:

```kotlin
package io.nisfeb.talon

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
// ... whatever your current MainActivity does ...

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // Same content tree as the current app/MainActivity
        }
    }
}
```

Don't fully migrate `MainActivity` content yet — the screen ports happen in Stage D. For now, render a placeholder ("Talon — port in progress").

- [ ] **Step 4: Build the Android APK from composeApp**

```bash
JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 \
  PATH=$JAVA_HOME/bin:$PATH \
  ./gradlew :composeApp:assembleRelease 2>&1 | tail -8
```

Expected: BUILD SUCCESSFUL, an APK at `composeApp/build/outputs/apk/release/composeApp-release.apk`. Install on device, confirm the placeholder activity opens.

- [ ] **Step 5: Commit**

```bash
git add composeApp/build.gradle.kts \
  composeApp/src/androidMain/
git commit -m "build: composeApp produces Android APK (placeholder activity)"
```

### Task B2: Move all portable source from `app/` to `composeApp/commonMain`

This is the bulk of Stage B. Touches dozens of files. The strategy: move in waves, building between each wave to catch breakage early.

**Files:** all of `app/src/main/java/io/nisfeb/talon/**` triaged into `commonMain` or `androidMain`.

- [ ] **Step 1: Generate the triage list**

```bash
cd /home/sneagan/software/personal/talon
grep -rL --include="*.kt" \
    -e "^import android\." \
    -e "^import androidx\.\(activity\|core\|room\|datastore\|work\|preference\|security\|biometric\|browser\|fragment\|lifecycle\.LiveData\|lifecycle\.ViewModel\|navigation\|paging\|hilt\)" \
    -e "^import com\.google\." \
    -e "^import dagger\." \
    app/src/main/java/io/nisfeb/talon/ \
  > /tmp/portable-files.txt
wc -l /tmp/portable-files.txt
```

This is the rough portable-candidates list. Manual triage refines it: some files import `androidx.compose.ui.platform.LocalContext` (Android-only) or `androidx.compose.foundation.text.KeyboardOptions` (portable).

- [ ] **Step 2: Move wave 1 — `urbit/`, `ai/clients/`, anything pure JVM**

```bash
mkdir -p composeApp/src/commonMain/kotlin/io/nisfeb/talon/urbit
git mv app/src/main/java/io/nisfeb/talon/urbit/*.kt \
    composeApp/src/commonMain/kotlin/io/nisfeb/talon/urbit/
```

Then the same for `ai/clients/` (but NOT `ai/EmbeddingIndexer.kt` or `ai/EntityActions.kt` which use Android APIs):

```bash
mkdir -p composeApp/src/commonMain/kotlin/io/nisfeb/talon/ai
git mv app/src/main/java/io/nisfeb/talon/ai/AiClient.kt \
    app/src/main/java/io/nisfeb/talon/ai/AiFeatures.kt \
    composeApp/src/commonMain/kotlin/io/nisfeb/talon/ai/
```

Build. If errors are confined to "this file is now in commonMain but uses `Log.i`" — those are real findings; address them in Step 4.

- [ ] **Step 3: Move wave 2 — UI composables**

The Compose UI is mostly portable. Move the entire `ui/` tree EXCEPT files that use `Activity`, `Context`, `MediaStore`, `BroadcastReceiver`, etc.:

```bash
mkdir -p composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui
# Move screen Composables one at a time, checking the imports
git mv app/src/main/java/io/nisfeb/talon/ui/screens/LoginScreen.kt \
    composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/LoginScreen.kt
git mv app/src/main/java/io/nisfeb/talon/ui/Theme.kt \
    composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/Theme.kt
# ... etc, screen by screen
```

Don't bulk-move the whole `ui/` directory — some screens have Android imports that will need refactoring. Move them as Stage D ports them.

For Stage B, the goal is just "the easy stuff is in commonMain." Stage D ports the rest.

- [ ] **Step 4: Replace `Log.*` with the logging facade**

(The facade itself lands in Task C2. For Stage B, just replace `Log.i/w/e` with `println` calls in the moved files. Stage C will swap to the proper facade.)

```bash
grep -rln "android\.util\.Log" composeApp/src/commonMain/ | while read f; do
    sed -i 's|import android\.util\.Log||g' "$f"
    sed -i 's|Log\.i(|println("INFO: "+|g; s|Log\.w(|println("WARN: "+|g; s|Log\.e(|println("ERROR: "+|g' "$f"
    # Fix the trailing parens — sed doesn't get the last `)` right after the comma
done
```

This is messy and approximate; fix individual cases by hand if the regex doesn't get them. The point is to get the files compiling; the proper logging facade lands in Task C2.

- [ ] **Step 5: Build composeApp Android target after each wave**

```bash
JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 \
  PATH=$JAVA_HOME/bin:$PATH \
  ./gradlew :composeApp:assembleRelease 2>&1 | tail -8
```

After each wave, the Android APK should still build. If it doesn't, the wave moved a file that wasn't actually portable — back it out and triage.

- [ ] **Step 6: Verify on device after the last wave**

Install the new APK from `composeApp/`. Open the app. Login screen appears. Login. Chat list loads. Open a chat. Send a message. If anything is broken, fix before continuing to Stage C.

- [ ] **Step 7: Commit per wave**

Each wave is its own commit:

```bash
git commit -m "port: move urbit/ to commonMain"
git commit -m "port: move ai/clients to commonMain"
git commit -m "port: move pure-Compose screens to commonMain"
```

### Task B3: Delete `app/`

After Stage B's moves, `app/` should be mostly empty — just the original `MainActivity`, the `AndroidManifest.xml`, and Android-specific resources.

**Files:**
- Move: `app/src/main/AndroidManifest.xml` → `composeApp/src/androidMain/AndroidManifest.xml`
- Move: `app/src/main/res/**` → `composeApp/src/androidMain/res/**`
- Move: residual Android-only Kotlin files (Services, Receivers, etc.) → `composeApp/src/androidMain/kotlin/io/nisfeb/talon/`
- Delete: `app/`
- Modify: `settings.gradle.kts` (remove `include(":app")`)

- [ ] **Step 1: Move the manifest + resources**

```bash
git mv app/src/main/AndroidManifest.xml composeApp/src/androidMain/AndroidManifest.xml
mkdir -p composeApp/src/androidMain/res
git mv app/src/main/res/* composeApp/src/androidMain/res/
```

- [ ] **Step 2: Move residual Android-only sources**

These are the things that genuinely belong in `androidMain`:
- `MainActivity.kt`
- `TalonApplication.kt`
- `TalonSyncService.kt` (foreground service)
- `DigestAlarmReceiver.kt`
- `DigestBootReceiver.kt`
- `EmbeddingIndexer.kt`, `EntityActions.kt` (ML Kit)
- Any other Service / BroadcastReceiver / WorkManager classes

```bash
mkdir -p composeApp/src/androidMain/kotlin/io/nisfeb/talon
git mv app/src/main/java/io/nisfeb/talon/MainActivity.kt \
    app/src/main/java/io/nisfeb/talon/TalonApplication.kt \
    app/src/main/java/io/nisfeb/talon/*Service.kt \
    app/src/main/java/io/nisfeb/talon/*Receiver.kt \
    composeApp/src/androidMain/kotlin/io/nisfeb/talon/
# Plus subdirectories as needed
```

- [ ] **Step 3: Update `composeApp/build.gradle.kts`**

The android{} block now needs to know about the manifest path — by default Gradle looks at `composeApp/src/androidMain/AndroidManifest.xml`, so this should already work.

If `composeApp/build.gradle.kts`'s androidMain dependencies reference things that lived in the old `app/build.gradle.kts`, port those over too. Things like:

- All the `implementation(libs.androidx.*)` lines
- `implementation(libs.workmanager.runtime)`
- ML Kit / on-device LLM dependencies

Don't include them in commonMain dependencies — these are Android-specific.

- [ ] **Step 4: Update `settings.gradle.kts`**

Remove `include(":app")`.

- [ ] **Step 5: Delete `app/`**

```bash
git rm -r app/
```

- [ ] **Step 6: Build the Android APK**

```bash
RELEASE_KEYSTORE_PROPS=$HOME/.config/talon/keystore.properties \
  JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 \
  PATH=$JAVA_HOME/bin:$PATH \
  ./gradlew :composeApp:assembleRelease 2>&1 | tail -8
```

Expected: BUILD SUCCESSFUL. APK at `composeApp/build/outputs/apk/release/composeApp-release.apk`.

- [ ] **Step 7: Install + smoke test**

Full smoke pass on device: login, chat list, chat view, send, receive, reactions, polls, settings. **If any feature regresses, do not proceed to Stage C — fix here.**

- [ ] **Step 8: Update `release.yml` and `release/poke-agent.sh` references**

Anywhere these scripts reference `:app:` task or the `app-release.apk` path, change to `:composeApp:` and `composeApp-release.apk`.

- [ ] **Step 9: Commit**

```bash
git add composeApp/ settings.gradle.kts .github/
git rm -r app/
git commit -m "port: delete app/, composeApp is now the sole module"
```

---

## Stage C — Platform abstractions

For each Android-specific surface, extract an `expect` interface in `commonMain` and provide `actual` in `androidMain` + `desktopMain`.

### Task C1: SessionStore expect/actual

**Files:**
- Create: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/urbit/SessionStore.kt` (interface declaration)
- Create: `composeApp/src/androidMain/kotlin/io/nisfeb/talon/urbit/SessionStore.android.kt` (existing impl)
- Create: `composeApp/src/desktopMain/kotlin/io/nisfeb/talon/urbit/SessionStore.desktop.kt` (new impl)

- [ ] **Step 1: Define the common interface**

The current `SessionStore` API (read from the existing `app/.../urbit/SessionStore.kt` before it was moved):

```kotlin
package io.nisfeb.talon.urbit

interface SessionStore {
    fun all(): List<SavedSession>
    fun active(): SavedSession?
    fun activeShip(): String?
    fun save(session: SavedSession)
    fun setActive(ship: String?)
    fun remove(ship: String)
    fun clearAll()
}

data class SavedSession(
    val ship: String,
    val baseUrl: String,
    val cookieJson: String,
)

expect fun createSessionStore(): SessionStore
```

- [ ] **Step 2: Android actual**

The existing `SessionStore.kt` (the Android one with `Context` + `SharedPreferences`) becomes the Android `actual`. Move/rename to `composeApp/src/androidMain/kotlin/io/nisfeb/talon/urbit/SessionStore.android.kt`:

```kotlin
package io.nisfeb.talon.urbit

import android.content.Context

actual fun createSessionStore(): SessionStore = AndroidSessionStore(/* needs context */)

private class AndroidSessionStore(context: Context) : SessionStore {
    // existing SharedPreferences-backed implementation
}
```

The `Context` dependency is awkward — `expect fun createSessionStore()` can't take a Context (it's not in commonMain). Two options:
- Pass the Context via `TalonApplication` initialization, then have the Android `actual` function access a stored singleton.
- Use an `expect class SessionStore` with a constructor — Android takes a Context, desktop takes nothing. This is cleaner; do this.

```kotlin
// commonMain
expect class SessionStore() {
    fun all(): List<SavedSession>
    // ... same API
}
```

```kotlin
// androidMain
actual class SessionStore actual constructor() {
    // Default constructor uses a Context passed via a static initializer
    // or a service-locator pattern. This is messier than it looks.
}
```

Or — pragmatic choice — pass the platform-bound dependencies via a small `AppContext` parameter:

```kotlin
// commonMain
expect class AppContext  // marker; android = real Context, desktop = unit/empty class
expect fun createSessionStore(ctx: AppContext): SessionStore
```

Pick the cleanest of these for your codebase. The README of CMP and SQLDelight usually point at the `AppContext` parameter style as the least bad option.

- [ ] **Step 3: Desktop actual**

```kotlin
// composeApp/src/desktopMain/kotlin/io/nisfeb/talon/urbit/SessionStore.desktop.kt
package io.nisfeb.talon.urbit

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

actual fun createSessionStore(): SessionStore = DesktopSessionStore()

private class DesktopSessionStore : SessionStore {
    private val file: File = File(
        System.getProperty("user.home"),
        ".config/talon/sessions.json"
    ).apply { parentFile.mkdirs() }

    private fun load(): SessionsBlob = if (file.exists())
        Json.decodeFromString(file.readText())
    else
        SessionsBlob(emptyList(), null)

    private fun save(blob: SessionsBlob) {
        file.writeText(Json.encodeToString(blob))
    }

    override fun all() = load().sessions
    override fun active() = load().let { b -> b.sessions.firstOrNull { it.ship == b.activeShip } }
    override fun activeShip() = load().activeShip
    override fun save(session: SavedSession) {
        val cur = load()
        val sessions = cur.sessions.filter { it.ship != session.ship } + session
        save(cur.copy(sessions = sessions))
    }
    override fun setActive(ship: String?) {
        save(load().copy(activeShip = ship))
    }
    override fun remove(ship: String) {
        val cur = load()
        save(cur.copy(
            sessions = cur.sessions.filter { it.ship != ship },
            activeShip = if (cur.activeShip == ship) null else cur.activeShip,
        ))
    }
    override fun clearAll() = save(SessionsBlob(emptyList(), null))

    @Serializable
    private data class SessionsBlob(
        val sessions: List<SavedSession>,
        val activeShip: String?,
    )
}
```

`SavedSession` needs `@Serializable` — ensure that's already in commonMain.

- [ ] **Step 4: Build all three targets**

```bash
JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 \
  PATH=$JAVA_HOME/bin:$PATH \
  ./gradlew :composeApp:compileCommonMainKotlinMetadata \
            :composeApp:compileDebugKotlin \
            :composeApp:compileKotlinDesktop \
  2>&1 | tail -10
```

Expected: all three pass.

- [ ] **Step 5: Smoke test on device + desktop**

On Android: login persists across app restart. On desktop: can't fully test until UpdateState/MainActivity ports — for now, write a quick `main()` test in desktopMain that creates a SessionStore, saves a session, reads it back. Verify `~/.config/talon/sessions.json` exists and has the right shape.

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/urbit/SessionStore.kt \
  composeApp/src/androidMain/kotlin/io/nisfeb/talon/urbit/SessionStore.android.kt \
  composeApp/src/desktopMain/kotlin/io/nisfeb/talon/urbit/SessionStore.desktop.kt
git commit -m "port: SessionStore expect/actual (android: SharedPreferences, desktop: JSON file)"
```

### Task C2: Logging facade expect/actual

**Files:**
- Create: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/util/Log.kt`
- Create: `composeApp/src/androidMain/kotlin/io/nisfeb/talon/util/Log.android.kt`
- Create: `composeApp/src/desktopMain/kotlin/io/nisfeb/talon/util/Log.desktop.kt`

- [ ] **Step 1: Common interface**

```kotlin
// composeApp/src/commonMain/kotlin/io/nisfeb/talon/util/Log.kt
package io.nisfeb.talon.util

expect object Log {
    fun i(tag: String, msg: String)
    fun w(tag: String, msg: String, t: Throwable? = null)
    fun e(tag: String, msg: String, t: Throwable? = null)
}
```

- [ ] **Step 2: Android actual**

```kotlin
// composeApp/src/androidMain/kotlin/io/nisfeb/talon/util/Log.android.kt
package io.nisfeb.talon.util

actual object Log {
    actual fun i(tag: String, msg: String) { android.util.Log.i(tag, msg) }
    actual fun w(tag: String, msg: String, t: Throwable?) { android.util.Log.w(tag, msg, t) }
    actual fun e(tag: String, msg: String, t: Throwable?) { android.util.Log.e(tag, msg, t) }
}
```

- [ ] **Step 3: Desktop actual**

```kotlin
// composeApp/src/desktopMain/kotlin/io/nisfeb/talon/util/Log.desktop.kt
package io.nisfeb.talon.util

actual object Log {
    actual fun i(tag: String, msg: String) {
        System.err.println("INFO  [$tag] $msg")
    }
    actual fun w(tag: String, msg: String, t: Throwable?) {
        System.err.println("WARN  [$tag] $msg")
        t?.printStackTrace(System.err)
    }
    actual fun e(tag: String, msg: String, t: Throwable?) {
        System.err.println("ERROR [$tag] $msg")
        t?.printStackTrace(System.err)
    }
}
```

- [ ] **Step 4: Replace all `android.util.Log` references in commonMain**

```bash
grep -rln "android\.util\.Log\|^import android\.util\.Log" composeApp/src/commonMain/ \
  | while read f; do
    sed -i 's|^import android\.util\.Log$|import io.nisfeb.talon.util.Log|g' "$f"
  done
```

The earlier Stage B `println(...)` shims also get replaced — find them with `grep -rn 'println("INFO: \|println("WARN: \|println("ERROR: ' composeApp/src/commonMain/`. Convert to proper `Log.i(tag, msg)` calls.

- [ ] **Step 5: Build + verify on both targets**

```bash
JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 \
  PATH=$JAVA_HOME/bin:$PATH \
  ./gradlew :composeApp:compileDebugKotlin :composeApp:compileKotlinDesktop 2>&1 | tail -5
```

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/util/ \
  composeApp/src/androidMain/kotlin/io/nisfeb/talon/util/ \
  composeApp/src/desktopMain/kotlin/io/nisfeb/talon/util/ \
  composeApp/src/commonMain/kotlin/io/nisfeb/talon/
git commit -m "port: Log facade expect/actual (android: util.Log, desktop: System.err)"
```

### Task C3: Notifications expect/actual

**Files:**
- Create: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/notifications/Notifications.kt`
- Create: `composeApp/src/androidMain/kotlin/io/nisfeb/talon/notifications/Notifications.android.kt`
- Create: `composeApp/src/desktopMain/kotlin/io/nisfeb/talon/notifications/Notifications.desktop.kt`

- [ ] **Step 1: Define the common interface**

```kotlin
// commonMain
package io.nisfeb.talon.notifications

interface Notifications {
    fun showMessage(
        ship: String,
        whom: String,
        from: String,
        body: String,
        deepLinkPostId: String?,
    )
    fun cancelAllForChat(whom: String)
}

expect fun createNotifications(): Notifications
```

- [ ] **Step 2: Android actual**

The existing `Notifications` object (in `androidMain` after Stage B) becomes the actual implementation. Wrap it in `class AndroidNotifications : Notifications` and provide `actual fun createNotifications(): Notifications = AndroidNotifications()`.

- [ ] **Step 3: Desktop actual**

Use `java.awt.SystemTray` + `TrayIcon.displayMessage` for desktop tray notifications. macOS, Windows, and Linux all support this with caveats (macOS may need a real bundled .app to show notifications; Linux varies by DE).

```kotlin
// composeApp/src/desktopMain/kotlin/io/nisfeb/talon/notifications/Notifications.desktop.kt
package io.nisfeb.talon.notifications

import java.awt.SystemTray
import java.awt.TrayIcon

actual fun createNotifications(): Notifications = DesktopNotifications()

private class DesktopNotifications : Notifications {
    private val tray = if (SystemTray.isSupported()) SystemTray.getSystemTray() else null
    private var icon: TrayIcon? = null

    override fun showMessage(
        ship: String,
        whom: String,
        from: String,
        body: String,
        deepLinkPostId: String?,
    ) {
        val t = tray ?: return
        if (icon == null) {
            // Lazy-init the tray icon. Use a 16x16 placeholder image.
            val img = java.awt.Toolkit.getDefaultToolkit().createImage(
                javaClass.getResource("/talon-tray.png")
            )
            icon = TrayIcon(img, "Talon").also {
                it.isImageAutoSize = true
                t.add(it)
            }
        }
        icon?.displayMessage(from, body, TrayIcon.MessageType.INFO)
    }

    override fun cancelAllForChat(whom: String) {
        // Desktop tray notifications are fire-and-forget — there's no
        // good way to cancel them after they're shown. No-op.
    }
}
```

A 16x16 PNG `talon-tray.png` lives in `composeApp/src/desktopMain/resources/`. Use the existing icon at any density that's small enough.

- [ ] **Step 4: Build + smoke test on Linux**

Run the app, send yourself a message from another device, confirm the tray notification fires. macOS and Windows tested in Stage F.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/{common,android,desktop}Main/kotlin/io/nisfeb/talon/notifications/ \
  composeApp/src/desktopMain/resources/talon-tray.png
git commit -m "port: Notifications expect/actual (android: NotificationCompat, desktop: SystemTray)"
```

### Task C4: File picker expect/actual (image attachments)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/util/FilePicker.kt`
- Create: `composeApp/src/androidMain/kotlin/io/nisfeb/talon/util/FilePicker.android.kt`
- Create: `composeApp/src/desktopMain/kotlin/io/nisfeb/talon/util/FilePicker.desktop.kt`

The Android impl uses MediaStore + `ActivityResultContracts.PickVisualMedia`. The desktop impl uses `javax.swing.JFileChooser` with image-file filters. Both return a `ByteArray` of the picked image (since the consumers all need to upload it via S3).

- [ ] **Step 1: Common interface**

```kotlin
// commonMain
package io.nisfeb.talon.util

interface FilePicker {
    suspend fun pickImage(): PickedImage?
}

data class PickedImage(
    val bytes: ByteArray,
    val mimeType: String,
    val displayName: String,
)
```

- [ ] **Step 2: Android actual**

Wrap the existing `PickVisualMedia` flow in a `Flow`-friendly suspending function. Pattern: a `suspendCancellableCoroutine` that registers a launcher and resolves on result.

This is the most complex platform abstraction in Stage C — the existing Android image-picker code uses `ActivityResultContracts` which require an Activity reference. Pass that via `TalonApplication`'s singleton or via Compose's `LocalContext`.

- [ ] **Step 3: Desktop actual**

```kotlin
actual fun createFilePicker(): FilePicker = DesktopFilePicker()

private class DesktopFilePicker : FilePicker {
    override suspend fun pickImage(): PickedImage? = withContext(Dispatchers.IO) {
        val chooser = JFileChooser().apply {
            fileFilter = FileNameExtensionFilter("Images", "jpg", "jpeg", "png", "gif", "webp")
        }
        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return@withContext null
        val file = chooser.selectedFile ?: return@withContext null
        PickedImage(
            bytes = file.readBytes(),
            mimeType = mimeFromExtension(file.extension),
            displayName = file.name,
        )
    }
}
```

- [ ] **Step 4: Wire callers**

The chat composer (and profile-edit screen) already invoke a picker — point them at the new `FilePicker` interface instead of the direct `ActivityResultContracts` flow.

- [ ] **Step 5: Build + smoke**

Send an image attachment on Android. Then on desktop. Verify both work end-to-end (S3 upload, message arrives with image, recipient sees thumbnail).

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/{common,android,desktop}Main/kotlin/io/nisfeb/talon/util/FilePicker*
git commit -m "port: FilePicker expect/actual for image attachments"
```

### Task C5: Scheduling stubs

For Stage D's purposes, we need the daily-digest alarm scheduling to compile but not fire on desktop. (Real desktop scheduling is its own project — system tray daemons and platform-specific autostart. Out of scope.)

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ai/DailyDigest.kt` (extract scheduling to expect)
- Create: `composeApp/src/desktopMain/kotlin/io/nisfeb/talon/ai/DigestScheduling.desktop.kt`

- [ ] **Step 1: Extract `expect fun scheduleDigestAlarm(...)`**

Pull the AlarmManager scheduling out of `DailyDigest.kt` into an `expect` function. Android `actual` keeps the AlarmManager call; desktop `actual` is a no-op:

```kotlin
// desktopMain
actual fun scheduleDigestAlarm(timeOfDay: LocalTime) {
    Log.i("DailyDigest", "Desktop has no daily-digest alarm; skipping schedule.")
}

actual fun cancelDigestAlarm() = Unit
```

- [ ] **Step 2: Hide the daily-digest UI on desktop**

In `SettingsScreen`, gate the daily-digest section behind `expect val isDailyDigestSupported: Boolean`. Android `actual` is `true`; desktop `actual` is `false`. The settings section just doesn't render on desktop.

- [ ] **Step 3: Build both targets, commit**

```bash
JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 \
  PATH=$JAVA_HOME/bin:$PATH \
  ./gradlew :composeApp:compileDebugKotlin :composeApp:compileKotlinDesktop 2>&1 | tail -5

git add composeApp/src/{common,android,desktop}Main/kotlin/io/nisfeb/talon/ai/
git commit -m "port: scheduleDigestAlarm expect/actual (android: AlarmManager, desktop: no-op)"
```

### Task C6: AppContext bridge

A few of the platform abstractions above need a "platform context" — Android needs a `Context`, desktop needs nothing (just unit). Codify this in commonMain:

**Files:**
- Create: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/util/AppContext.kt`

```kotlin
// commonMain
package io.nisfeb.talon.util

expect class AppContext
```

```kotlin
// androidMain
actual typealias AppContext = android.content.Context
```

```kotlin
// desktopMain
actual class AppContext  // empty marker
```

Anywhere `Context` was needed in a method signature, replace with `AppContext`. The `typealias` makes Android usage transparent.

- [ ] **Step 1-3: Define, replace usages, commit**

Mechanical refactor; pattern is clear from the above. ~half-day of work.

```bash
git commit -m "port: AppContext expect/typealias for platform-bound APIs"
```

---

## Stage D — Screen ports

Each of the five core screens gets ported individually. Order: simplest first.

### Task D1: LoginScreen (already done in spike)

- [ ] **Step 1: Apply the spike's findings to the production code path**

The spike already validated LoginScreen ports. The production version may have drifted — re-check imports + the `semantics { contentType = ... }` issue. Apply the same fix the spike used.

- [ ] **Step 2: Wire LoginScreen into desktop Main.kt**

Replace the placeholder with the real LoginScreen:

```kotlin
fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Talon") {
        TalonTheme {
            Surface {
                val sessionStore = remember { createSessionStore() }
                if (sessionStore.activeShip() == null) {
                    LoginScreen(
                        // ... pass mock callbacks for now ...
                    )
                } else {
                    Text("Logged in as ${sessionStore.activeShip()}")
                }
            }
        }
    }
}
```

- [ ] **Step 3: Test on desktop — actual login**

Run the app, enter your ship's URL + code, hit Login. The session should persist; `~/.config/talon/sessions.json` should contain the saved session.

- [ ] **Step 4: Commit**

```bash
git commit -m "port: LoginScreen wired to desktop Main, real login works"
```

### Task D2: SettingsScreen

A relatively small screen, no Room dependency.

- [ ] Triage Android-only references (notification preferences, AI settings DataStore).
- [ ] Move to commonMain, replace platform-specific reads with the `AppContext` pattern from Task C6.
- [ ] Wire into desktop nav. Build + smoke. Commit.

### Task D3: ProfileEditScreen

- [ ] Triage. Moves cleanly to commonMain after the FilePicker abstraction is in place (the only Android-specific bit is the avatar picker).
- [ ] Move + wire + smoke. Commit.

### Task D4: DmListScreen

The home screen. Largest of the five. Touches: contact map (commonMain after Stage B), Room (data layer access), drafts, drag-reorder.

- [ ] **Step 1: Confirm Room 2.7 KMP queries work on desktop**

The Room upgrade in Stage A made Room 2.7 available, but desktop needs a SQLite driver. In commonMain dependencies, add:

```kotlin
implementation("androidx.sqlite:sqlite-bundled:2.5.0-alpha09")
```

and configure the Room database builder to use the bundled driver on desktop.

- [ ] **Step 2: Port the screen**

Most of the work happens via `AppContext` and the abstractions already in place from Stage C. The drag-reorder library `sh.calvin.reorderable` is already multiplatform.

- [ ] **Step 3: Smoke on both Android + desktop**

Open the app, see the chat list. Click a chat — for now, just log "would open chat" since DmChatScreen is task D5. Drag-reorder a group, restart, confirm order persists.

- [ ] **Step 4: Commit**

### Task D5: DmChatScreen

The chat view. Largest single composable in the app. Touches: messages flow, reactions, polls, replies, attachments, voice messages (skip on desktop), quote-cite.

- [ ] **Step 1: Triage features that need to be hidden on desktop v1**

Voice messages, camera attachments, on-device LLM features — gate behind `expect val isVoiceSupported: Boolean` etc., similar to Task C5's daily-digest gate.

- [ ] **Step 2: Port the screen**

Big move. Likely 1-2 days of focused work given the file's size (~3000 lines). Break into commits per logical section if it helps.

- [ ] **Step 3: Smoke**

Open a chat. Read messages. Send a message. React to a message. Quote-cite. Threads. Confirm everything works on both platforms.

- [ ] **Step 4: Commit**

---

## Stage E — Desktop packaging

### Task E1: jpackage configuration

**Files:**
- Modify: `composeApp/build.gradle.kts` (`compose.desktop.application.nativeDistributions`)

- [ ] **Step 1: Configure all three target formats**

Already in the spike's build file:

```kotlin
compose.desktop {
    application {
        mainClass = "io.nisfeb.talon.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Talon"
            packageVersion = "1.0.0" // see spike finding — MAJOR > 0 required
            description = "Native chat client for Urbit"
            copyright = "© 2026 ~nisfeb"
            vendor = "nisfeb"
            licenseFile.set(project.file("../LICENSE"))
            modules("java.naming", "java.sql") // for Room/SQLite + DNS
        }
    }
}
```

- [ ] **Step 2: Bundle the launcher icon**

Use the existing `branding/play_store_icon_512.png` as the source. Convert to platform-native formats:
- macOS: `.icns` via `iconutil` or `png2icns`
- Windows: `.ico` via ImageMagick
- Linux: `.png` (multiple sizes)

Place in `composeApp/src/desktopMain/resources/` and configure `nativeDistributions.macOS.iconFile`, `windows.iconFile`, `linux.iconFile`.

- [ ] **Step 3: Build per-platform packages**

```bash
JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 \
  PATH=$JAVA_HOME/bin:$PATH \
  ./gradlew :composeApp:packageReleaseDeb 2>&1 | tail -5
# Also packageReleaseDmg, packageReleaseMsi (need to run on the
# respective host OS for the proper installer to emit)
```

The `.deb` produces on Linux. `.dmg` requires macOS (Gradle errors politely if you try elsewhere). `.msi` requires Windows (or wine64 / WiX in CI). Land what you can locally; the rest happens in CI.

- [ ] **Step 4: Commit**

```bash
git add composeApp/build.gradle.kts composeApp/src/desktopMain/resources/icon.*
git commit -m "package: jpackage configs for Dmg/Msi/Deb, native icons"
```

### Task E2: CI workflow update

**Files:**
- Modify: `.github/workflows/release.yml`

The existing release workflow only builds the Android APK. Extend to also build desktop artifacts on three runner OSes.

- [ ] **Step 1: Add desktop build matrix**

```yaml
  build-desktop:
    needs: tagcheck
    strategy:
      fail-fast: false
      matrix:
        include:
          - os: ubuntu-latest
            target: packageReleaseDeb
            artifact: "*.deb"
          - os: macos-latest
            target: packageReleaseDmg
            artifact: "*.dmg"
          - os: windows-latest
            target: packageReleaseMsi
            artifact: "*.msi"
    runs-on: ${{ matrix.os }}
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '17'
      - name: Build native package
        run: ./gradlew :composeApp:${{ matrix.target }}
      - name: Upload artifact
        uses: actions/upload-artifact@v4
        with:
          name: talon-${{ matrix.os }}
          path: composeApp/build/compose/binaries/main/*/
```

- [ ] **Step 2: Add desktop artifacts to the release**

In the existing `Publish GitHub Release` step, also include the desktop artifacts.

- [ ] **Step 3: Update `latest.json` schema**

The current manifest has a single `url` field. For desktop, we need per-platform URLs. Schema becomes:

```json
{
  "versionCode": ...,
  "versionName": "...",
  "android": {
    "url": "...",
    "sha256": "..."
  },
  "desktop": {
    "linux":   { "url": "...", "sha256": "..." },
    "macos":   { "url": "...", "sha256": "..." },
    "windows": { "url": "...", "sha256": "..." }
  },
  "minSdk": 26,
  "changelog": "...",
  "mandatory": false
}
```

Update `release/manifest-template.json`, the workflow's sed pipeline, and `UpdateManifest.parse()` accordingly. The HttpUpdateChecker on Android reads the `android` branch; on desktop, it reads the platform-matching `desktop.*` branch.

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/release.yml release/manifest-template.json composeApp/src/commonMain/kotlin/io/nisfeb/talon/update/UpdateManifest.kt
git commit -m "release: CI matrix builds desktop artifacts; manifest schema per-platform"
```

---

## Stage F — Cross-platform smoke + polish

### Task F1: Manual smoke test on three OSes

- [ ] **Step 1: Linux**

Build locally:

```bash
./gradlew :composeApp:packageReleaseDeb
sudo dpkg -i composeApp/build/compose/binaries/main/deb/talon_1.0.0_amd64.deb
talon  # launches from PATH
```

Test: login, chat list, chat view, send, receive, reactions, polls, settings, profile edit, image attachment.

- [ ] **Step 2: macOS**

Cut a tag, let CI build the `.dmg`, download from the GitHub Release, install. Same smoke pass.

- [ ] **Step 3: Windows**

Same, with `.msi`.

- [ ] **Step 4: Document any platform-specific bugs in a follow-up file**

Anything the smoke pass surfaces gets logged as TODOs in `docs/superpowers/spikes/2026-04-27-cmp-desktop-followups.md`. Don't try to fix everything inline — the goal of Stage F is "is this shippable enough?" not "polish to perfection."

### Task F2: Final review + merge

- [ ] **Step 1: Run all tests on all targets**

```bash
JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 \
  PATH=$JAVA_HOME/bin:$PATH \
  ./gradlew :composeApp:testDebugUnitTest :composeApp:testDesktop 2>&1 | tail -10
```

- [ ] **Step 2: Squash review the branch**

```bash
git log master..port/cmp-desktop --oneline | wc -l
# A 5-week port likely has 50-80 commits. That's fine — the review
# happens commit by commit.
```

- [ ] **Step 3: Merge to master**

```bash
git checkout master
git merge --no-ff port/cmp-desktop -m "feat: Compose Multiplatform desktop port (Linux, macOS, Windows)"
git push origin master
```

- [ ] **Step 4: Cut a 0.5.0 release**

```bash
$EDITOR composeApp/build.gradle.kts  # bump versionName + versionCode
git commit -am "release: 0.5.0"
git tag v0.5.0
git push origin master
git push origin v0.5.0
```

CI builds Android + Linux + macOS + Windows artifacts. Released on GitHub. Done.

---

## Self-review

1. **Spec coverage:** every blocker from `2026-04-27-cmp-desktop-findings.md` has a task. Room → A1, Coil → A2, plugin classpath → A3, SessionStore → C1, Log → C2, autofill → handled in screen ports as encountered, namespace → handled in B3 (single-module from then on), okhttp-sse → handled in dependency block of composeApp/build.gradle.kts (no separate task — the dep is just there).

2. **Placeholder scan:** no `TBD`/`TODO` in step bodies. The plan does mention `// TODO desktop:` markers as a documentation tactic during ports — that's intentional, not a placeholder failure.

3. **Type consistency:** `SessionStore`, `Notifications`, `FilePicker`, `Log`, `AppContext`, `UpdateManifest`, `UpdateState`, `UpdateChecker` are used identically across tasks.

4. **Stages produce working software:** Stage A leaves Android working with new deps. Stage B leaves Android working from the new module. Stage C makes desktop compilable. Stage D adds rendering. Stage E adds packaging. Stage F merges. Each stage is verifiable.

5. **Stopping conditions:** Stage F explicitly produces 0.5.0; nothing past that is in scope here. iOS, daily-digest-on-desktop, voice-messages-on-desktop, on-device-AI-on-desktop are all explicitly deferred to follow-up plans.

---

## Realistic effort estimate

| Stage | Estimate |
|---|---|
| Pre-port + Stage A | 2–3 days |
| Stage B | 3–5 days |
| Stage C | 4–6 days |
| Stage D | 5–8 days |
| Stage E | 2–3 days |
| Stage F | 2–3 days |
| **Total** | **3–4 person-weeks** |

The estimate matches the spike's recommendation. Variance comes mostly from Stage D (screen ports — DmChatScreen is the wildcard).

---

## Out-of-scope — follow-up plans needed after this lands

- **iOS port** — separate plan. Estimate 2–3 weeks on top of this.
- **Daily-digest desktop scheduling** — needs a system-tray daemon or autostart entries per OS.
- **Voice messages on desktop** — `javax.sound.sampled` integration.
- **On-device AI on desktop** — bind to `llama.cpp` or skip entirely.
- **Auto-update mechanism for desktop** — the in-app update flow is currently Android-only via `PackageInstaller`. Desktop needs `--update`-style relaunch or per-platform installers (Sparkle on macOS, etc.).
- **Web (Wasm) target** — defer to 2027 when Compose Web is stable.
