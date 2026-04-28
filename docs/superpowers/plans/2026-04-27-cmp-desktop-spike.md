# Compose Multiplatform Desktop Spike

**Goal:** in 1–2 days, produce a `:composeApp:run` desktop build that renders something recognizable as Talon (ideally the chat list against mock data, minimum the home/login screen), and a written punch list of what would need to change for full feature parity. **The deliverable is the punch list, not a shipping desktop app.**

**Why a spike:** porting cost estimates ("2–4 weeks for desktop, more for iOS") are guesses on paper. A spike turns them into specifics — every Android dep we hit, every `expect`/`actual` we'd need, every Compose extension that doesn't exist on desktop. The punch list becomes the input to a real port plan.

**Branch:** do this on a `spike/cmp-desktop` branch. Master stays Android-only and shippable. If the spike succeeds, we merge into a real port plan; if it fails, we throw the branch away.

**Tech stack:** Kotlin Multiplatform 2.0.20 (matches current project), Compose Multiplatform 1.7.x, JDK 17. No iOS targets in this spike — desktop and Android only.

---

## Scope discipline

In scope:
- Add a `composeApp/` module that targets Android + Desktop.
- Identify the largest possible subset of `app/src/main/java/io/nisfeb/talon/**` that compiles unchanged in a `commonMain` source set, and move it.
- For Android-only seams (notifications, alarms, services, MediaStore, ML Kit), declare `expect` in commonMain and provide trivial stub `actual` in desktopMain (no-op, hardcoded, or `error("not implemented on desktop")`).
- Run the desktop build, screenshot whatever renders.
- Write the punch list.

Out of scope:
- iOS target.
- Web/Wasm target.
- Replacing OkHttp with Ktor (would force a real iOS port; desktop runs OkHttp fine on JVM).
- Replacing Room with SQLDelight (Room 2.7 has KMP support; spike uses that).
- Replacing Coil 2 with Coil 3 if currently on 2.x — defer to real port.
- Making any feature actually work end-to-end on desktop. The chat list against mock data is the bar.
- Touching `app/` (the production Android module). The spike adds a new module; it doesn't restructure the existing one.

---

## Pre-spike check

- [ ] **Step 1: Confirm Compose Multiplatform supports our deps**

Run these checks (no code changes):

```bash
# Compose Multiplatform 1.7.x supports Kotlin 2.0.x — confirm.
grep -E "kotlin = |composeBom" /home/sneagan/software/personal/talon/gradle/libs.versions.toml

# Room 2.7+ has KMP. We need to be on 2.7+ for commonMain to use Room.
grep room /home/sneagan/software/personal/talon/gradle/libs.versions.toml

# Coil 3 is multiplatform. Coil 2 isn't.
grep coil /home/sneagan/software/personal/talon/gradle/libs.versions.toml
```

Expected outcomes — and what to do if they don't match:
- Kotlin ≥ 2.0.20: ✅ proceed.
- Room ≥ 2.7: if older, deferring Room to commonMain doesn't work in this spike. Stub the data layer with mock data instead — the goal is rendering, not real Room reads.
- Coil ≥ 3.0: same deal. If Coil 2, skip image rendering in the spike.

Note any "stays in androidMain" decisions in the punch list.

---

## Phase A — Scaffold

### Task A1: Create the multiplatform module

**Files:**
- Create: `composeApp/build.gradle.kts`
- Modify: `settings.gradle.kts` (add the new module)

- [ ] **Step 1: Branch**

```bash
cd /home/sneagan/software/personal/talon
git checkout -b spike/cmp-desktop
```

- [ ] **Step 2: Add the module to `settings.gradle.kts`**

Append:

```kotlin
include(":composeApp")
```

- [ ] **Step 3: Create `composeApp/build.gradle.kts`**

```kotlin
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    jvm("desktop") {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.components.resources)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.okhttp)
        }
        val desktopMain by getting
        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.okhttp)
        }
    }
}

android {
    namespace = "io.nisfeb.talon.compose"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

compose.desktop {
    application {
        mainClass = "io.nisfeb.talon.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Talon"
            packageVersion = "0.4.2"
        }
    }
}
```

- [ ] **Step 4: Add the Compose Multiplatform plugin to the version catalog**

In `gradle/libs.versions.toml`, in the `[versions]` section add:

```toml
composeMultiplatform = "1.7.0"
kotlinx-coroutines-swing = "1.8.0"
```

In `[plugins]`:

```toml
compose-multiplatform = { id = "org.jetbrains.compose", version.ref = "composeMultiplatform" }
```

In `[libraries]`:

```toml
kotlinx-coroutines-swing = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-swing", version.ref = "kotlinx-coroutines-swing" }
```

(If the kotlin-multiplatform plugin isn't already in the catalog, add it too — same kotlin version as the existing kotlin-android entry.)

- [ ] **Step 5: Create the directory structure**

```bash
mkdir -p composeApp/src/commonMain/kotlin/io/nisfeb/talon
mkdir -p composeApp/src/androidMain/kotlin/io/nisfeb/talon
mkdir -p composeApp/src/desktopMain/kotlin/io/nisfeb/talon
```

- [ ] **Step 6: Write a Hello World to confirm the desktop build runs**

Create `composeApp/src/desktopMain/kotlin/io/nisfeb/talon/Main.kt`:

```kotlin
package io.nisfeb.talon

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Talon (spike)") {
        MaterialTheme {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Talon Compose Multiplatform spike — Hello.")
            }
        }
    }
}
```

- [ ] **Step 7: Run the desktop build**

```bash
JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 \
  PATH=$JAVA_HOME/bin:$PATH \
  ./gradlew :composeApp:run
```

Expected: a window opens saying "Talon Compose Multiplatform spike — Hello."

If this fails, the punch list's first entry is whatever broke. Stop and write up the failure mode (Gradle plugin compatibility, JDK version, etc.) before continuing.

- [ ] **Step 8: Commit**

```bash
git add composeApp/ settings.gradle.kts gradle/libs.versions.toml
git commit -m "spike: scaffold composeApp KMP module (android + desktop)"
```

---

## Phase B — Migrate the portable surface

### Task B1: Identify the bridgeable subset

The goal is to find every file under `app/src/main/java/io/nisfeb/talon/` that compiles unchanged in `commonMain`. Heuristic: any file whose imports contain `android.*`, `androidx.*` (except `androidx.compose.*`), `okhttp3.*`, `kotlinx.coroutines.*` (except `kotlinx.coroutines.android`) is non-portable.

- [ ] **Step 1: Generate the candidate list**

```bash
cd /home/sneagan/software/personal/talon
grep -rL --include="*.kt" \
    -e "^import android\." \
    -e "^import androidx\.\(activity\|core\|room\|datastore\|work\|preference\|security\|biometric\|browser\|fragment\|lifecycle\.LiveData\|lifecycle\.ViewModel\|navigation\|paging\)" \
    -e "^import com\.google\." \
    -e "^import dagger\." \
    app/src/main/java/io/nisfeb/talon/ \
  > /tmp/candidate-portable.txt
wc -l /tmp/candidate-portable.txt
head -50 /tmp/candidate-portable.txt
```

The numbers won't be perfect — `androidx.compose.*` is portable but `androidx.activity.compose.*` isn't. Manual triage follows. Save the rough list to refer back to when writing the punch list.

- [ ] **Step 2: Pick the spike target screen**

The chat list (`DmListScreen.kt`) is the most visible feature surface — but it depends on `Repo`, Room flows, Urbit session state, etc. For the spike, target one of:

- **Easy mode:** the `LoginScreen.kt` or a single-row `ConversationRow.kt` rendered against a hand-built `MessageEntity` mock.
- **Stretch:** `DmListScreen.kt` with mock data passed in via constructor instead of read from Room.

Pick easy mode unless Phase A finished in well under a day. The point is to find breakage, not to render the full screen.

- [ ] **Step 3: Triage which files come along**

For each candidate file in `/tmp/candidate-portable.txt`, decide: portable (move to commonMain), Android-only (stays in app/, or duplicate in androidMain), or "needs `expect`/`actual`" (declare interface in commonMain, implement in androidMain + desktopMain).

The categories you'll likely see:
- **Pure Compose UI** (most rows + composables) — portable, modulo a few `Modifier.systemBarsPadding()` calls that need a desktop-equivalent.
- **`Repo` and friends** — portable in spirit but pulls Room transitively. For the spike, pass mock data and skip Room entirely.
- **Wire layer (`urbit/`)** — portable on JVM (OkHttp works), but `Log.i` calls need a `expect fun log(...)` shim or a logging facade. For the spike, replace `Log.i/w/e` calls with `println` in the moved files (don't change the originals).
- **Settings (`AiSettings`, etc.)** — depend on `SharedPreferences`. Stub with in-memory mocks for the spike.
- **Theme (`Theme.kt`, `Typography.kt`)** — portable directly.

- [ ] **Step 4: Move the chosen files**

Copy (don't move — keep the originals in `app/` so master still builds) the files into `composeApp/src/commonMain/kotlin/io/nisfeb/talon/`. Strip Android-specific imports. Replace `Log.*` calls with `println`. Replace `SharedPreferences`-based reads with hardcoded constants.

For the spike, use copies. The real port would consolidate later.

- [ ] **Step 5: Try to compile**

```bash
JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 \
  PATH=$JAVA_HOME/bin:$PATH \
  ./gradlew :composeApp:compileCommonMainKotlinMetadata 2>&1 | tee /tmp/spike-compile.log | tail -60
```

Whatever errors come out are the punch list — write them down. Fix the easiest ones (missing imports, simple type mismatches), skip the hard ones (Room queries, Service references). The spike is allowed to leave `// TODO desktop:` comments wherever the breakage gets non-trivial.

- [ ] **Step 6: Render**

Wire your spike target screen into `Main.kt` with mock data:

```kotlin
fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Talon (spike)") {
        TalonTheme {
            Surface {
                LoginScreen(  // or ConversationRow, or DmListScreen
                    // … pass mock data …
                )
            }
        }
    }
}
```

Run it:

```bash
JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 \
  PATH=$JAVA_HOME/bin:$PATH \
  ./gradlew :composeApp:run
```

Screenshot whatever opens. Even if it's broken, screenshot the broken thing.

- [ ] **Step 7: Commit progress**

```bash
git add composeApp/
git commit -m "spike: migrate $TARGET_SCREEN to commonMain, render against mock data"
```

---

## Phase C — Punch list

### Task C1: Write the report

**File:** `docs/superpowers/spikes/2026-04-27-cmp-desktop-findings.md` (create the directory if needed).

Structure the report as:

```markdown
# CMP Desktop Spike — Findings

## What rendered
- [Screenshot or "didn't get there" with the failure]
- Files moved: N. Files compiled in commonMain: M. Files needed androidMain shims: K.

## Hard blockers
[Things that would prevent a real port without major work. Each entry:
 - File + line ref
 - The Android API in question
 - Estimated cost of providing a desktop equivalent (hours/days)]

## Soft blockers
[Things that broke but are obvious to fix. Each entry: same shape as above
 but the fix is "swap library X for Y" or "wrap in expect/actual".]

## Surprising wins
[Things that worked unchanged. Compose code that just compiled, libraries
 that turned out to already be MP, etc.]

## Recommendation
- Realistic effort for a real port: [X person-weeks]
- Biggest unknowns: [list]
- Whether to proceed: [yes / yes-with-caveats / no — explain]
```

- [ ] **Step 1: Fill in each section based on the actual spike work**

Use specifics. "23 files in `urbit/` compiled unchanged" beats "most of urbit/ ports cleanly." Cite line numbers for blockers.

- [ ] **Step 2: Commit the report**

```bash
git add docs/superpowers/spikes/2026-04-27-cmp-desktop-findings.md
git commit -m "spike: cmp-desktop findings + recommendation"
```

- [ ] **Step 3: Push the branch**

```bash
git push -u origin spike/cmp-desktop
```

Don't merge into master. The findings doc is the deliverable; the branch is the workshop.

---

## Stopping conditions

The spike ends when ANY of these is true:

- The punch list has at least 5 concrete blockers OR confirms portability of one full screen rendered against mock data.
- 2 working days have elapsed.
- A blocker turns out to be much harder than anticipated (e.g. Room 2.7 KMP doesn't actually work for our schema, Compose Multiplatform 1.7 has a regression on Linux). Stop, document, recommend.

The point of a spike is to fail fast and write down what you learned. If the desktop build doesn't run after Phase A, **that's a successful spike** — you learned that the toolchain isn't there yet. Write that up as the recommendation.

---

## What this plan deliberately doesn't do

- Doesn't restructure `app/` — production Android stays where it is.
- Doesn't try to make Room work on desktop (mock data is sufficient).
- Doesn't try iOS — every "iOS would also need…" comment in the punch list is a free observation, not a deliverable.
- Doesn't attempt to port the `update/` package (Stage A/B work) — that's sideload-only and platform-specific by design.
- Doesn't deliver a working desktop Talon. A real port is a separate plan that this spike's punch list informs.
