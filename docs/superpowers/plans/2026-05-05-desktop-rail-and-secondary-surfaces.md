# Desktop rail + secondary list-pane surfaces (Phase 2) — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a 64dp vertical icon rail on the far left at ≥840dp window widths that switches the existing list pane between Chats, Statuses, Bookmarks, and Activity. Right pane (chat detail) is independent. Mobile / compact behaviour is unchanged.

**Architecture:** A new `DesktopShell` composable wraps the Phase 1 `ChatPaneScaffold`. Below 840dp it's a passthrough (only the existing chat surface renders, as today). At/above 840dp it adds the rail on the left and resolves the scaffold's `list` slot from a per-ship `activeRailTab` setting. Existing screen wrappers (`StatusFeedScreen` / `BookmarksScreen` / `ActivityFeedScreen`) get refactored to delegate their list body to new `*List` composables that the rail also consumes — extraction, not parameterisation, so mobile keeps its existing back-arrow / `PlatformBackHandler` flow byte-for-byte.

**Tech Stack:** Kotlin Multiplatform + Compose Multiplatform 1.7, Material3 (icons + tooltips), kotlinx.coroutines.flow.StateFlow, kotlinx.serialization.json (`DesktopUiSettings`'s existing pattern), AndroidX SharedPreferences (`AndroidUiSettings`).

**Spec:** [`docs/superpowers/specs/2026-05-05-desktop-rail-and-secondary-surfaces-design.md`](../specs/2026-05-05-desktop-rail-and-secondary-surfaces-design.md)

---

## File structure

**Create:**

| Path | Responsibility |
|---|---|
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/RailTab.kt` | `RailTab` enum (single source of truth for rail surfaces) |
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/DesktopShell.kt` | The new layout host: rail + ChatPaneScaffold + reserved sidebar slot |
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/StatusFeedList.kt` | Extracted list body from `StatusFeedScreen` |
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/BookmarksList.kt` | Extracted list body from `BookmarksScreen` |
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/ActivityList.kt` | Extracted list body from `ActivityFeedScreen` |
| `composeApp/src/commonTest/kotlin/io/nisfeb/talon/ui/RailTabTest.kt` | Enum name round-trip + valueOfOrDefault helper |
| `composeApp/src/desktopTest/kotlin/io/nisfeb/talon/ui/DesktopShellTest.kt` | Compose UI tests for the rail + breakpoint |
| `composeApp/src/desktopTest/kotlin/io/nisfeb/talon/ui/DesktopUiSettingsActiveRailTabTest.kt` | JSON round-trip + corrupt-value fallback |

**Modify:**

| Path | What |
|---|---|
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/UiSettings.kt` | Add `activeRailTab: StateFlow<RailTab>` + `setActiveRailTab(tab)` to interface and `InMemoryUiSettings` |
| `composeApp/src/desktopMain/kotlin/io/nisfeb/talon/ui/DesktopUiSettings.kt` | Persist new field to JSON |
| `composeApp/src/androidMain/kotlin/io/nisfeb/talon/ui/AndroidUiSettings.kt` | Persist new field to SharedPreferences |
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/StatusFeedScreen.kt` | Refactor screen wrapper to delegate to `StatusFeedList` |
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/BookmarksScreen.kt` | Refactor screen wrapper to delegate to `BookmarksList` |
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/ActivityFeedScreen.kt` | Refactor screen wrapper to delegate to `ActivityList` |
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/compose/App.kt` | Replace `ChatPaneScaffold(...)` call with `DesktopShell(...)`. Wire kebab-menu items to set `activeRailTab` on wide and existing flags on compact. |
| `RELEASE.md` | Add Phase 2 smoke checklist entries |
| `composeApp/build.gradle.kts` | Bump `talonVersionCode` / `talonVersionName` to `0.9.0-rc3` |

---

## Phase 1 — Persistence + types

### Task 1.1: `RailTab` enum

**Files:**
- Create: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/RailTab.kt`

- [ ] **Step 1: Write the file**

```kotlin
package io.nisfeb.talon.ui

/**
 * Surfaces that the desktop / tablet-landscape rail can switch the
 * left pane between. Order matches the rail's icon stacking order
 * (top to bottom). Adding a fifth value here is the single point of
 * change for landing a new rail surface — `DesktopShell`'s list
 * resolver and the rail icon list both fan out from this enum.
 */
enum class RailTab { Chats, Statuses, Bookmarks, Activity }

/**
 * Persistence helper. `RailTab.valueOf(name)` throws on unknown
 * names; this version falls back to [Chats] on any parse failure
 * (corrupt JSON / SharedPrefs entry, removed enum value, etc.) so
 * a bad value on disk never blows up the app.
 */
fun railTabOrDefault(name: String?): RailTab {
    if (name.isNullOrBlank()) return RailTab.Chats
    return runCatching { RailTab.valueOf(name) }.getOrDefault(RailTab.Chats)
}
```

- [ ] **Step 2: Compile**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :composeApp:compileKotlinDesktop`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/RailTab.kt
git commit -m "ui: RailTab enum + railTabOrDefault parse-fallback helper"
```

---

### Task 1.2: `RailTab` round-trip tests

**Files:**
- Create: `composeApp/src/commonTest/kotlin/io/nisfeb/talon/ui/RailTabTest.kt`

- [ ] **Step 1: Write the tests**

```kotlin
package io.nisfeb.talon.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class RailTabTest {

    @Test
    fun `name + railTabOrDefault round-trips every value`() {
        for (tab in RailTab.entries) {
            assertEquals(tab, railTabOrDefault(tab.name))
        }
    }

    @Test
    fun `null name falls back to Chats`() {
        assertEquals(RailTab.Chats, railTabOrDefault(null))
    }

    @Test
    fun `blank name falls back to Chats`() {
        assertEquals(RailTab.Chats, railTabOrDefault(""))
        assertEquals(RailTab.Chats, railTabOrDefault("   "))
    }

    @Test
    fun `unknown name falls back to Chats`() {
        // Future-proofing: removing or renaming a tab on disk shouldn't
        // crash on next launch; default to the safe home tab instead.
        assertEquals(RailTab.Chats, railTabOrDefault("Profile"))
        assertEquals(RailTab.Chats, railTabOrDefault("nope"))
    }
}
```

- [ ] **Step 2: Run tests**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :composeApp:desktopTest --tests 'io.nisfeb.talon.ui.RailTabTest'`
Expected: 4 tests pass.

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/commonTest/kotlin/io/nisfeb/talon/ui/RailTabTest.kt
git commit -m "test(ui): RailTab name round-trip + parse-fallback"
```

---

### Task 1.3: Add `activeRailTab` to `UiSettings` interface + `InMemoryUiSettings`

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/UiSettings.kt`

- [ ] **Step 1: Add to the interface**

After the existing `chatPaneListFraction` declaration in the interface body (search for `chatPaneListFraction: StateFlow<Float>`):

```kotlin
/**
 * Which surface the desktop / tablet-landscape rail has selected for
 * the left pane. Default [RailTab.Chats]. Persists per ship across
 * launches alongside the other UI prefs. Mobile (<840dp) ignores
 * this value — the kebab menu drives mobile nav directly via the
 * existing `showStatusFeed` / `showBookmarks` / `showActivity`
 * flags. See `DesktopShell` for the wide-pane consumer.
 */
val activeRailTab: StateFlow<RailTab>
fun setActiveRailTab(tab: RailTab)
```

- [ ] **Step 2: Add to `InMemoryUiSettings`**

Inside `InMemoryUiSettings`, after the `_chatPaneListFraction` block:

```kotlin
private val _activeRailTab = MutableStateFlow(initialActiveRailTab)
override val activeRailTab: StateFlow<RailTab> =
    _activeRailTab.asStateFlow()
override fun setActiveRailTab(tab: RailTab) {
    _activeRailTab.value = tab
}
```

Add a corresponding ctor parameter:

```kotlin
class InMemoryUiSettings(
    initialHide: Boolean = false,
    initialAccent: AccentSettings = AccentSettings(),
    initialGroupOrder: GroupChannelOrder = GroupChannelOrder.Recent,
    initialChatPaneListFraction: Float = 0.30f,
    initialActiveRailTab: RailTab = RailTab.Chats,
) : UiSettings { ... }
```

- [ ] **Step 2.5: Inspect compile errors**

The `DesktopUiSettings` and `AndroidUiSettings` impls are now non-compliant. We'll fix both in 1.4 and 1.5; do NOT compile yet — the failing build is expected.

---

### Task 1.4: `DesktopUiSettings` persists `activeRailTab`

**Files:**
- Modify: `composeApp/src/desktopMain/kotlin/io/nisfeb/talon/ui/DesktopUiSettings.kt`

- [ ] **Step 1: Read the file to find the persisted snapshot data class**

Run: `grep -n 'data class\|chatPaneListFraction\|persistCurrent' composeApp/src/desktopMain/kotlin/io/nisfeb/talon/ui/DesktopUiSettings.kt`
Expected: shows the `Persisted` data class definition + the `persistCurrent()` method + the `chatPaneListFraction` field. Note their line numbers.

- [ ] **Step 2: Add the new field to the persisted snapshot**

In the persisted `data class` definition, add a string field after `chatPaneListFraction`:

```kotlin
val chatPaneListFraction: Float = 0.30f,
val activeRailTab: String = RailTab.Chats.name,
```

Stored as the enum's `name` string — same pattern as `groupChannelOrder` (which already lives in the same data class as a string). Keeps the JSON file human-readable and doesn't bind the persistence format to the enum's ordinal.

- [ ] **Step 3: Wire the StateFlow + setter**

After the `_chatPaneListFraction` block:

```kotlin
private val _activeRailTab = MutableStateFlow(
    railTabOrDefault(initial.activeRailTab),
)
override val activeRailTab: StateFlow<RailTab> =
    _activeRailTab.asStateFlow()
override fun setActiveRailTab(tab: RailTab) {
    if (_activeRailTab.value == tab) return
    _activeRailTab.value = tab
    persistCurrent()
}
```

- [ ] **Step 4: Update `persistCurrent()`**

Find the `persistCurrent()` method body. It builds a fresh `Persisted(...)` instance from the current StateFlow values. Add `activeRailTab = _activeRailTab.value.name` to the constructor invocation.

- [ ] **Step 5: Compile both targets**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :composeApp:compileKotlinDesktop`
Expected: BUILD SUCCESSFUL on desktop.
Then: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :composeApp:compileDebugKotlinAndroid`
Expected: FAILS — `AndroidUiSettings` doesn't implement the new interface members yet. That's fixed in 1.5.

---

### Task 1.5: `AndroidUiSettings` persists `activeRailTab`

**Files:**
- Modify: `composeApp/src/androidMain/kotlin/io/nisfeb/talon/ui/AndroidUiSettings.kt`

- [ ] **Step 1: Read the file to find the existing pattern**

Run: `grep -n 'KEY_\|getFloat\|getString\|chatPaneListFraction' composeApp/src/androidMain/kotlin/io/nisfeb/talon/ui/AndroidUiSettings.kt`
Expected: shows the existing `KEY_*` constants in the companion / file-private scope and the `chatPaneListFraction` block.

- [ ] **Step 2: Add a key constant**

Add to the same scope as the other `KEY_*` constants:

```kotlin
private const val KEY_ACTIVE_RAIL_TAB = "active_rail_tab"
```

- [ ] **Step 3: Wire StateFlow + setter**

After the existing `_chatPaneListFraction` block:

```kotlin
private val _activeRailTab = MutableStateFlow(
    railTabOrDefault(prefs.getString(KEY_ACTIVE_RAIL_TAB, null)),
)
override val activeRailTab: StateFlow<RailTab> =
    _activeRailTab.asStateFlow()
override fun setActiveRailTab(tab: RailTab) {
    if (_activeRailTab.value == tab) return
    prefs.edit().putString(KEY_ACTIVE_RAIL_TAB, tab.name).apply()
    _activeRailTab.value = tab
}
```

- [ ] **Step 4: Compile both targets + run tests**

```
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :composeApp:compileKotlinDesktop :composeApp:compileDebugKotlinAndroid :composeApp:desktopTest
```
Expected: BUILD SUCCESSFUL, all existing tests still pass (only new tests added so far are RailTabTest's 4 cases).

- [ ] **Step 5: Commit Tasks 1.3 + 1.4 + 1.5 together**

These three depend on each other and the codebase doesn't compile in between. One commit:

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/UiSettings.kt \
        composeApp/src/desktopMain/kotlin/io/nisfeb/talon/ui/DesktopUiSettings.kt \
        composeApp/src/androidMain/kotlin/io/nisfeb/talon/ui/AndroidUiSettings.kt
git commit -m "ui: activeRailTab in UiSettings (interface + Desktop + Android impls)"
```

---

### Task 1.6: Round-trip test for the desktop persistence

**Files:**
- Create: `composeApp/src/desktopTest/kotlin/io/nisfeb/talon/ui/DesktopUiSettingsActiveRailTabTest.kt`

- [ ] **Step 1: Write the test**

```kotlin
package io.nisfeb.talon.ui

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopUiSettingsActiveRailTabTest {

    private lateinit var tmpDir: File
    private lateinit var jsonFile: File

    @BeforeTest
    fun setUp() {
        tmpDir = createTempDirectory(prefix = "talon-rail-tab-test-").toFile()
        jsonFile = File(tmpDir, "ui-settings.json")
    }

    @AfterTest
    fun tearDown() {
        tmpDir.deleteRecursively()
    }

    @Test
    fun `set then re-read round-trips`() = runBlocking {
        val first = DesktopUiSettings(file = jsonFile)
        // Default on a fresh file is Chats.
        assertEquals(RailTab.Chats, first.activeRailTab.value)
        first.setActiveRailTab(RailTab.Bookmarks)
        // Construct a fresh instance over the same file — should read
        // back what we just wrote.
        val second = DesktopUiSettings(file = jsonFile)
        assertEquals(RailTab.Bookmarks, second.activeRailTab.value)
    }

    @Test
    fun `corrupt enum value falls back to Chats`() {
        // Write a JSON file with a deliberately bad activeRailTab
        // string (a removed / typo'd value). The store should not
        // throw and should hand back Chats.
        jsonFile.writeText(
            """{
                "hideComposerButtons": false,
                "groupChannelOrder": "Recent",
                "chatPaneListFraction": 0.3,
                "activeRailTab": "ProfileEdit"
            }""".trimIndent(),
        )
        val s = DesktopUiSettings(file = jsonFile)
        assertEquals(RailTab.Chats, s.activeRailTab.value)
    }

    @Test
    fun `setting same value twice is idempotent`() = runBlocking {
        val s = DesktopUiSettings(file = jsonFile)
        s.setActiveRailTab(RailTab.Activity)
        val mtime = jsonFile.lastModified()
        Thread.sleep(15)
        s.setActiveRailTab(RailTab.Activity)
        // Same value → setter is a no-op and skips persisting. The
        // mtime should be unchanged. (Without the early-return guard
        // every re-render would rewrite the file.)
        assertEquals(mtime, jsonFile.lastModified())
    }
}
```

- [ ] **Step 2: Run the tests**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :composeApp:desktopTest --tests 'io.nisfeb.talon.ui.DesktopUiSettingsActiveRailTabTest'`
Expected: 3 tests pass.

If the existing `DesktopUiSettings` constructor takes the file as a positional rather than `file = ...` named arg, adapt the test calls; the assertion bodies don't change.

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/desktopTest/kotlin/io/nisfeb/talon/ui/DesktopUiSettingsActiveRailTabTest.kt
git commit -m "test(ui): DesktopUiSettings activeRailTab round-trip + corrupt-value fallback"
```

---

## Phase 2 — Extract list bodies

The pattern is identical for all three screens:

1. Create `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/<Surface>List.kt` with the list body (no header, no `onBack`).
2. The existing `<Surface>Screen` keeps its public signature; its body becomes a `Column { TopAppBar(...); <Surface>List(...) }`.
3. No `embedded: Boolean` parameter — extraction, not parameterisation.

Each task ships its own commit so the mobile path is verifiable independently after each one.

### Task 2.1: Extract `StatusFeedList`

**Files:**
- Create: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/StatusFeedList.kt`
- Modify: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/StatusFeedScreen.kt`

- [ ] **Step 1: Read the existing screen to identify the list body**

Run: `wc -l composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/StatusFeedScreen.kt`
Expected: ~250 lines.

The current shape is:

```kotlin
@Composable
fun StatusFeedScreen(
    db: AppDatabase,
    repo: TlonChatRepo,
    ourPatp: String,
    onBack: () -> Unit,
    onOpenContact: (ship: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
        TopAppBar(...)        // ← header (back + title)
        SelfStatusRow(...)    // ← inline self status
        LazyColumn { ... }    // ← rows
        EditStatusDialog(...) // ← dialog
    }
}
```

- [ ] **Step 2: Create `StatusFeedList.kt` with the body, minus the header**

```kotlin
package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.urbit.TlonChatRepo
import kotlinx.coroutines.launch

/**
 * The list body of [StatusFeedScreen], extracted so the desktop /
 * tablet-landscape rail can render it without the screen-level
 * header. Mobile + compact-mode wide go through [StatusFeedScreen]
 * which wraps this with the existing TopAppBar + back-arrow.
 */
@Composable
fun StatusFeedList(
    db: AppDatabase,
    repo: TlonChatRepo,
    ourPatp: String,
    onOpenContact: (ship: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val contacts by remember {
        db.contacts().streamStatusFeed()
    }.collectAsState(initial = emptyList())
    val self by remember(ourPatp) {
        db.contacts().streamOne(ourPatp)
    }.collectAsState(initial = null)
    var editing by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize()) {
        SelfStatusRow(self = self, onEdit = { editing = true })
        if (contacts.isEmpty()) {
            Text(
                "No statuses yet. They'll show up here as your contacts " +
                    "update theirs.",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(contacts, key = { it.ship }) { c ->
                    StatusRow(c) { onOpenContact(c.ship) }
                }
            }
        }
    }
    if (editing) {
        EditStatusDialog(
            initial = self?.status.orEmpty(),
            onDismiss = { editing = false },
            onSave = { next ->
                editing = false
                scope.launch {
                    runCatching { repo.updateProfile(status = next) }
                }
            },
        )
    }
}
```

**IMPORTANT:** the `SelfStatusRow`, `StatusRow`, and `EditStatusDialog` composables live in `StatusFeedScreen.kt` today as `private`. Step 3 widens their visibility to `internal` so `StatusFeedList` can call them from the same package without inlining their bodies twice.

- [ ] **Step 3: Refactor `StatusFeedScreen` to delegate to `StatusFeedList`**

In `StatusFeedScreen.kt`:

1. Drop the `private` qualifier on `SelfStatusRow`, `StatusRow`, `EditStatusDialog` (`private` → `internal`).
2. Remove the body composables that `StatusFeedList` now owns (`var editing`, the `LazyColumn`, the conditional `EditStatusDialog`). Keep the `onBack` arrow and `Text("Statuses")` header.
3. Replace the body with a `StatusFeedList(...)` call passing through the same params (minus `onBack`).

The result is a thin wrapper:

```kotlin
@Composable
fun StatusFeedScreen(
    db: AppDatabase,
    repo: TlonChatRepo,
    ourPatp: String,
    onBack: () -> Unit,
    onOpenContact: (ship: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                "Statuses",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        StatusFeedList(
            db = db,
            repo = repo,
            ourPatp = ourPatp,
            onOpenContact = onOpenContact,
        )
    }
}
```

The exact header markup may differ from the snippet above — match what the file already has byte-for-byte. The contract is: same TopAppBar / Row / IconButton / Text the user sees today, then the list body delegated.

- [ ] **Step 4: Compile + smoke**

```
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :composeApp:compileKotlinDesktop :composeApp:compileDebugKotlinAndroid :composeApp:desktopTest
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/StatusFeedList.kt \
        composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/StatusFeedScreen.kt
git commit -m "ui(screens): extract StatusFeedList from StatusFeedScreen"
```

---

### Task 2.2: Extract `BookmarksList`

Same pattern as 2.1.

**Files:**
- Create: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/BookmarksList.kt`
- Modify: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/BookmarksScreen.kt`

- [ ] **Step 1: Read the existing screen**

Run: `wc -l composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/BookmarksScreen.kt`
Expected: ~440 lines (this one's bigger; folder selector + multiple sub-composables).

The body shape is `Column { TopAppBar; FolderRow; LazyColumn; Dialogs… }`.

- [ ] **Step 2: Create `BookmarksList.kt`**

The body composable holds everything below the TopAppBar in `BookmarksScreen`. Copy the entire body region (folder chips, folder management dialogs, the LazyColumn of bookmark rows, and the create/edit/delete dialogs) into a new `@Composable fun BookmarksList(...)`. Param list:

```kotlin
@Composable
fun BookmarksList(
    db: AppDatabase,
    repo: TlonChatRepo,
    onOpenConversation: (whom: String) -> Unit,
    modifier: Modifier = Modifier,
)
```

(Drop `onBack` only — every other parameter that the current screen takes carries through unchanged.)

Like 2.1, change any `private` helper composables called from this body to `internal` so the new file can reuse them without duplicating code.

- [ ] **Step 3: Refactor `BookmarksScreen` to delegate**

`BookmarksScreen` keeps its existing public signature and renders only:

1. The header `Row { IconButton(onClick = onBack); Text("Bookmarks"); … }` exactly as today.
2. `BookmarksList(...)` passing through every non-`onBack` parameter.

- [ ] **Step 4: Compile + test**

```
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :composeApp:compileKotlinDesktop :composeApp:compileDebugKotlinAndroid :composeApp:desktopTest
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/BookmarksList.kt \
        composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/BookmarksScreen.kt
git commit -m "ui(screens): extract BookmarksList from BookmarksScreen"
```

---

### Task 2.3: Extract `ActivityList`

Same pattern as 2.1 / 2.2.

**Files:**
- Create: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/ActivityList.kt`
- Modify: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/ActivityFeedScreen.kt`

- [ ] **Step 1: Read the existing screen**

Run: `wc -l composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/ActivityFeedScreen.kt`
Expected: ~200-300 lines.

- [ ] **Step 2: Create `ActivityList.kt`**

Param list:

```kotlin
@Composable
fun ActivityList(
    db: AppDatabase,
    repo: TlonChatRepo,
    onOpenConversation: (whom: String) -> Unit,
    onOpenReply: (whom: String, parentId: String, replyId: String) -> Unit = { w, _, _ -> onOpenConversation(w) },
    modifier: Modifier = Modifier,
)
```

(Drop `onBack`. Every other parameter passes through.)

- [ ] **Step 3: Refactor `ActivityFeedScreen` to delegate**

Same shape as 2.1 / 2.2: header + `ActivityList(...)`.

- [ ] **Step 4: Compile + test**

```
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :composeApp:compileKotlinDesktop :composeApp:compileDebugKotlinAndroid :composeApp:desktopTest
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/ActivityList.kt \
        composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/ActivityFeedScreen.kt
git commit -m "ui(screens): extract ActivityList from ActivityFeedScreen"
```

---

## Phase 3 — `DesktopShell` + rail

### Task 3.1: Create `DesktopShell` (no rail icons yet, just the structure)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/DesktopShell.kt`

- [ ] **Step 1: Write the file**

```kotlin
package io.nisfeb.talon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * Desktop / tablet-landscape host. Below [ExpandedThreshold] this is a
 * passthrough: we render only [detail] when set, otherwise [list] —
 * matching the stack-style mobile flow Phase 1 already established.
 *
 * At/above the threshold we add a 64dp vertical icon rail on the far
 * left, then defer to [ChatPaneScaffold] for the list / detail split
 * with its drag handle. A reserved [rightSidebar] slot is null in
 * Phase 2; Phase 3 will fill it with thread / files / links / pinned-
 * post panels.
 *
 * The rail is rendered ONLY inside the expanded branch — never as a
 * sibling of [ChatPaneScaffold] — so a compact-mode resize doesn't
 * leave a dangling 64dp gutter on phones / narrow desktop windows.
 */
@Composable
fun DesktopShell(
    activeRailTab: RailTab,
    onSelectRailTab: (RailTab) -> Unit,
    list: @Composable () -> Unit,
    detail: (@Composable () -> Unit)?,
    listFraction: Float,
    onListFractionChange: (Float) -> Unit,
    rightSidebar: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val expanded = maxWidth >= ExpandedThreshold
        if (!expanded) {
            // Compact: rail/sidebar collapsed; identical to Phase 1.
            if (detail != null) detail() else list()
            return@BoxWithConstraints
        }
        Row(modifier = Modifier.fillMaxSize()) {
            DesktopRail(
                activeTab = activeRailTab,
                onSelect = onSelectRailTab,
            )
            Box(modifier = Modifier.fillMaxHeight().fillMaxSize()) {
                ChatPaneScaffold(
                    list = list,
                    detail = detail,
                    listFraction = listFraction,
                    onListFractionChange = onListFractionChange,
                )
            }
            // Phase 3: rightSidebar?.invoke() once we wire threads /
            // files / links here. Today rightSidebar stays null and
            // we don't render an empty fourth column.
        }
    }
}

@Composable
private fun DesktopRail(
    activeTab: RailTab,
    onSelect: (RailTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxHeight().width(RAIL_WIDTH),
    ) {
        Column(
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxHeight().padding(vertical = 8.dp),
        ) {
            for (tab in RailTab.entries) {
                RailIconButton(
                    tab = tab,
                    isSelected = tab == activeTab,
                    onClick = { onSelect(tab) },
                )
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun RailIconButton(
    tab: RailTab,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (isSelected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant
    IconButton(onClick = onClick) {
        Icon(
            imageVector = railIcon(tab),
            contentDescription = railLabel(tab),
            tint = tint,
        )
    }
}

private fun railIcon(tab: RailTab): ImageVector = when (tab) {
    // All four icons live in material-icons-core (always shipped) so
    // they're outside the slim-task's keep-list and don't risk an R8
    // strip on Android. (Android phones won't render the rail anyway,
    // but the tablet build does.)
    RailTab.Chats -> Icons.Filled.Home
    RailTab.Statuses -> Icons.Filled.Notifications
    RailTab.Bookmarks -> Icons.Filled.Star
    RailTab.Activity -> Icons.Filled.Notifications  // placeholder — pick distinct in 3.2
}

private fun railLabel(tab: RailTab): String = when (tab) {
    RailTab.Chats -> "Chats"
    RailTab.Statuses -> "Statuses"
    RailTab.Bookmarks -> "Bookmarks"
    RailTab.Activity -> "Activity"
}

private val RAIL_WIDTH = 64.dp
```

`Icons.Filled.Notifications` is used twice in this skeleton — Step 3.2 picks distinct icons that all exist in `material-icons-core` (so they don't tangle with the existing strip keep-list).

- [ ] **Step 2: Compile**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :composeApp:compileKotlinDesktop :composeApp:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/DesktopShell.kt
git commit -m "ui: DesktopShell — rail + ChatPaneScaffold host (Phase 2 skeleton)"
```

---

### Task 3.2: Pick distinct icons + add tooltips

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/DesktopShell.kt`

- [ ] **Step 1: Pick four distinct core icons**

The Compose `material-icons-core` artifact ships about 20 icons. Confirm which by greping a recent JAR:

```
find ~/.gradle/caches -name 'material-icons-core-desktop-*.jar' -print -quit \
  | xargs -I {} unzip -l {} \
  | grep '/filled/.*Kt\.class$' | sort
```

From the output, four that read sensibly for our four tabs:

- `RailTab.Chats` → `Icons.Filled.Home`
- `RailTab.Statuses` → `Icons.Filled.Person`
- `RailTab.Bookmarks` → `Icons.Filled.Star`
- `RailTab.Activity` → `Icons.Filled.Notifications`

(All four are documented as core icons. If `Person` somehow isn't present in the cached JAR for the user's Compose version, swap to `Icons.Filled.Favorite` or another core icon — the rule is just "no extended-only icons or the strip catches us.")

- [ ] **Step 2: Apply the picks + wrap in `BasicTooltipBox`**

Update `railIcon` and `RailIconButton`:

```kotlin
import androidx.compose.foundation.BasicTooltipBox
import androidx.compose.foundation.TooltipState
import androidx.compose.foundation.rememberBasicTooltipState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star

private fun railIcon(tab: RailTab): ImageVector = when (tab) {
    RailTab.Chats -> Icons.Filled.Home
    RailTab.Statuses -> Icons.Filled.Person
    RailTab.Bookmarks -> Icons.Filled.Star
    RailTab.Activity -> Icons.Filled.Notifications
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RailIconButton(
    tab: RailTab,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (isSelected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant
    val tooltipState = rememberBasicTooltipState(isPersistent = false)
    BasicTooltipBox(
        positionProvider = androidx.compose.ui.window.rememberCursorPositionProvider(),
        tooltip = {
            Surface(
                color = MaterialTheme.colorScheme.inverseSurface,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    railLabel(tab),
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        },
        state = tooltipState,
    ) {
        IconButton(onClick = onClick) {
            Icon(
                imageVector = railIcon(tab),
                contentDescription = railLabel(tab),
                tint = tint,
            )
        }
    }
}
```

If `BasicTooltipBox` isn't in the Compose Multiplatform version on this branch (it lands in 1.6+, we're on 1.7), check the import path. Falls back gracefully: if the tooltip surface doesn't render, the `contentDescription` on `Icon` still gives screen readers the label.

- [ ] **Step 3: Compile**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :composeApp:compileKotlinDesktop :composeApp:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL.

If the build fails because a chosen icon isn't actually in core, swap it for another one from the JAR listing in Step 1 and retry. Note the swap in the commit message.

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/DesktopShell.kt
git commit -m "ui(rail): distinct icons + cursor-anchored tooltips"
```

---

### Task 3.3: Compose UI tests for `DesktopShell`

**Files:**
- Create: `composeApp/src/desktopTest/kotlin/io/nisfeb/talon/ui/DesktopShellTest.kt`

- [ ] **Step 1: Write the tests**

```kotlin
package io.nisfeb.talon.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopShellTest {

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `narrow window does not render rail`() = runComposeUiTest {
        setContent {
            Box(Modifier.size(width = 600.dp, height = 800.dp)) {
                DesktopShell(
                    activeRailTab = RailTab.Chats,
                    onSelectRailTab = {},
                    list = { Text("LIST") },
                    detail = { Text("DETAIL") },
                    listFraction = 0.30f,
                    onListFractionChange = {},
                )
            }
        }
        // Compact: rail icon labels (used as contentDescription) don't render.
        onNodeWithContentDescription("Statuses").assertDoesNotExist()
        onNodeWithContentDescription("Bookmarks").assertDoesNotExist()
        onNodeWithContentDescription("Activity").assertDoesNotExist()
        // Detail wins on compact when set.
        onNodeWithText("DETAIL").assertExists()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `wide window renders rail with all four icons`() = runComposeUiTest {
        setContent {
            Box(Modifier.size(width = 1200.dp, height = 800.dp)) {
                DesktopShell(
                    activeRailTab = RailTab.Chats,
                    onSelectRailTab = {},
                    list = { Text("LIST") },
                    detail = { Text("DETAIL") },
                    listFraction = 0.30f,
                    onListFractionChange = {},
                )
            }
        }
        onNodeWithContentDescription("Chats").assertExists()
        onNodeWithContentDescription("Statuses").assertExists()
        onNodeWithContentDescription("Bookmarks").assertExists()
        onNodeWithContentDescription("Activity").assertExists()
        onNodeWithText("LIST").assertExists()
        onNodeWithText("DETAIL").assertExists()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `tapping a rail icon fires onSelectRailTab with the tapped tab`() =
        runComposeUiTest {
            var lastSelected: RailTab? = null
            setContent {
                Box(Modifier.size(width = 1200.dp, height = 800.dp)) {
                    DesktopShell(
                        activeRailTab = RailTab.Chats,
                        onSelectRailTab = { lastSelected = it },
                        list = { Text("LIST") },
                        detail = null,
                        listFraction = 0.30f,
                        onListFractionChange = {},
                    )
                }
            }
            onNodeWithContentDescription("Bookmarks").performClick()
            assertEquals(RailTab.Bookmarks, lastSelected)
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `wide window with null rightSidebar does not affect layout`() =
        runComposeUiTest {
            // Forward-compat guard: the Phase 3 sidebar slot is reserved
            // but defaults to null. A null slot must not consume any
            // horizontal space (no empty fourth column).
            setContent {
                Box(Modifier.size(width = 1200.dp, height = 800.dp)) {
                    DesktopShell(
                        activeRailTab = RailTab.Chats,
                        onSelectRailTab = {},
                        list = { Text("LIST") },
                        detail = { Text("DETAIL") },
                        listFraction = 0.30f,
                        onListFractionChange = {},
                        rightSidebar = null,
                    )
                }
            }
            onNodeWithText("LIST").assertExists()
            onNodeWithText("DETAIL").assertExists()
        }
}
```

- [ ] **Step 2: Run the tests**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :composeApp:desktopTest --tests 'io.nisfeb.talon.ui.DesktopShellTest'`
Expected: 4 tests pass.

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/desktopTest/kotlin/io/nisfeb/talon/ui/DesktopShellTest.kt
git commit -m "test(ui): DesktopShell rail + breakpoint behaviour"
```

---

## Phase 4 — App.kt integration

### Task 4.1: Swap `ChatPaneScaffold(...)` → `DesktopShell(...)` and wire the list resolver

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/compose/App.kt`

- [ ] **Step 1: Find the existing scaffold call site**

Run: `grep -n 'ChatPaneScaffold(' composeApp/src/commonMain/kotlin/io/nisfeb/talon/compose/App.kt`
Expected: one or two matches (the call inside the navigation `else` branch and an import).

- [ ] **Step 2: Add a ship-aware `activeRailTab` reader**

Near where the other `uiSettings` flows are collected (search for `chatPaneListFraction` to locate the sibling collectAsState block):

```kotlin
val activeRailTab by uiSettings.activeRailTab.collectAsState()
```

- [ ] **Step 3: Build the list-slot resolver**

Above the existing `ChatPaneScaffold(...)` call, declare a list-slot lambda that picks the right composable for the active tab:

```kotlin
val railListSlot: @Composable () -> Unit = {
    when (activeRailTab) {
        RailTab.Chats -> {
            // existing DmListScreen(...) call expression goes here —
            // copy from the current ChatPaneScaffold(list = { DmListScreen(...) }) site.
        }
        RailTab.Statuses -> StatusFeedList(
            db = db,
            repo = repo,
            ourPatp = ourPatp,
            // Whatever lambda the existing StatusFeedScreen call site
            // passes for `onOpenContact` — search the file for
            // `StatusFeedScreen(` and copy that lambda's body verbatim
            // into here. Today it routes to a profile-edit / contact
            // profile flow; the rail uses the same handler so the
            // user gets the same destination from either entry point.
            onOpenContact = { ship ->
                /* paste the existing onOpenContact lambda body */
            },
        )
        RailTab.Bookmarks -> BookmarksList(
            db = db,
            repo = repo,
            onOpenConversation = { whom -> openChat = whom },
        )
        RailTab.Activity -> ActivityList(
            db = db,
            repo = repo,
            onOpenConversation = { whom -> openChat = whom },
            onOpenReply = { whom, parentId, _ ->
                openChat = whom
                openThreadParent = parentId
            },
        )
    }
}
```

The `RailTab.Statuses → onOpenContact` callback — match what the existing `StatusFeedScreen` callsite hands in. If `onOpenContact` today routes through a `showContactProfile` flag or similar, reuse the same flag-flip here. **Read App.kt's existing call site for `StatusFeedScreen` and use the same handler.**

- [ ] **Step 4: Replace the `ChatPaneScaffold(...)` call with `DesktopShell(...)`**

```kotlin
DesktopShell(
    activeRailTab = activeRailTab,
    onSelectRailTab = { uiSettings.setActiveRailTab(it) },
    list = railListSlot,
    detail = detailSlot,
    listFraction = listFraction,
    onListFractionChange = { uiSettings.setChatPaneListFraction(it) },
)
```

- [ ] **Step 5: Add the import**

`import io.nisfeb.talon.ui.DesktopShell` at the top of the file.
`import io.nisfeb.talon.ui.RailTab` likewise.
`import io.nisfeb.talon.ui.screens.StatusFeedList` / `BookmarksList` / `ActivityList` likewise.

- [ ] **Step 6: Compile both targets + run tests**

```
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :composeApp:compileKotlinDesktop :composeApp:compileDebugKotlinAndroid :composeApp:desktopTest
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/compose/App.kt
git commit -m "ui(app): host the list pane in DesktopShell with rail-driven tab resolver"
```

---

### Task 4.2: Wire kebab menu to flip `activeRailTab` on wide, existing flags on compact

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/compose/App.kt`

- [ ] **Step 1: Find the kebab menu `onOpenStatusFeed` / `onOpenBookmarks` / `onOpenActivity` handlers**

Run: `grep -n 'onOpenStatusFeed\|onOpenBookmarks\|onOpenActivity' composeApp/src/commonMain/kotlin/io/nisfeb/talon/compose/App.kt`
Expected: 3-6 matches (the lambdas + the param names where DmListScreen is called).

- [ ] **Step 2: Wrap the navigation block in `BoxWithConstraints` so App.kt knows its width**

`DesktopShell` already does its own `BoxWithConstraints` for its layout, but the kebab handlers fire BEFORE `DesktopShell` has a chance to redirect. We need App.kt itself to know the width when it's building the handler lambdas, otherwise we'd have to plumb routing through DesktopShell's API.

Find the navigation `when {}` block in App.kt's body (where `DesktopShell(...)` is now called from Task 4.1). Wrap it in:

```kotlin
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import io.nisfeb.talon.ui.ExpandedThreshold

BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    val expanded = maxWidth >= ExpandedThreshold
    // … existing navigation when {} block …
    // … DesktopShell(...) call inside the `else` branch …
}
```

Both `App.kt` and `DesktopShell` now consult `maxWidth >= ExpandedThreshold` against the same window — they always agree. This is intentional duplication; the alternative (DesktopShell publishing its expanded state via a callback) is more API surface than the value justifies.

- [ ] **Step 3: Build the kebab router lambdas using `expanded`**

Inside the `BoxWithConstraints` block, ABOVE the existing `when {}` navigation:

```kotlin
// Mirror DesktopShell's threshold so the kebab menu makes the right
// navigation move at this breakpoint. Mobile / compact (<840dp):
// flip the existing show* flag (full-screen replace, with back
// arrow). Wide (≥840dp): switch the rail tab instead — the rail is
// visible so no full-screen replace is needed.
val onOpenStatusFeed: () -> Unit = {
    if (expanded) uiSettings.setActiveRailTab(RailTab.Statuses)
    else showStatusFeed = true
}
val onOpenBookmarks: () -> Unit = {
    if (expanded) uiSettings.setActiveRailTab(RailTab.Bookmarks)
    else showBookmarks = true
}
val onOpenActivity: () -> Unit = {
    if (expanded) uiSettings.setActiveRailTab(RailTab.Activity)
    else showActivity = true
}
```

- [ ] **Step 4: Update the `DmListScreen(...)` call site**

Find the `DmListScreen(...)` invocation. Replace:

```kotlin
onOpenStatusFeed = { showStatusFeed = true },
onOpenBookmarks = { showBookmarks = true },
onOpenActivity = { showActivity = true },
```

with:

```kotlin
onOpenStatusFeed = onOpenStatusFeed,
onOpenBookmarks = onOpenBookmarks,
onOpenActivity = onOpenActivity,
```

(referencing the lambdas defined in Step 3).

- [ ] **Step 5: Compile + manual smoke**

```
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :composeApp:compileKotlinDesktop :composeApp:compileDebugKotlinAndroid :composeApp:desktopTest
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :composeApp:run
```

Manually verify (wide window):

- Open Talon → see chat list on the left, chat detail on the right.
- Tap the rail's Statuses icon → list pane swaps to the statuses feed; chat detail unchanged.
- Tap the rail's Chats icon → list pane returns to the chat list.
- Open the kebab → tap "Statuses" → behaves the same as tapping the rail icon (rail tab flips, no full-screen replace).
- Resize the window narrow → kebab → tap "Statuses" → full-screen `StatusFeedScreen` with back arrow (the existing mobile flow).

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/compose/App.kt
git commit -m "ui(app): kebab menu flips rail tab on wide, existing flag on compact"
```

---

## Phase 5 — Polish

### Task 5.1: Selected-state visual on the active rail icon

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/DesktopShell.kt`

- [ ] **Step 1: Read the current `RailIconButton`**

Run: `grep -n 'fun RailIconButton' composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/DesktopShell.kt`

Today the selected state is conveyed only by tinting the icon — fine but easy to miss. Add a small selection indicator: a vertical primary-coloured pill on the left edge, ~3dp wide × 24dp tall, only visible when `isSelected`.

- [ ] **Step 2: Wrap the IconButton in a Box with the indicator**

```kotlin
@Composable
private fun RailIconButton(
    tab: RailTab,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (isSelected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(24.dp)
                .background(
                    if (isSelected) MaterialTheme.colorScheme.primary
                    else androidx.compose.ui.graphics.Color.Transparent,
                ),
        )
        // existing tooltip+IconButton block
        BasicTooltipBox(
            // … same as Task 3.2 …
        ) {
            IconButton(onClick = onClick) {
                Icon(
                    imageVector = railIcon(tab),
                    contentDescription = railLabel(tab),
                    tint = tint,
                )
            }
        }
    }
}
```

- [ ] **Step 3: Compile**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :composeApp:compileKotlinDesktop`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/DesktopShell.kt
git commit -m "ui(rail): primary-colour pill marks the active tab"
```

---

### Task 5.2: RELEASE.md smoke checklist

**Files:**
- Modify: `RELEASE.md`

- [ ] **Step 1: Locate the existing desktop-split-pane smoke section**

Run: `grep -n 'Desktop split-pane smoke' RELEASE.md`
Expected: one match.

- [ ] **Step 2: Append the rail / Phase 2 entries to that section**

```markdown
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
```

- [ ] **Step 3: Commit**

```bash
git add RELEASE.md
git commit -m "docs(release): add Phase 2 rail smoke checklist"
```

---

### Task 5.3: Bump version + tag

**Files:**
- Modify: `composeApp/build.gradle.kts`

- [ ] **Step 1: Bump version**

Find the existing `talonVersionCode` / `talonVersionName` block (it's currently `66` / `0.9.0-rc2` post-rebase). Change to:

```kotlin
val talonVersionCode = 67
val talonVersionName = "0.9.0-rc3"
```

- [ ] **Step 2: Compile + run all tests + relay tests**

```
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :composeApp:assembleRelease :composeApp:desktopTest :relay:test
```
Expected: BUILD SUCCESSFUL on both, all tests pass.

If `:composeApp:assembleRelease` fails locally for jlink-toolchain reasons (the dev box may have a headless JDK without jmods), settle for `:composeApp:assembleDebug` here and let CI exercise the release path.

- [ ] **Step 3: Commit + push branch + tag**

```bash
git add composeApp/build.gradle.kts
git commit -m "release: 0.9.0-rc3 — desktop rail + secondary list-pane surfaces"
git tag -a v0.9.0-rc3 -m "Talon 0.9.0-rc3 — desktop rail (Chats/Statuses/Bookmarks/Activity)"
git push github desktop-split-pane
git push github v0.9.0-rc3
```

CI will build and publish the pre-release.

---

## Done criteria

- All five phases committed on the `desktop-split-pane` branch.
- Every checklist item in 5.2 manually verified on the rc3 AppImage.
- `desktopTest` and `commonTest` green on the rc3 commit.
- Mobile / compact builds verified unchanged: open the rc3 APK on a phone, walk through the kebab menu, hit Statuses / Bookmarks / Activity, confirm each renders full-screen with a back arrow exactly as before.

After validation, fast-forward to master, drop the `-rc3`, re-tag `v0.9.0`. Phase 1 + Phase 2 ship together as the 0.9.0 stable release.
