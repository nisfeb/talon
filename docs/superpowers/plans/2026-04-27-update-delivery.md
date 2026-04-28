# Update Delivery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship Talon updates to users through two complementary channels: a website (GitHub Releases + HTTPS manifest) and an Urbit subscription push (`%talon-updates` agent on a release ship), backed by shared infrastructure (real release keystore, manifest format, in-app banner).

**Architecture:** Both channels resolve to the same `UpdateManifest` JSON: `{versionCode, versionName, url, sha256, minSdk, changelog, mandatory}`. A `Channel`-agnostic `UpdateChecker` interface has two implementations (`HttpUpdateChecker`, `UrbitUpdateChecker`); both publish into a single `UpdateState` flow that drives a Compose `UpdateBanner`. Tap → `UpdateInstaller` downloads the APK via Android `DownloadManager`, verifies SHA-256, hands the file to `PackageInstaller`. The APK itself always lives on GitHub Releases — Urbit only carries the *notification* that a new manifest exists.

**Tech Stack:** Kotlin 2.0 / Compose 1.7 / OkHttp 4.12 / Android `DownloadManager` + `PackageInstaller`; Hoon (Urbit) for the `%talon-updates` agent; GitHub Actions for the release pipeline.

---

## Embedded Design Summary

This plan rolls the design and the implementation into one document because the design is small.

**Trust models:**
- **Channel 1 (HTTPS):** trust GitHub's TLS for the manifest, trust the SHA-256 in the manifest for the APK, trust the APK's signing certificate (matched by Android against the installed app's cert).
- **Channel 2 (Urbit push):** hardcode a single trusted release ship (`@p`) in the app. Reject any `%talon-updates` fact that arrives from any other ship. The APK URL still points to the same GitHub Release; SHA-256 still verified. Compromising the release ship lets an attacker make the app *prompt* for a stale or alternate version, but they can't substitute an APK without the keystore.

**Single source of truth:** the GitHub Actions release workflow generates `latest.json`, uploads it as a Release asset, and pokes the `%talon-updates` agent with the same payload. Both channels read the same manifest, so they cannot drift.

**Update prompt UX:** non-blocking banner above the chat list. "Talon 0.5.1 available — tap to update." Single primary action. Dismissible (until next cold start). When `mandatory: true`, banner is sticky and blocks compose with a toast.

**Rate limit:** HTTP checker fires once per cold start, max once per 12h (persist last-checked-ms in `SharedPreferences`). Urbit checker is event-driven (no rate limit needed; the agent only emits on real changes).

**Channel selection:** a settings toggle with three modes: `Off`, `HTTPS only`, `Urbit + HTTPS`. Default for fresh installs is `HTTPS only` (works without ship login). Power users can opt into `Urbit + HTTPS` for instant pushes.

**Decisions to make before starting:**
1. **Release keystore location.** Recommendation: `~/.config/talon/release.keystore` outside the repo, with passwords stored in `~/.config/talon/keystore.properties` (gitignored). Backup the keystore + properties in your password manager — losing it means new key, which means uninstall/reinstall for all users.
2. **Release ship `@p`.** Recommendation: `~nisfeb` (the user's existing ship). If you'd rather isolate the publishing identity, spin up a moon and use that.
3. **GitHub repo URL.** Throughout this plan I assume `https://github.com/sneagan/talon`. Replace if different.

---

## File Map

**New Kotlin (app):**
- `app/src/main/java/io/nisfeb/talon/update/UpdateManifest.kt` — data class + JSON parser
- `app/src/main/java/io/nisfeb/talon/update/UpdateChecker.kt` — `interface UpdateChecker { suspend fun check(): UpdateManifest? }`
- `app/src/main/java/io/nisfeb/talon/update/HttpUpdateChecker.kt` — fetches `latest.json` over HTTPS with rate limit
- `app/src/main/java/io/nisfeb/talon/update/UrbitUpdateChecker.kt` — subscribes to `%talon-updates` `/v1/latest`
- `app/src/main/java/io/nisfeb/talon/update/UpdateState.kt` — singleton `StateFlow<UpdateStatus>`
- `app/src/main/java/io/nisfeb/talon/update/UpdateInstaller.kt` — `DownloadManager` + `PackageInstaller` driver
- `app/src/main/java/io/nisfeb/talon/update/UpdateSettings.kt` — DataStore-backed settings (channel mode + last-checked-ms)
- `app/src/main/java/io/nisfeb/talon/ui/UpdateBanner.kt` — Compose banner

**New tests:**
- `app/src/test/kotlin/io/nisfeb/talon/update/UpdateManifestTest.kt`
- `app/src/test/kotlin/io/nisfeb/talon/update/HttpUpdateCheckerTest.kt`

**Modified Kotlin:**
- `app/build.gradle.kts` — release `signingConfig` switch from debug → real keystore
- `app/src/main/AndroidManifest.xml` — `REQUEST_INSTALL_PACKAGES` permission + `FileProvider`
- `app/src/main/res/xml/file_paths.xml` — FileProvider paths (new file)
- `app/src/main/java/io/nisfeb/talon/TalonApplication.kt` — instantiate `UpdateState`, wire checkers
- `app/src/main/java/io/nisfeb/talon/ui/screens/DmListScreen.kt` — render `UpdateBanner` above the list
- `app/src/main/java/io/nisfeb/talon/ui/screens/SettingsScreen.kt` — channel-mode toggle

**New Hoon (Urbit agent):**
- `desk/talon-updates/sys.kelvin`
- `desk/talon-updates/desk.bill`
- `desk/talon-updates/desk.docket-0`
- `desk/talon-updates/sur/talon-updates.hoon`
- `desk/talon-updates/mar/talon-updates/manifest.hoon`
- `desk/talon-updates/app/talon-updates.hoon`

**New CI / release infra:**
- `.github/workflows/release.yml` — build, sign, publish APK + manifest on tag push
- `release/keystore.properties.example` — template (real one stays out of repo)
- `release/manifest-template.json` — manifest skeleton consumed by the workflow
- `release/poke-agent.sh` — small bash helper that pokes the talon-updates agent over HTTP

---

## Pre-work (manual, do these first)

### Pre-1: Generate the release keystore

- [ ] **Step 1: Create the config directory**

```bash
mkdir -p ~/.config/talon
chmod 700 ~/.config/talon
```

- [ ] **Step 2: Generate a 25-year RSA keystore**

```bash
keytool -genkeypair \
  -alias talon \
  -keyalg RSA \
  -keysize 4096 \
  -validity 9125 \
  -keystore ~/.config/talon/release.keystore \
  -storetype PKCS12 \
  -dname "CN=Talon, O=nisfeb, C=US"
```

Pick a strong store password and write it down — losing it bricks future updates.

- [ ] **Step 3: Save the password in a properties file**

Create `~/.config/talon/keystore.properties` with:

```properties
storeFile=/home/sneagan/.config/talon/release.keystore
storePassword=<the password you just typed>
keyAlias=talon
keyPassword=<same password>
```

```bash
chmod 600 ~/.config/talon/keystore.properties
```

- [ ] **Step 4: Back it up**

Copy `~/.config/talon/release.keystore` and `~/.config/talon/keystore.properties` to your password manager / encrypted backup. **Do not commit them.** Verify `~/.config/talon/` is not tracked by any git repo.

### Pre-2: Pick the release ship

- [ ] **Step 1: Decide and write down the @p**

Decision: which ship will publish updates? Recommended: `~nisfeb`. Write the chosen `@p` somewhere you'll find it again — it gets hardcoded in app code (Task 9) and used in the GitHub Actions secret (Task 8).

---

## Stage A — Foundation (must finish before either channel works)

### Task 1: Wire the release keystore into Gradle

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `.gitignore`

- [ ] **Step 1: Make sure the keystore properties file isn't tracked**

Append to `.gitignore`:

```
# Local-only release signing — keystore lives outside the repo at
# ~/.config/talon/release.keystore; the path is read from
# RELEASE_KEYSTORE_PROPS env var.
release/keystore.properties
```

- [ ] **Step 2: Add a signing config that reads from the keystore properties**

In `app/build.gradle.kts`, replace the existing `buildTypes { release { ... } }` block. Find the `android { ... }` block and update it so the file looks like this around the relevant sections:

```kotlin
import java.util.Properties

android {
    namespace = "io.nisfeb.talon"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.nisfeb.talon"
        minSdk = 26
        targetSdk = 35
        versionCode = 19
        versionName = "0.4.2"
    }

    signingConfigs {
        create("release") {
            // Keystore lives outside the repo. Path comes from the
            // RELEASE_KEYSTORE_PROPS env var (file containing
            // storeFile / storePassword / keyAlias / keyPassword).
            // Falls back to debug signing when the env var is unset
            // so unsigned local debug builds still work.
            val propsPath = System.getenv("RELEASE_KEYSTORE_PROPS")
            if (propsPath != null) {
                val props = Properties().apply {
                    java.io.FileInputStream(propsPath).use { load(it) }
                }
                storeFile = file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            val hasReleaseKeys = System.getenv("RELEASE_KEYSTORE_PROPS") != null
            signingConfig = signingConfigs.getByName(
                if (hasReleaseKeys) "release" else "debug"
            )
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    // ... rest of android { ... } unchanged
}
```

- [ ] **Step 3: Test the signing config locally**

Run:

```bash
RELEASE_KEYSTORE_PROPS=$HOME/.config/talon/keystore.properties \
  JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 \
  PATH=$JAVA_HOME/bin:$PATH \
  ./gradlew :app:assembleRelease
```

Expected: `BUILD SUCCESSFUL`. Then verify the APK is signed with the new key:

```bash
keytool -printcert -jarfile app/build/outputs/apk/release/app-release.apk
```

The output should show `Owner: CN=Talon, O=nisfeb, C=US`. If it shows `CN=Android Debug`, the env var didn't apply — re-check Step 2.

- [ ] **Step 4: Commit**

```bash
git add app/build.gradle.kts .gitignore
git commit -m "build: switch release signing to real keystore via env var"
```

### Task 2: Add install-packages permission and FileProvider

The `PackageInstaller` API needs the APK as a content URI; that requires a FileProvider. Android 8+ also requires the `REQUEST_INSTALL_PACKAGES` permission for any app that hands an APK to `PackageInstaller`.

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/xml/file_paths.xml`

- [ ] **Step 1: Add the permission and FileProvider to the manifest**

In `app/src/main/AndroidManifest.xml`, inside the top-level `<manifest>` element, add (next to existing `<uses-permission>` lines):

```xml
<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
```

And inside the `<application>` element, add (next to other `<provider>` elements if any, or at the end):

```xml
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.updates.fileprovider"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/file_paths" />
</provider>
```

- [ ] **Step 2: Create the FileProvider paths config**

Create `app/src/main/res/xml/file_paths.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<paths xmlns:android="http://schemas.android.com/apk/res/android">
    <!--
      Where UpdateInstaller drops the downloaded APK before handing
      it to PackageInstaller. external-files-path resolves to
      Context.getExternalFilesDir(null).
    -->
    <external-files-path
        name="updates"
        path="updates" />
</paths>
```

- [ ] **Step 3: Build to confirm the manifest still compiles**

```bash
JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 \
  PATH=$JAVA_HOME/bin:$PATH \
  ./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/res/xml/file_paths.xml
git commit -m "android: REQUEST_INSTALL_PACKAGES + FileProvider for in-app update install"
```

### Task 3: UpdateManifest data class and parser

**Files:**
- Create: `app/src/main/java/io/nisfeb/talon/update/UpdateManifest.kt`
- Test: `app/src/test/kotlin/io/nisfeb/talon/update/UpdateManifestTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/kotlin/io/nisfeb/talon/update/UpdateManifestTest.kt`:

```kotlin
package io.nisfeb.talon.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateManifestTest {

    private val sample = """
        {
          "versionCode": 21,
          "versionName": "0.5.0",
          "url": "https://github.com/sneagan/talon/releases/download/v0.5.0/talon-0.5.0.apk",
          "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
          "minSdk": 26,
          "changelog": "Polls fixed; daily digest weather is now Fahrenheit.",
          "mandatory": false
        }
    """.trimIndent()

    @Test fun `parses well-formed manifest`() {
        val m = UpdateManifest.parse(sample)!!
        assertEquals(21, m.versionCode)
        assertEquals("0.5.0", m.versionName)
        assertEquals(
            "https://github.com/sneagan/talon/releases/download/v0.5.0/talon-0.5.0.apk",
            m.url,
        )
        assertEquals(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            m.sha256,
        )
        assertEquals(26, m.minSdk)
        assertEquals(false, m.mandatory)
    }

    @Test fun `mandatory defaults to false when missing`() {
        val noMandatory = sample.replace(",\n          \"mandatory\": false", "")
        val m = UpdateManifest.parse(noMandatory)!!
        assertEquals(false, m.mandatory)
    }

    @Test fun `rejects manifest with non-https url`() {
        val httpUrl = sample.replace("https://", "http://")
        assertNull(UpdateManifest.parse(httpUrl))
    }

    @Test fun `rejects manifest with malformed sha256`() {
        val badHash = sample.replace(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            "deadbeef",
        )
        assertNull(UpdateManifest.parse(badHash))
    }

    @Test fun `rejects garbage input`() {
        assertNull(UpdateManifest.parse("not json"))
        assertNull(UpdateManifest.parse(""))
    }
}
```

- [ ] **Step 2: Run the test, confirm it fails**

```bash
JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 \
  PATH=$JAVA_HOME/bin:$PATH \
  ./gradlew :app:testDebugUnitTest --tests "io.nisfeb.talon.update.UpdateManifestTest"
```

Expected: FAIL with "unresolved reference: UpdateManifest".

- [ ] **Step 3: Implement UpdateManifest**

Create `app/src/main/java/io/nisfeb/talon/update/UpdateManifest.kt`:

```kotlin
package io.nisfeb.talon.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * The shape both update channels resolve to. Backed by the same
 * latest.json hosted on GitHub Releases (Channel 1) and pushed by
 * the talon-updates Urbit agent (Channel 2).
 *
 * url + sha256 are belt-and-braces: TLS protects the download, but
 * the hash also catches mid-air swap if a user routes through a
 * sketchy proxy or ends up with a partial/cached download.
 */
data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val url: String,
    val sha256: String,
    val minSdk: Int,
    val changelog: String,
    val mandatory: Boolean,
) {
    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }
        private val SHA256_RE = Regex("^[0-9a-fA-F]{64}$")

        fun parse(raw: String): UpdateManifest? = runCatching {
            val obj = JSON.parseToJsonElement(raw).jsonObject
            val url = obj["url"]?.jsonPrimitive?.contentOrNull
                ?: return@runCatching null
            // Defense in depth: never accept a non-HTTPS URL even
            // if the manifest came from a "trusted" source.
            if (!url.startsWith("https://")) return@runCatching null
            val sha256 = obj["sha256"]?.jsonPrimitive?.contentOrNull
                ?: return@runCatching null
            if (!SHA256_RE.matches(sha256)) return@runCatching null
            UpdateManifest(
                versionCode = obj["versionCode"]?.jsonPrimitive?.intOrNull
                    ?: return@runCatching null,
                versionName = obj["versionName"]?.jsonPrimitive?.contentOrNull
                    ?: return@runCatching null,
                url = url,
                sha256 = sha256.lowercase(),
                minSdk = obj["minSdk"]?.jsonPrimitive?.intOrNull ?: 26,
                changelog = obj["changelog"]?.jsonPrimitive?.contentOrNull ?: "",
                mandatory = obj["mandatory"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        }.getOrNull()
    }
}
```

- [ ] **Step 4: Run the tests, confirm they pass**

```bash
JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 \
  PATH=$JAVA_HOME/bin:$PATH \
  ./gradlew :app:testDebugUnitTest --tests "io.nisfeb.talon.update.UpdateManifestTest"
```

Expected: 5 tests passed, BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/nisfeb/talon/update/UpdateManifest.kt \
  app/src/test/kotlin/io/nisfeb/talon/update/UpdateManifestTest.kt
git commit -m "update: UpdateManifest data class + parser with HTTPS / sha256 validation"
```

### Task 4: UpdateChecker interface, UpdateStatus, UpdateState

**Files:**
- Create: `app/src/main/java/io/nisfeb/talon/update/UpdateChecker.kt`
- Create: `app/src/main/java/io/nisfeb/talon/update/UpdateState.kt`

- [ ] **Step 1: Define the interface and status enum**

Create `app/src/main/java/io/nisfeb/talon/update/UpdateChecker.kt`:

```kotlin
package io.nisfeb.talon.update

/**
 * Either channel produces an UpdateManifest or null. Implementations
 * MUST NOT throw — return null on any failure (network, parse,
 * subscription drop). Logging is the implementation's responsibility.
 */
interface UpdateChecker {
    suspend fun check(): UpdateManifest?
}

/**
 * Surface state for the banner. Idle when nothing to show. Available
 * holds the manifest waiting for user action. Downloading carries
 * progress 0..100. Ready means the APK is on disk and verified.
 * Failed flips when a download or hash check broke; the banner shows
 * the message and lets the user retry.
 */
sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data class Available(val manifest: UpdateManifest) : UpdateStatus
    data class Downloading(val manifest: UpdateManifest, val progress: Int) : UpdateStatus
    data class Ready(val manifest: UpdateManifest, val apkPath: String) : UpdateStatus
    data class Failed(val manifest: UpdateManifest?, val message: String) : UpdateStatus
}
```

- [ ] **Step 2: Implement UpdateState as a singleton flow**

Create `app/src/main/java/io/nisfeb/talon/update/UpdateState.kt`:

```kotlin
package io.nisfeb.talon.update

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Process-wide source of truth for the "is there an update?" banner.
 * Owns no checkers itself; the application registers HTTP / Urbit
 * checkers and tells UpdateState when they fire. Threadsafe — all
 * mutations go through the StateFlow.
 */
class UpdateState(
    private val context: Context,
    private val scope: CoroutineScope,
    private val installer: UpdateInstaller,
) {
    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    /**
     * Called by both checkers when they observe a manifest. We only
     * surface it if it's actually newer than what's installed — so a
     * channel that re-emits the current version on reconnect doesn't
     * spam the banner.
     */
    fun onManifest(manifest: UpdateManifest) {
        val installed = installedVersionCode()
        if (manifest.versionCode <= installed) return
        // Don't clobber an in-flight download. If the user already
        // tapped to update, leave them alone.
        when (val cur = _status.value) {
            is UpdateStatus.Downloading,
            is UpdateStatus.Ready -> return
            else -> {
                if (cur is UpdateStatus.Available && cur.manifest.versionCode == manifest.versionCode) {
                    return  // same offer, no churn
                }
                _status.value = UpdateStatus.Available(manifest)
            }
        }
    }

    /**
     * User tapped the banner. Kicks off the download. Progress and
     * completion publish back into the state flow.
     */
    fun startDownload(manifest: UpdateManifest) {
        _status.value = UpdateStatus.Downloading(manifest, 0)
        scope.launch(Dispatchers.IO) {
            installer.download(
                manifest = manifest,
                onProgress = { pct ->
                    _status.value = UpdateStatus.Downloading(manifest, pct)
                },
                onReady = { apkPath ->
                    _status.value = UpdateStatus.Ready(manifest, apkPath)
                },
                onFailure = { message ->
                    _status.value = UpdateStatus.Failed(manifest, message)
                },
            )
        }
    }

    /** User tapped Install on the Ready banner — fire the system installer prompt. */
    fun launchInstaller(apkPath: String) {
        installer.install(apkPath)
    }

    fun dismiss() {
        if (_status.value is UpdateStatus.Available) {
            _status.value = UpdateStatus.Idle
        }
    }

    private fun installedVersionCode(): Int = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionCode
    } catch (e: Exception) {
        0
    }
}
```

- [ ] **Step 3: Build to confirm it compiles**

```bash
JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 \
  PATH=$JAVA_HOME/bin:$PATH \
  ./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL. (You'll get an "unresolved reference: UpdateInstaller" — that's Task 5; if so, comment out the `installer` references in `UpdateState` until Task 5 lands and uncomment when done. Otherwise, do Task 5 first then commit both together. Recommend the latter.)

- [ ] **Step 4: Commit (after Task 5 also lands — see note in Step 3)**

```bash
git add app/src/main/java/io/nisfeb/talon/update/UpdateChecker.kt \
  app/src/main/java/io/nisfeb/talon/update/UpdateState.kt
git commit -m "update: UpdateChecker interface + UpdateState single-source-of-truth flow"
```

### Task 5: UpdateInstaller (download + verify + install)

**Files:**
- Create: `app/src/main/java/io/nisfeb/talon/update/UpdateInstaller.kt`

- [ ] **Step 1: Implement the installer**

Create `app/src/main/java/io/nisfeb/talon/update/UpdateInstaller.kt`:

```kotlin
package io.nisfeb.talon.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

class UpdateInstaller(private val context: Context) {

    /**
     * Download the APK to external-files/updates, verify its SHA-256,
     * call onReady with the absolute path. onProgress receives 0..100.
     * onFailure receives a human message.
     */
    suspend fun download(
        manifest: UpdateManifest,
        onProgress: (Int) -> Unit,
        onReady: (String) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        val updatesDir = File(context.getExternalFilesDir(null), "updates").apply { mkdirs() }
        val target = File(updatesDir, "talon-${manifest.versionName}.apk")
        if (target.exists()) target.delete()

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val req = DownloadManager.Request(Uri.parse(manifest.url))
            .setTitle("Talon ${manifest.versionName}")
            .setDescription("Downloading update")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(target))
            .addRequestHeader("User-Agent", "Talon-UpdateInstaller")
        val downloadId = dm.enqueue(req)

        // Poll. DownloadManager's broadcast is racy in our use case
        // (we want fine-grained progress); polling at 250ms is fine
        // for a single foreground-bounded download.
        while (true) {
            val q = DownloadManager.Query().setFilterById(downloadId)
            val cursor: Cursor = dm.query(q) ?: run {
                onFailure("download manager returned null cursor")
                return
            }
            cursor.use { c ->
                if (!c.moveToFirst()) {
                    onFailure("download id $downloadId not found")
                    return
                }
                val statusIdx = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val status = c.getInt(statusIdx)
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val ok = verifySha256(target, manifest.sha256)
                        if (!ok) {
                            target.delete()
                            onFailure("downloaded APK failed SHA-256 check")
                            return
                        }
                        onReady(target.absolutePath)
                        return
                    }
                    DownloadManager.STATUS_FAILED -> {
                        val reasonIdx = c.getColumnIndex(DownloadManager.COLUMN_REASON)
                        val reason = c.getInt(reasonIdx)
                        onFailure("download failed (reason $reason)")
                        return
                    }
                    DownloadManager.STATUS_RUNNING,
                    DownloadManager.STATUS_PAUSED,
                    DownloadManager.STATUS_PENDING -> {
                        val totalIdx = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                        val soFarIdx = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                        val total = c.getLong(totalIdx)
                        val soFar = c.getLong(soFarIdx)
                        val pct = if (total > 0) ((soFar * 100) / total).toInt() else 0
                        onProgress(pct.coerceIn(0, 99))
                    }
                }
            }
            delay(250)
        }
    }

    /** Hand the verified APK to PackageInstaller via FileProvider URI. */
    fun install(apkPath: String) {
        val file = File(apkPath)
        val authority = "${context.packageName}.updates.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("UpdateInstaller", "install intent failed", e)
        }
    }

    private fun verifySha256(file: File, expected: String): Boolean {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        val got = md.digest().joinToString("") { "%02x".format(it) }
        return got.equals(expected, ignoreCase = true)
    }
}
```

- [ ] **Step 2: Build**

```bash
JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 \
  PATH=$JAVA_HOME/bin:$PATH \
  ./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit (combined with Task 4 if you split the build above)**

```bash
git add app/src/main/java/io/nisfeb/talon/update/
git commit -m "update: UpdateInstaller (DownloadManager + SHA-256 verify + PackageInstaller intent)"
```

### Task 6: UpdateBanner Compose component

**Files:**
- Create: `app/src/main/java/io/nisfeb/talon/ui/UpdateBanner.kt`

- [ ] **Step 1: Implement the banner**

Create `app/src/main/java/io/nisfeb/talon/ui/UpdateBanner.kt`:

```kotlin
package io.nisfeb.talon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.update.UpdateStatus

@Composable
fun UpdateBanner(
    status: UpdateStatus,
    onTap: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (status) {
        is UpdateStatus.Idle -> Unit
        is UpdateStatus.Available -> BannerSurface(
            primary = "Talon ${status.manifest.versionName} available",
            secondary = status.manifest.changelog.takeIf { it.isNotBlank() }
                ?: "Tap to update.",
            onTap = onTap,
            onDismiss = if (status.manifest.mandatory) null else onDismiss,
        )
        is UpdateStatus.Downloading -> BannerSurface(
            primary = "Downloading ${status.manifest.versionName}…",
            secondary = "${status.progress}%",
            onTap = null,
            onDismiss = null,
            progress = status.progress,
        )
        is UpdateStatus.Ready -> BannerSurface(
            primary = "Tap to install ${status.manifest.versionName}",
            secondary = "Verified — Android will ask you to confirm.",
            onTap = onTap,
            onDismiss = null,
        )
        is UpdateStatus.Failed -> BannerSurface(
            primary = "Update failed",
            secondary = status.message + " · tap to retry",
            onTap = onTap,
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun BannerSurface(
    primary: String,
    secondary: String,
    onTap: (() -> Unit)?,
    onDismiss: (() -> Unit)?,
    progress: Int? = null,
) {
    val rowMod = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 6.dp)
        .clip(RoundedCornerShape(10.dp))
        .background(MaterialTheme.colorScheme.primaryContainer)
        .let { if (onTap != null) it.clickable(onClick = onTap) else it }
        .padding(horizontal = 12.dp, vertical = 10.dp)
    Row(
        modifier = rowMod,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                primary,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                secondary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
        }
        if (onDismiss != null) {
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Dismiss",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}
```

- [ ] **Step 2: Build**

```bash
JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 \
  PATH=$JAVA_HOME/bin:$PATH \
  ./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/io/nisfeb/talon/ui/UpdateBanner.kt
git commit -m "update: UpdateBanner Composable for available/downloading/ready/failed states"
```

### Task 7: Wire UpdateState into TalonApplication and DmListScreen

**Files:**
- Modify: `app/src/main/java/io/nisfeb/talon/TalonApplication.kt`
- Modify: `app/src/main/java/io/nisfeb/talon/ui/screens/DmListScreen.kt`

- [ ] **Step 1: Add UpdateState to TalonApplication**

In `app/src/main/java/io/nisfeb/talon/TalonApplication.kt`, near the other `lateinit var` declarations, add:

```kotlin
lateinit var updateState: UpdateState
```

In `onCreate` (after `sessionStore = SessionStore(this)`), add:

```kotlin
val updateInstaller = UpdateInstaller(this)
updateState = UpdateState(
    context = this,
    scope = io.nisfeb.talon.util.AppScopes.app(),
    installer = updateInstaller,
)
```

(Confirm `AppScopes.app()` returns a long-lived `CoroutineScope` — if no such helper exists in the project, use `CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)` and store it in a `private val` on `TalonApplication`.)

Add the imports at the top of the file:

```kotlin
import io.nisfeb.talon.update.UpdateInstaller
import io.nisfeb.talon.update.UpdateState
```

- [ ] **Step 2: Render the banner in DmListScreen**

In `app/src/main/java/io/nisfeb/talon/ui/screens/DmListScreen.kt`, find the top-level `Column` inside the screen (around line 700, the one wrapping the title row + tab strip + LazyColumn). Just below the `HorizontalDivider()` that follows the tab strip and *above* the `LazyColumn`, insert:

```kotlin
val updateStatus by app.updateState.status.collectAsState()
io.nisfeb.talon.ui.UpdateBanner(
    status = updateStatus,
    onTap = {
        when (val s = updateStatus) {
            is io.nisfeb.talon.update.UpdateStatus.Available ->
                app.updateState.startDownload(s.manifest)
            is io.nisfeb.talon.update.UpdateStatus.Ready ->
                app.updateState.launchInstaller(s.apkPath)
            is io.nisfeb.talon.update.UpdateStatus.Failed -> {
                val m = s.manifest ?: return@UpdateBanner
                app.updateState.startDownload(m)
            }
            else -> Unit
        }
    },
    onDismiss = { app.updateState.dismiss() },
)
```

(`app` is the existing `TalonApplication` reference in scope — confirm by reading the surrounding code; in this file it's `(LocalContext.current.applicationContext as TalonApplication)` named `app`.)

- [ ] **Step 3: Build and install on the connected device**

```bash
RELEASE_KEYSTORE_PROPS=$HOME/.config/talon/keystore.properties \
  JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 \
  PATH=$JAVA_HOME/bin:$PATH \
  ./gradlew :app:installDebug
```

- [ ] **Step 4: Smoke test the banner manually**

Open `adb shell am broadcast` is not enough — the banner reads `UpdateState`. Easiest manual test: temporarily add `updateState.onManifest(UpdateManifest(versionCode = 9999, versionName = "test", url = "https://example.com/foo.apk", sha256 = "0".repeat(64), minSdk = 26, changelog = "smoke test", mandatory = false))` in `TalonApplication.onCreate`, restart the app, confirm the banner appears, then revert.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/nisfeb/talon/TalonApplication.kt \
  app/src/main/java/io/nisfeb/talon/ui/screens/DmListScreen.kt
git commit -m "update: wire UpdateState into TalonApplication + show banner in DmListScreen"
```

---

## Stage B — Channel 1 (HTTPS / GitHub Releases)

### Task 8: HttpUpdateChecker with rate-limited fetch

**Files:**
- Create: `app/src/main/java/io/nisfeb/talon/update/HttpUpdateChecker.kt`
- Test: `app/src/test/kotlin/io/nisfeb/talon/update/HttpUpdateCheckerTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/kotlin/io/nisfeb/talon/update/HttpUpdateCheckerTest.kt`:

```kotlin
package io.nisfeb.talon.update

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class HttpUpdateCheckerTest {

    private lateinit var server: MockWebServer

    @Before fun start() {
        server = MockWebServer()
        server.start()
    }

    @After fun stop() {
        server.shutdown()
    }

    private val sample = """
        {
          "versionCode": 21,
          "versionName": "0.5.0",
          "url": "https://example.com/talon-0.5.0.apk",
          "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
          "minSdk": 26,
          "changelog": "x",
          "mandatory": false
        }
    """.trimIndent()

    @Test fun `returns parsed manifest on 200`() = runBlocking {
        server.enqueue(MockResponse().setBody(sample))
        val checker = HttpUpdateChecker(
            http = OkHttpClient(),
            url = server.url("/latest.json").toString(),
            now = { 0L },
            lastCheckedAtMs = { 0L },
            recordCheckedAt = {},
            minIntervalMs = 0,
        )
        val m = checker.check()!!
        assertEquals(21, m.versionCode)
    }

    @Test fun `returns null on 500`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        val checker = HttpUpdateChecker(
            http = OkHttpClient(),
            url = server.url("/latest.json").toString(),
            now = { 0L },
            lastCheckedAtMs = { 0L },
            recordCheckedAt = {},
            minIntervalMs = 0,
        )
        assertNull(checker.check())
    }

    @Test fun `respects rate limit`() = runBlocking {
        // Last check was 1s ago, min interval is 12h — should skip the network.
        val checker = HttpUpdateChecker(
            http = OkHttpClient(),
            url = server.url("/latest.json").toString(),
            now = { 1_000L },
            lastCheckedAtMs = { 0L },
            recordCheckedAt = {},
            minIntervalMs = 12 * 60 * 60 * 1000L,
        )
        assertNull(checker.check())
        // Server was not hit:
        assertEquals(0, server.requestCount)
    }

    @Test fun `records last-checked timestamp on success`() = runBlocking {
        server.enqueue(MockResponse().setBody(sample))
        var recorded: Long? = null
        val checker = HttpUpdateChecker(
            http = OkHttpClient(),
            url = server.url("/latest.json").toString(),
            now = { 12_345L },
            lastCheckedAtMs = { 0L },
            recordCheckedAt = { recorded = it },
            minIntervalMs = 0,
        )
        checker.check()
        assertEquals(12_345L, recorded)
    }
}
```

- [ ] **Step 2: Add MockWebServer to test deps**

In `app/build.gradle.kts`, find the `dependencies { ... }` block and add (under existing `testImplementation` lines):

```kotlin
testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
```

- [ ] **Step 3: Run the test, confirm it fails**

```bash
JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 \
  PATH=$JAVA_HOME/bin:$PATH \
  ./gradlew :app:testDebugUnitTest --tests "io.nisfeb.talon.update.HttpUpdateCheckerTest"
```

Expected: FAIL with "unresolved reference: HttpUpdateChecker".

- [ ] **Step 4: Implement HttpUpdateChecker**

Create `app/src/main/java/io/nisfeb/talon/update/HttpUpdateChecker.kt`:

```kotlin
package io.nisfeb.talon.update

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Fetches latest.json from a fixed HTTPS URL. Caller injects clock
 * + persistence hooks so tests don't need a real Context.
 *
 * Rate-limited: skip the network if we checked less than
 * [minIntervalMs] ago. The cold-start caller invokes this once per
 * launch; this guard keeps us honest if the app gets cold-started
 * many times in a short window.
 */
class HttpUpdateChecker(
    private val http: OkHttpClient,
    private val url: String,
    private val now: () -> Long,
    private val lastCheckedAtMs: () -> Long,
    private val recordCheckedAt: (Long) -> Unit,
    private val minIntervalMs: Long,
) : UpdateChecker {

    override suspend fun check(): UpdateManifest? {
        val nowMs = now()
        val last = lastCheckedAtMs()
        if (nowMs - last < minIntervalMs) return null
        return runCatching {
            val req = Request.Builder().url(url)
                .header("User-Agent", "Talon-UpdateChecker")
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val body = resp.body?.string() ?: return@runCatching null
                val m = UpdateManifest.parse(body) ?: return@runCatching null
                recordCheckedAt(nowMs)
                m
            }
        }.onFailure {
            Log.w("HttpUpdateChecker", "check failed", it)
        }.getOrNull()
    }
}
```

- [ ] **Step 5: Run the tests, confirm they pass**

```bash
JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 \
  PATH=$JAVA_HOME/bin:$PATH \
  ./gradlew :app:testDebugUnitTest --tests "io.nisfeb.talon.update.HttpUpdateCheckerTest"
```

Expected: 4 tests passed.

- [ ] **Step 6: Wire HttpUpdateChecker into TalonApplication**

In `app/src/main/java/io/nisfeb/talon/TalonApplication.kt`, after the `updateState = UpdateState(...)` line, add:

```kotlin
val updatePrefs = getSharedPreferences("update_state", MODE_PRIVATE)
val httpChecker = HttpUpdateChecker(
    http = http,  // existing OkHttpClient on TalonApplication
    url = "https://github.com/sneagan/talon/releases/latest/download/latest.json",
    now = { System.currentTimeMillis() },
    lastCheckedAtMs = { updatePrefs.getLong("last_http_check_ms", 0L) },
    recordCheckedAt = { updatePrefs.edit().putLong("last_http_check_ms", it).apply() },
    minIntervalMs = 12L * 60L * 60L * 1000L,
)
io.nisfeb.talon.util.AppScopes.app().launch {
    val m = httpChecker.check()
    if (m != null) updateState.onManifest(m)
}
```

Add imports:

```kotlin
import io.nisfeb.talon.update.HttpUpdateChecker
import kotlinx.coroutines.launch
```

- [ ] **Step 7: Build and install**

```bash
RELEASE_KEYSTORE_PROPS=$HOME/.config/talon/keystore.properties \
  JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 \
  PATH=$JAVA_HOME/bin:$PATH \
  ./gradlew :app:installRelease
```

- [ ] **Step 8: Commit**

```bash
git add app/build.gradle.kts \
  app/src/main/java/io/nisfeb/talon/update/HttpUpdateChecker.kt \
  app/src/test/kotlin/io/nisfeb/talon/update/HttpUpdateCheckerTest.kt \
  app/src/main/java/io/nisfeb/talon/TalonApplication.kt
git commit -m "update: HttpUpdateChecker + cold-start fetch from GitHub Releases latest.json"
```

### Task 9: GitHub Actions release workflow

**Files:**
- Create: `.github/workflows/release.yml`
- Create: `release/manifest-template.json`

- [ ] **Step 1: Add the manifest template**

Create `release/manifest-template.json`:

```json
{
  "versionCode": __VERSION_CODE__,
  "versionName": "__VERSION_NAME__",
  "url": "https://github.com/sneagan/talon/releases/download/v__VERSION_NAME__/talon-__VERSION_NAME__.apk",
  "sha256": "__SHA256__",
  "minSdk": 26,
  "changelog": "__CHANGELOG__",
  "mandatory": __MANDATORY__
}
```

- [ ] **Step 2: Add the release workflow**

Create `.github/workflows/release.yml`:

```yaml
name: release

on:
  push:
    tags:
      - 'v*'

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '17'

      - name: Decode keystore
        env:
          KEYSTORE_BASE64: ${{ secrets.RELEASE_KEYSTORE_BASE64 }}
        run: |
          mkdir -p ~/.config/talon
          echo "$KEYSTORE_BASE64" | base64 -d > ~/.config/talon/release.keystore
          cat > ~/.config/talon/keystore.properties <<EOF
          storeFile=$HOME/.config/talon/release.keystore
          storePassword=${{ secrets.RELEASE_STORE_PASSWORD }}
          keyAlias=${{ secrets.RELEASE_KEY_ALIAS }}
          keyPassword=${{ secrets.RELEASE_KEY_PASSWORD }}
          EOF
          chmod 600 ~/.config/talon/keystore.properties

      - name: Build release APK
        env:
          RELEASE_KEYSTORE_PROPS: /home/runner/.config/talon/keystore.properties
        run: ./gradlew :app:assembleRelease

      - name: Resolve version metadata
        id: meta
        run: |
          TAG="${GITHUB_REF#refs/tags/v}"
          VERSION_CODE=$(grep 'versionCode = ' app/build.gradle.kts | head -1 | grep -oE '[0-9]+')
          VERSION_NAME=$(grep 'versionName = ' app/build.gradle.kts | head -1 | grep -oE '"[^"]+"' | tr -d '"')
          if [ "$TAG" != "$VERSION_NAME" ]; then
            echo "Tag ($TAG) does not match versionName ($VERSION_NAME) in build.gradle.kts"
            exit 1
          fi
          APK=app/build/outputs/apk/release/app-release.apk
          OUT_APK="talon-$VERSION_NAME.apk"
          cp "$APK" "$OUT_APK"
          SHA=$(sha256sum "$OUT_APK" | awk '{print $1}')
          CHANGELOG="$(git log -1 --pretty=%B | tr '\n' ' ' | sed 's/"/\\"/g')"
          sed -e "s/__VERSION_CODE__/$VERSION_CODE/" \
              -e "s/__VERSION_NAME__/$VERSION_NAME/" \
              -e "s/__SHA256__/$SHA/" \
              -e "s/__CHANGELOG__/$CHANGELOG/" \
              -e "s/__MANDATORY__/false/" \
              release/manifest-template.json > latest.json
          echo "apk=$OUT_APK" >> $GITHUB_OUTPUT
          echo "version_name=$VERSION_NAME" >> $GITHUB_OUTPUT

      - name: Publish GitHub Release
        uses: softprops/action-gh-release@v2
        with:
          files: |
            ${{ steps.meta.outputs.apk }}
            latest.json
          generate_release_notes: true
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
```

- [ ] **Step 3: Configure the GitHub repo secrets**

In the GitHub web UI for the repo, **Settings → Secrets and variables → Actions → New repository secret**, add four secrets:
- `RELEASE_KEYSTORE_BASE64` — output of `base64 -w0 ~/.config/talon/release.keystore`
- `RELEASE_STORE_PASSWORD`
- `RELEASE_KEY_ALIAS` — `talon`
- `RELEASE_KEY_PASSWORD`

- [ ] **Step 4: Cut a test release**

```bash
git tag v0.4.3
git push origin v0.4.3
```

Watch the Actions tab. After it finishes, confirm a Release `v0.4.3` exists with `talon-0.4.3.apk` and `latest.json` attached. Confirm `https://github.com/sneagan/talon/releases/latest/download/latest.json` redirects to the latest manifest.

- [ ] **Step 5: End-to-end smoke test**

On the connected phone running an older Talon (e.g., 0.4.2), force-stop and reopen the app. Within ~2s the banner should appear: "Talon 0.4.3 available". Tap → progress shows → "Tap to install" → Android prompts → install. Done.

- [ ] **Step 6: Commit**

```bash
git add .github/workflows/release.yml release/manifest-template.json
git commit -m "release: GitHub Actions workflow that builds, signs, and publishes APK + manifest"
```

---

## Stage C — Channel 2 (Urbit push)

### Task 10: talon-updates Urbit agent skeleton

**Files:**
- Create: `desk/talon-updates/sys.kelvin`
- Create: `desk/talon-updates/desk.bill`
- Create: `desk/talon-updates/desk.docket-0`
- Create: `desk/talon-updates/sur/talon-updates.hoon`
- Create: `desk/talon-updates/mar/talon-updates/manifest.hoon`
- Create: `desk/talon-updates/app/talon-updates.hoon`

- [ ] **Step 1: kelvin pin**

Create `desk/talon-updates/sys.kelvin`:

```
[%zuse 412]
```

- [ ] **Step 2: bill (apps to launch on install)**

Create `desk/talon-updates/desk.bill`:

```
:~  %talon-updates
==
```

- [ ] **Step 3: docket (so the desk installs)**

Create `desk/talon-updates/desk.docket-0`:

```
:~  title+'Talon Updates'
    info+'Publishes Talon Android update manifests to subscribers.'
    color+0xff.0000
    version+[0 1 0]
    website+'https://github.com/sneagan/talon'
    license+'MIT'
==
```

- [ ] **Step 4: type definitions**

Create `desk/talon-updates/sur/talon-updates.hoon`:

```hoon
|%
+$  manifest
  $:  version-code=@ud
      version-name=@t
      url=@t
      sha-256=@t
      min-sdk=@ud
      changelog=@t
      mandatory=?
  ==
::
+$  action
  $%  [%set =manifest]
      [%clear ~]
  ==
::
+$  update
  $%  [%manifest =manifest]
      [%cleared ~]
  ==
--
```

- [ ] **Step 5: mark for the manifest JSON**

Create `desk/talon-updates/mar/talon-updates/manifest.hoon`:

```hoon
/-  *talon-updates
|_  m=manifest
++  grow
  |%
  ++  noun  m
  ++  json
    =,  enjs:format
    %-  pairs
    :~  ['versionCode' (numb version-code.m)]
        ['versionName' s+version-name.m]
        ['url' s+url.m]
        ['sha256' s+sha-256.m]
        ['minSdk' (numb min-sdk.m)]
        ['changelog' s+changelog.m]
        ['mandatory' b+mandatory.m]
    ==
  --
++  grab
  |%
  ++  noun  manifest
  ++  json
    =,  dejs:format
    %-  ot
    :~  ['versionCode' ni]
        ['versionName' so]
        ['url' so]
        ['sha256' so]
        ['minSdk' ni]
        ['changelog' so]
        ['mandatory' bo]
    ==
  --
++  grad  %noun
--
```

- [ ] **Step 6: agent**

Create `desk/talon-updates/app/talon-updates.hoon`:

```hoon
::  %talon-updates: stores the latest Talon Android manifest and
::  pushes it to subscribers. Single-fact-of-truth — only the desk
::  owner can poke. Subscribers connect to /v1/latest and receive
::  the current manifest immediately, then any changes.
::
/-  *talon-updates
/+  default-agent, dbug, verb
|%
+$  versioned-state
  $%  state-0
  ==
+$  state-0  [%0 latest=(unit manifest)]
+$  card  card:agent:gall
--
%-  agent:dbug
%+  verb  |
=|  state-0
=*  state  -
^-  agent:gall
|_  =bowl:gall
+*  this  .
    def   ~(. (default-agent this %|) bowl)
::
++  on-init
  ^-  (quip card _this)
  `this(state [%0 ~])
::
++  on-save  !>(state)
::
++  on-load
  |=  old-vase=vase
  ^-  (quip card _this)
  `this(state !<(state-0 old-vase))
::
++  on-poke
  |=  [=mark =vase]
  ^-  (quip card _this)
  ?>  =(src.bowl our.bowl)
  ?+    mark  (on-poke:def mark vase)
      %talon-updates-action
    =/  act  !<(action vase)
    ?-    -.act
        %set
      :_  this(latest `manifest.act)
      [%give %fact ~[/v1/latest] %talon-updates-update !>([%manifest manifest.act])]~
    ::
        %clear
      :_  this(latest ~)
      [%give %fact ~[/v1/latest] %talon-updates-update !>([%cleared ~])]~
    ==
  ==
::
++  on-watch
  |=  =path
  ^-  (quip card _this)
  ?+    path  (on-watch:def path)
      [%v1 %latest ~]
    ?~  latest  `this
    :_  this
    [%give %fact ~ %talon-updates-update !>([%manifest u.latest])]~
  ==
::
++  on-peek
  |=  =path
  ^-  (unit (unit cage))
  ?+    path  (on-peek:def path)
      [%x %v1 %latest ~]
    ?~  latest  ~
    ``talon-updates-manifest+!>(u.latest)
  ==
::
++  on-leave  on-leave:def
++  on-agent  on-agent:def
++  on-arvo   on-arvo:def
++  on-fail   on-fail:def
--
```

You'll also need the action and update marks. Create them inline in the agent file is non-idiomatic; instead create:

`desk/talon-updates/mar/talon-updates/action.hoon`:

```hoon
/-  *talon-updates
|_  a=action
++  grow
  |%
  ++  noun  a
  --
++  grab
  |%
  ++  noun  action
  ++  json
    =,  dejs:format
    |=  jon=json
    ^-  action
    %.  jon
    %-  of
    :~  ['set' (cu manifest:^manifest:dejs:^,format ot)]
        ['clear' ul]
    ==
  --
++  grad  %noun
--
```

Note: this is intentionally simplified. The poker (Task 11) speaks `%noun` directly so the JSON conversion path on action isn't strictly needed. If you keep poker noun-only, replace the `grab` for json with `++  json  !!` and skip the inline conversion.

`desk/talon-updates/mar/talon-updates/update.hoon`:

```hoon
/-  *talon-updates
|_  u=update
++  grow
  |%
  ++  noun  u
  ++  json
    ?-    -.u
        %manifest
      =,  enjs:format
      %-  pairs
      :~  ['type' s+'manifest']
          ['manifest' (json:^,grow:^,manifest manifest.u)]
      ==
    ::
        %cleared
      [%s 'cleared']
    ==
  --
++  grab
  |%
  ++  noun  update
  --
++  grad  %noun
--
```

(Hoon import paths above with `^,` are pseudo — verify against the actual project conventions before committing. If your Urbit fakezod fails to compile, simplify by emitting the manifest JSON inline in the agent's `%give %fact` rather than going through a structured update mark.)

- [ ] **Step 7: Install on a fakezod and smoke-test**

On a fakezod:

```
|new-desk %talon-updates
|commit %talon-updates
|install our %talon-updates
```

Then poke it:

```
:talon-updates &talon-updates-action [%set [21 '0.5.0' 'https://github.com/sneagan/talon/releases/download/v0.5.0/talon-0.5.0.apk' '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef' 26 'test' |]]
```

Subscribe in another fakezod / agent:

```
|hi our  ::  ensure connected
:: from a test agent or via a watch via the channel API:
.^(cage %gx /=talon-updates=/v1/latest/talon-updates-manifest)
```

Expected: scry returns the manifest. The repeated poke should re-publish to subscribers.

- [ ] **Step 8: Commit**

```bash
git add desk/talon-updates/
git commit -m "urbit: %talon-updates agent that publishes manifests to /v1/latest"
```

### Task 11: poke-agent helper for the release pipeline

**Files:**
- Create: `release/poke-agent.sh`
- Modify: `.github/workflows/release.yml`

- [ ] **Step 1: Write the poke helper**

Create `release/poke-agent.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail

# Pokes the talon-updates agent on the release ship via its HTTP API.
# Required env:
#   RELEASE_SHIP_URL    e.g. https://urbit.example.com  (the +code login URL prefix)
#   RELEASE_SHIP_CODE   the +code for the ship
#   MANIFEST_PATH       path to latest.json on disk

if [[ -z "${RELEASE_SHIP_URL:-}" || -z "${RELEASE_SHIP_CODE:-}" || -z "${MANIFEST_PATH:-}" ]]; then
  echo "RELEASE_SHIP_URL, RELEASE_SHIP_CODE, MANIFEST_PATH all required" >&2
  exit 1
fi

JAR=$(mktemp)
trap 'rm -f "$JAR"' EXIT

# 1) login — saves the auth cookie into a jar.
curl -sS -c "$JAR" -X POST \
  -d "password=$RELEASE_SHIP_CODE" \
  "$RELEASE_SHIP_URL/~/login" >/dev/null

# 2) build the action payload as JSON. Read manifest fields and wrap
#    them in {set: {...}}.
ACTION=$(jq -c '{set: .}' "$MANIFEST_PATH")

# 3) Open a channel + poke. Channel ids are arbitrary — using a
#    timestamp-based id avoids collisions across runs.
CHANNEL_ID="release-$(date +%s)"
curl -sS -b "$JAR" -X PUT \
  -H 'Content-Type: application/json' \
  --data "[{\"id\":1,\"action\":\"poke\",\"ship\":\"$(echo "$ACTION" | jq -r '.set.url' | grep -oE 'releases.+' >/dev/null; echo $URBIT_RELEASE_SHIP_PATP)\",\"app\":\"talon-updates\",\"mark\":\"talon-updates-action\",\"json\":$ACTION}]" \
  "$RELEASE_SHIP_URL/~/channel/$CHANNEL_ID"

echo "poked talon-updates with manifest $(basename "$MANIFEST_PATH")"
```

(The `URBIT_RELEASE_SHIP_PATP` env var is populated by the workflow in Step 2.)

```bash
chmod +x release/poke-agent.sh
```

- [ ] **Step 2: Wire the poke into the release workflow**

In `.github/workflows/release.yml`, after the "Publish GitHub Release" step, add:

```yaml
      - name: Push manifest to talon-updates agent
        env:
          RELEASE_SHIP_URL: ${{ secrets.URBIT_RELEASE_SHIP_URL }}
          RELEASE_SHIP_CODE: ${{ secrets.URBIT_RELEASE_SHIP_CODE }}
          URBIT_RELEASE_SHIP_PATP: ${{ secrets.URBIT_RELEASE_SHIP_PATP }}
          MANIFEST_PATH: latest.json
        run: |
          sudo apt-get install -y jq
          ./release/poke-agent.sh
```

Add three more GitHub secrets:
- `URBIT_RELEASE_SHIP_URL` — base URL of the release ship's HTTP service
- `URBIT_RELEASE_SHIP_CODE` — the `+code` of the ship
- `URBIT_RELEASE_SHIP_PATP` — the `@p` (e.g. `~nisfeb`)

- [ ] **Step 3: Test by re-tagging**

```bash
git tag -d v0.4.3 2>/dev/null || true
git push origin :refs/tags/v0.4.3 2>/dev/null || true
git tag v0.4.4
git push origin v0.4.4
```

Watch Actions. After the workflow finishes, scry the agent on the release ship:

```
.^(cage %gx /=talon-updates=/v1/latest/talon-updates-manifest)
```

Should return the 0.4.4 manifest.

- [ ] **Step 4: Commit**

```bash
git add release/poke-agent.sh .github/workflows/release.yml
git commit -m "release: poke talon-updates agent after publishing GitHub Release"
```

### Task 12: UrbitUpdateChecker — subscribe via UrbitChannel

**Files:**
- Create: `app/src/main/java/io/nisfeb/talon/update/UrbitUpdateChecker.kt`
- Modify: `app/src/main/java/io/nisfeb/talon/TalonApplication.kt`

- [ ] **Step 1: Implement the subscriber**

Create `app/src/main/java/io/nisfeb/talon/update/UrbitUpdateChecker.kt`:

```kotlin
package io.nisfeb.talon.update

import android.util.Log
import io.nisfeb.talon.urbit.UrbitChannel
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Long-running subscriber that listens for talon-updates facts on
 * the user's ship's channel feed. The release-ship @p is hardcoded;
 * any fact from a different ship is ignored.
 *
 * Note: this isn't a one-shot UpdateChecker — it's a subscription
 * that calls onManifest whenever the agent emits one. Run it from a
 * long-lived coroutine.
 */
class UrbitUpdateChecker(
    private val channel: UrbitChannel,
    private val releaseShipPatp: String,
    private val onManifest: (UpdateManifest) -> Unit,
) {

    suspend fun start() {
        runCatching {
            channel.subscribe(
                app = "talon-updates",
                path = "/v1/latest",
                onShip = releaseShipPatp,
            )
        }.onFailure {
            Log.w("UrbitUpdateChecker", "subscribe failed", it)
            return
        }
        channel.events().collect { event ->
            val outer = event.body as? JsonObject ?: return@collect
            // Only diff payloads carry facts we care about.
            if (outer["response"]?.jsonPrimitive?.contentOrNull != "diff") return@collect
            val payload = outer["json"] as? JsonObject ?: return@collect
            val type = payload["type"]?.jsonPrimitive?.contentOrNull
            if (type != "manifest") return@collect
            val inner = payload["manifest"]?.jsonObject ?: return@collect
            val m = UpdateManifest.parse(inner.toString()) ?: run {
                Log.w("UrbitUpdateChecker", "manifest payload failed validation")
                return@collect
            }
            onManifest(m)
        }
    }
}
```

(Adjust import + property names if `UrbitChannel.events().collect` event body access differs from this in your codebase — verify against `app/src/main/java/io/nisfeb/talon/urbit/UrbitChannel.kt`.)

- [ ] **Step 2: Wire it up in TalonApplication**

In `app/src/main/java/io/nisfeb/talon/TalonApplication.kt`, after `httpChecker.check()` is launched, add:

```kotlin
val urbitChecker = UrbitUpdateChecker(
    channel = repo.channel ?: return,  // not connected yet — Channel 2 will start on first session
    releaseShipPatp = "~nisfeb",
    onManifest = { updateState.onManifest(it) },
)
io.nisfeb.talon.util.AppScopes.app().launch {
    urbitChecker.start()
}
```

A robust version delays this until after `repo.channel` is non-null and re-subscribes on session reconnects. Easiest hook is to subscribe inside `runSessionOnce` in `TlonChatRepo.kt` rather than in `TalonApplication`. Pseudocode:

```kotlin
// in runSessionOnce(session, firstRun), after subscribe(...) calls:
runCatching { ch.subscribe("talon-updates", "/v1/latest", onShip = "~nisfeb") }
   .onFailure { Log.e(TAG, "talon-updates subscribe failed", it) }
```

Then add a branch in `applyEvent` to handle `talon-updates` facts and call `updateState.onManifest`. This is the more correct integration; pick whichever fits the existing pattern in the file.

- [ ] **Step 3: Build and install**

```bash
RELEASE_KEYSTORE_PROPS=$HOME/.config/talon/keystore.properties \
  JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 \
  PATH=$JAVA_HOME/bin:$PATH \
  ./gradlew :app:installRelease
```

- [ ] **Step 4: Smoke test end-to-end via Urbit channel**

On the release ship, poke the agent with a higher version than what's installed:

```
:talon-updates &talon-updates-action [%set [99 '9.9.9' 'https://github.com/sneagan/talon/releases/download/v9.9.9/talon-9.9.9.apk' '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef' 26 'urbit smoke test' |]]
```

(Use a fake URL — you'll dismiss the banner without downloading.)

On the phone, the banner should appear within ~1s of the poke landing. Dismiss it, then poke `[%set ...]` with the real current version and confirm the banner does *not* re-appear (versionCode <= installed).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/nisfeb/talon/update/UrbitUpdateChecker.kt \
  app/src/main/java/io/nisfeb/talon/TalonApplication.kt \
  app/src/main/java/io/nisfeb/talon/urbit/TlonChatRepo.kt  # if you wired via repo
git commit -m "update: UrbitUpdateChecker subscribes to talon-updates and routes into UpdateState"
```

### Task 13: Channel-mode setting

**Files:**
- Create: `app/src/main/java/io/nisfeb/talon/update/UpdateSettings.kt`
- Modify: `app/src/main/java/io/nisfeb/talon/ui/screens/SettingsScreen.kt`
- Modify: `app/src/main/java/io/nisfeb/talon/TalonApplication.kt`

- [ ] **Step 1: UpdateSettings holder**

Create `app/src/main/java/io/nisfeb/talon/update/UpdateSettings.kt`:

```kotlin
package io.nisfeb.talon.update

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class UpdateChannelMode { Off, HttpsOnly, UrbitAndHttps }

/**
 * Per-device update preference. Persisted via SharedPreferences;
 * exposes a StateFlow so UI + checker startup can react.
 */
class UpdateSettings(context: Context) {
    private val prefs = context.getSharedPreferences("update_settings", Context.MODE_PRIVATE)
    private val _mode = MutableStateFlow(load())
    val mode: StateFlow<UpdateChannelMode> = _mode.asStateFlow()

    fun set(mode: UpdateChannelMode) {
        prefs.edit().putString("mode", mode.name).apply()
        _mode.value = mode
    }

    private fun load(): UpdateChannelMode {
        val raw = prefs.getString("mode", UpdateChannelMode.HttpsOnly.name)
        return runCatching { UpdateChannelMode.valueOf(raw!!) }
            .getOrDefault(UpdateChannelMode.HttpsOnly)
    }
}
```

- [ ] **Step 2: Gate checker launches by mode**

In `TalonApplication.onCreate`, replace the unconditional checker launches with:

```kotlin
val updateSettings = UpdateSettings(this)
this.updateSettings = updateSettings  // expose if you need it elsewhere

io.nisfeb.talon.util.AppScopes.app().launch {
    updateSettings.mode.collect { mode ->
        when (mode) {
            UpdateChannelMode.Off -> Unit
            UpdateChannelMode.HttpsOnly -> {
                val m = httpChecker.check()
                if (m != null) updateState.onManifest(m)
            }
            UpdateChannelMode.UrbitAndHttps -> {
                val m = httpChecker.check()
                if (m != null) updateState.onManifest(m)
                // Urbit subscribe is wired inside TlonChatRepo; the
                // mode flag is read there to decide whether to add
                // the talon-updates subscription on each connect.
            }
        }
    }
}
```

In `TlonChatRepo.runSessionOnce`, gate the talon-updates subscribe on mode:

```kotlin
if (app.updateSettings.mode.value == UpdateChannelMode.UrbitAndHttps) {
    runCatching { ch.subscribe("talon-updates", "/v1/latest", onShip = "~nisfeb") }
        .onFailure { Log.e(TAG, "talon-updates subscribe failed", it) }
}
```

- [ ] **Step 3: Add the toggle to SettingsScreen**

In `app/src/main/java/io/nisfeb/talon/ui/screens/SettingsScreen.kt`, find the existing settings group section (e.g., where AI / notification settings live) and add a new section:

```kotlin
val mode by app.updateSettings.mode.collectAsState()
Text("Updates", style = MaterialTheme.typography.titleSmall)
listOf(
    UpdateChannelMode.Off to "Off",
    UpdateChannelMode.HttpsOnly to "Check website",
    UpdateChannelMode.UrbitAndHttps to "Website + Urbit push",
).forEach { (option, label) ->
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { app.updateSettings.set(option) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = mode == option, onClick = { app.updateSettings.set(option) })
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
```

- [ ] **Step 4: Build and install**

```bash
RELEASE_KEYSTORE_PROPS=$HOME/.config/talon/keystore.properties \
  JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 \
  PATH=$JAVA_HOME/bin:$PATH \
  ./gradlew :app:installRelease
```

- [ ] **Step 5: Manual test**

In Settings, switch to `Off`, force-stop the app, reopen — confirm the banner does not appear even though a newer manifest exists. Switch to `HttpsOnly`, restart — banner appears. Switch to `UrbitAndHttps`, restart — banner appears AND `adb logcat -s TlonChatRepo` shows the talon-updates subscribe line.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/nisfeb/talon/update/UpdateSettings.kt \
  app/src/main/java/io/nisfeb/talon/ui/screens/SettingsScreen.kt \
  app/src/main/java/io/nisfeb/talon/TalonApplication.kt \
  app/src/main/java/io/nisfeb/talon/urbit/TlonChatRepo.kt
git commit -m "update: per-device channel mode setting (Off / Https / Urbit+Https)"
```

---

## Final smoke tests (operate the system you built)

### Task 14: End-to-end release rehearsal

- [ ] **Step 1: Bump versionName + versionCode**

In `app/build.gradle.kts`, bump to e.g. `0.5.0` / `20`. Commit:

```bash
git add app/build.gradle.kts
git commit -m "release: 0.5.0"
```

- [ ] **Step 2: Tag and push**

```bash
git tag v0.5.0
git push origin master
git push origin v0.5.0
```

- [ ] **Step 3: Verify the website channel**

From a phone running 0.4.x:
1. Force-stop Talon.
2. Reopen.
3. Within a few seconds the banner should announce 0.5.0 with the changelog from the tag commit.
4. Tap → progress → Ready → Install → Android system prompt → Install.
5. Reopen Talon, verify the version in About is 0.5.0.

- [ ] **Step 4: Verify the Urbit channel**

From a *second* phone running 0.4.x with `UrbitAndHttps` mode and ship logged in:
1. Set the device's clock back so the HTTPS rate limit holds (or just pick a brand-new clean install).
2. Confirm the banner appears via Urbit (no HTTPS fetch needed) — `adb logcat -s TlonChatRepo` should show the talon-updates fact arriving.
3. Tap, install, verify.

- [ ] **Step 5: Verify cross-device dismissal does NOT propagate**

Dismissing the banner is per-device — confirm dismissing on phone A leaves phone B's banner up.

If any of these fail, the failure points to the relevant Stage's tasks; iterate there.

---

## Self-review notes

- All file paths are concrete; placeholders for the GitHub repo URL and release ship `@p` are explicit and called out as decisions, not as TODOs.
- Each Stage produces working software:
  - After Stage A, the banner exists but no checker fires it (only the manual smoke test in Task 7). That's intentional — Stage A alone does not ship a feature.
  - After Stage B, Channel 1 (website) works end-to-end.
  - After Stage C, Channel 2 (Urbit push) works end-to-end.
- Hoon naming consistency: `manifest`, `action`, `update` are used identically across `sur/`, `mar/`, and `app/`. The poke uses `%talon-updates-action` mark; the fact uses `%talon-updates-update` mark.
- Kotlin naming consistency: `UpdateManifest`, `UpdateChecker`, `UpdateState`, `UpdateInstaller`, `UpdateBanner`, `UpdateSettings`, `UpdateChannelMode`, `UpdateStatus` — used identically wherever they appear.
- The Hoon mark code uses `^,grow:^,manifest` style cross-mark references, which may need adjusting for your project's actual import conventions. Task 10 step 6 calls this out and offers a simpler fallback (emit JSON directly in the agent).
