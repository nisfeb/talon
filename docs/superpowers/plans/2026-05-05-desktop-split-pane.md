# Desktop split-pane + keyboard shortcuts — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Render the chat list and chat detail side-by-side on windows ≥840dp (desktop and Android tablet landscape), with persisted per-ship last-open chat, drag-to-resize between panes, and a small set of keyboard shortcuts (Ctrl+K, Ctrl+N, Esc, Ctrl+,, Ctrl+1..9).

**Architecture:** A new `ChatPaneScaffold` composable in `commonMain/ui/` wraps the existing navigation `when {}` block in `App.kt`. It uses `BoxWithConstraints` to read window width — below 840dp it falls through to today's stack-style replace; at/above 840dp it places the list and detail in a `Row` with a 4dp drag handle between. State persistence lives behind a `LastOpenChatStore` interface (in-memory default + SharedPreferences/JSON impls per platform). The keyboard shortcut layer is a pure `keyEventToShortcut(event): ShortcutAction?` mapping in commonMain, wired to `onPreviewKeyEvent` on the Compose root focus host on both targets.

**Tech Stack:** Kotlin Multiplatform + Compose Multiplatform 1.7, Material3, kotlinx.coroutines.flow.StateFlow, kotlinx.serialization.json (existing JSON-file pattern), AndroidX SharedPreferences (Android side).

**Spec:** [`docs/superpowers/specs/2026-05-05-desktop-split-pane-design.md`](../specs/2026-05-05-desktop-split-pane-design.md)

---

## File structure

**Create:**

| Path | Responsibility |
|---|---|
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/ChatPaneScaffold.kt` | Layout host — breakpoint logic, list/detail row, drag-handle slot |
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/EmptyChatPane.kt` | Right-pane placeholder when no chat is selected |
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/KeyboardShortcuts.kt` | `ShortcutAction` sealed interface + `keyEventToShortcut()` pure mapping |
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/notify/LastOpenChatStore.kt` | Interface + InMemory default + Noop |
| `composeApp/src/androidMain/kotlin/io/nisfeb/talon/notify/AndroidLastOpenChatStore.kt` | SharedPreferences-backed impl |
| `composeApp/src/desktopMain/kotlin/io/nisfeb/talon/notify/DesktopLastOpenChatStore.kt` | JSON-file impl under `AppDirs.config / last-open-chat.json` |
| `composeApp/src/commonTest/kotlin/io/nisfeb/talon/ui/KeyboardShortcutsTest.kt` | Mapping table tests |
| `composeApp/src/commonTest/kotlin/io/nisfeb/talon/notify/LastOpenChatStoreTest.kt` | InMemory store behaviour |
| `composeApp/src/desktopTest/kotlin/io/nisfeb/talon/notify/DesktopLastOpenChatStoreTest.kt` | JSON file round-trip + atomic write |
| `composeApp/src/desktopTest/kotlin/io/nisfeb/talon/ui/ChatPaneScaffoldTest.kt` | Compose UI test for breakpoint behaviour |

**Modify:**

| Path | What |
|---|---|
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/UiSettings.kt` | Add `chatPaneListFraction: StateFlow<Float>` + `setChatPaneListFraction(value: Float)` to interface and `InMemoryUiSettings` |
| `composeApp/src/desktopMain/kotlin/io/nisfeb/talon/ui/DesktopUiSettings.kt` | Persist new field to JSON |
| `composeApp/src/androidMain/kotlin/io/nisfeb/talon/ui/AndroidUiSettings.kt` | Persist new field to SharedPreferences |
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/compose/App.kt` | Wrap navigation `when {}` with `ChatPaneScaffold`, accept `lastOpenChatStore`, add `focusSearchRequest` + `showNewDmRequest` flags, hook `onPreviewKeyEvent` |
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/DmListScreen.kt` | Consume `focusSearchRequest` + `showNewDmRequest` flags via `LaunchedEffect` |
| `composeApp/src/desktopMain/kotlin/io/nisfeb/talon/compose/Main.kt` | Wire `DesktopLastOpenChatStore` into the App graph |
| `composeApp/src/androidMain/kotlin/io/nisfeb/talon/MainActivity.kt` | Wire `AndroidLastOpenChatStore` into the App entry |

**Companion bump:**

| Path | What |
|---|---|
| `composeApp/build.gradle.kts` | `talonVersionCode` + `talonVersionName` bump when ready to release |

---

## Phase 1 — Scaffold + breakpoint

### Task 1.1: Create `EmptyChatPane`

The placeholder rendered in the right pane when no chat is selected. Pure composable, no state.

**Files:**
- Create: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/EmptyChatPane.kt`

- [ ] **Step 1: Write the file**

```kotlin
package io.nisfeb.talon.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Right-pane placeholder used by [ChatPaneScaffold] when the
 * window is wide enough to render the split layout but the user
 * hasn't selected a chat yet (or the persisted last-open chat
 * resolved to a row that no longer exists).
 *
 * Pure presentation; takes no state. The "select a chat" copy is
 * intentionally minimal — users who got here understand the
 * concept; we don't need a tutorial.
 */
@Composable
fun EmptyChatPane(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painter = talonLogoPainter(),
            contentDescription = null,
            modifier = Modifier.size(64.dp),
        )
        Text(
            text = "Select a chat to begin",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}
```

- [ ] **Step 2: Confirm compile**

Run: `./gradlew :composeApp:compileKotlinDesktop`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/EmptyChatPane.kt
git commit -m "ui: add EmptyChatPane placeholder for split-pane right-side empty state"
```

---

### Task 1.2: Create `ChatPaneScaffold` (no drag handle yet)

The breakpoint-aware host. We build it without the drag handle first so the visible split lands in one commit; the handle and resizing arrive in Phase 3.

**Files:**
- Create: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/ChatPaneScaffold.kt`

- [ ] **Step 1: Write the file**

```kotlin
package io.nisfeb.talon.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * List/detail layout host. Below [ExpandedThreshold] it renders the
 * stack-style "show whichever has content" behaviour Talon used
 * pre-0.9 (mobile + narrow desktop windows). At/above the threshold
 * it places [list] on the left and [detail] (or [EmptyChatPane])
 * on the right.
 *
 * Owns no navigation state — callers (today: `App.kt`) decide what
 * `detail` is by inspecting their existing flags (`openChat`,
 * `openThreadParent`, etc.). The scaffold is a layout component;
 * window-size class crossings just change how it draws.
 *
 * Phase 3 adds a drag handle between the panes that consumes
 * [listFraction]; Phase 1 fixes the fraction at 0.30 so the visible
 * split-pane lands without the resize machinery.
 */
@Composable
fun ChatPaneScaffold(
    list: @Composable () -> Unit,
    detail: (@Composable () -> Unit)?,
    listFraction: Float = DEFAULT_LIST_FRACTION,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val expanded = maxWidth >= ExpandedThreshold
        if (!expanded) {
            // Stack: detail wins when present, list otherwise.
            if (detail != null) detail() else list()
            return@BoxWithConstraints
        }
        Row(modifier = Modifier.fillMaxSize()) {
            val listWidth = maxWidth * listFraction.coerceIn(MIN_LIST_FRACTION, MAX_LIST_FRACTION)
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.width(listWidth).fillMaxHeight(),
            ) { list() }
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.fillMaxWidth().fillMaxHeight(),
            ) {
                if (detail != null) detail() else EmptyChatPane()
            }
        }
    }
}

/** Material3 expanded-window threshold. Tablets in landscape +
 *  desktop windows cross it; phones never do. */
val ExpandedThreshold = 840.dp

/** Initial split — list takes 30% by default. Tuned to match Slack /
 *  Telegram Desktop, where the chat list is intentionally narrower
 *  than the active conversation. */
const val DEFAULT_LIST_FRACTION = 0.30f
const val MIN_LIST_FRACTION = 0.20f
const val MAX_LIST_FRACTION = 0.50f
```

- [ ] **Step 2: Confirm compile**

Run: `./gradlew :composeApp:compileKotlinDesktop`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/ChatPaneScaffold.kt
git commit -m "ui: ChatPaneScaffold — list/detail layout host with 840dp breakpoint"
```

---

### Task 1.3: Compose UI test for `ChatPaneScaffold`

Lives in `desktopTest` because the Compose UI test runner is only wired up there. Verifies stacked vs split rendering at the threshold.

**Files:**
- Create: `composeApp/src/desktopTest/kotlin/io/nisfeb/talon/ui/ChatPaneScaffoldTest.kt`

- [ ] **Step 1: Write the test**

```kotlin
package io.nisfeb.talon.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test

class ChatPaneScaffoldTest {

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `narrow window renders only detail when set`() = runComposeUiTest {
        setContent {
            // 600dp < 840dp threshold → stacked behaviour
            androidx.compose.foundation.layout.Box(
                Modifier.size(width = 600.dp, height = 800.dp),
            ) {
                ChatPaneScaffold(
                    list = { Text("LIST") },
                    detail = { Text("DETAIL") },
                )
            }
        }
        onNodeWithText("DETAIL").assertExists()
        onNodeWithText("LIST").assertDoesNotExist()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `narrow window renders list when detail is null`() = runComposeUiTest {
        setContent {
            androidx.compose.foundation.layout.Box(
                Modifier.size(width = 600.dp, height = 800.dp),
            ) {
                ChatPaneScaffold(list = { Text("LIST") }, detail = null)
            }
        }
        onNodeWithText("LIST").assertExists()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `wide window renders both panes when detail is set`() = runComposeUiTest {
        setContent {
            androidx.compose.foundation.layout.Box(
                Modifier.size(width = 1200.dp, height = 800.dp),
            ) {
                ChatPaneScaffold(
                    list = { Text("LIST") },
                    detail = { Text("DETAIL") },
                )
            }
        }
        onNodeWithText("LIST").assertExists()
        onNodeWithText("DETAIL").assertExists()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `wide window with null detail renders empty pane copy`() = runComposeUiTest {
        setContent {
            androidx.compose.foundation.layout.Box(
                Modifier.size(width = 1200.dp, height = 800.dp),
            ) {
                ChatPaneScaffold(list = { Text("LIST") }, detail = null)
            }
        }
        onNodeWithText("LIST").assertExists()
        onNodeWithText("Select a chat to begin").assertExists()
    }
}
```

- [ ] **Step 2: Run the tests, expect them to pass**

Run: `./gradlew :composeApp:desktopTest --tests 'io.nisfeb.talon.ui.ChatPaneScaffoldTest'`
Expected: 4 tests pass.

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/desktopTest/kotlin/io/nisfeb/talon/ui/ChatPaneScaffoldTest.kt
git commit -m "test(ui): ChatPaneScaffold breakpoint behaviour at 600dp and 1200dp"
```

---

### Task 1.4: Wire `ChatPaneScaffold` into `App.kt`

Replace the bare `when {}` block with the scaffold. Phase 1 keeps `listFraction` as the default; persistence lands in Phase 3.

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/compose/App.kt` — the navigation block (search for `openGroupHomeFlag != null -> GroupHomeScreen`).

- [ ] **Step 1: Identify the existing navigation block**

The block looks roughly like this (line numbers around 600-770; verify by grep first):

```kotlin
when {
    showSettings -> SettingsScreen(...)
    openGroupHomeFlag != null -> GroupHomeScreen(...)
    openChat != null && openThreadParent != null -> ThreadScreen(...)
    openChat != null -> DmChatScreen(...)
    else -> DmListScreen(...)
}
```

Run: `grep -n 'openGroupHomeFlag != null -> GroupHomeScreen' composeApp/src/commonMain/kotlin/io/nisfeb/talon/compose/App.kt`
Expected: one line matching.

- [ ] **Step 2: Capture the existing screen invocations verbatim**

Before editing, copy the existing `DmListScreen(...)`, `DmChatScreen(...)`, and `ThreadScreen(...)` call expressions out of the current `when {}` block to a scratch buffer. These call expressions can be 30-50 lines each — they include every parameter the screens take today. Step 3's edit will paste them back into the new structure unchanged. The whole point of this task is layout-only; the screen-call argument lists must not change.

- [ ] **Step 3: Restructure into list/detail slots**

Wrap the block so the list slot evaluates to `DmListScreen` and the detail slot evaluates to one of the chat-detail screens (or null when at the list root). The "modal" branches (settings, group home) stay as full-screen replace by short-circuiting to render directly without entering the scaffold.

Replace the existing block with the structure below. **The `// existing X(…) call expression`** comments mark where to paste the lambdas you captured in Step 2 — *do not invent parameters*, copy verbatim:

```kotlin
// Modal branches stay full-screen replace — settings, group home,
// and the post-tap branches that swap the entire surface. Keep
// each existing branch's body byte-for-byte identical to what's
// there today.
when {
    showSettings -> {
        // existing SettingsScreen(...) block, including any
        // surrounding `val relayClient = remember { … }` etc.
    }
    openGroupHomeFlag != null -> {
        // existing GroupHomeScreen(...) call expression
    }
    showSelfProfile -> {
        // existing ProfileEditScreen(...) call expression
    }
    // any other full-screen branches the current `when` has —
    // copy them across as-is, no parameter changes.
    else -> {
        // List/detail surface. The `detail` slot returns null when
        // we're at the list root, otherwise renders the
        // chat-style screen the same way the prior `when` block did.
        val detailSlot: (@Composable () -> Unit)? = when {
            openChat != null && openThreadParent != null -> {
                { /* existing ThreadScreen(...) call expression */ }
            }
            openChat != null -> {
                { /* existing DmChatScreen(...) call expression */ }
            }
            else -> null
        }
        ChatPaneScaffold(
            list = {
                /* existing DmListScreen(...) call expression */
            },
            detail = detailSlot,
        )
    }
}
```

**IMPORTANT:** the existing call expressions for `DmListScreen`, `DmChatScreen`, `ThreadScreen`, etc. include long argument lists (~30 lines each). Copy them verbatim into the new lambda bodies — no parameter changes, no reorderings. This task is purely a wrap-around-existing-code refactor.

- [ ] **Step 3: Run desktop tests + compile both targets**

```
./gradlew :composeApp:desktopTest :composeApp:compileKotlinDesktop :composeApp:compileDebugKotlinAndroid
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Manual smoke**

```
./gradlew :composeApp:run
```

Resize the desktop window across the 840dp threshold. Below: chat opens full-width like today. Above: list visible left, chat detail right. Empty pane shows when nothing's open.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/compose/App.kt
git commit -m "ui(app): host chat list+detail in ChatPaneScaffold"
```

---

## Phase 2 — Last-open chat persistence

### Task 2.1: `LastOpenChatStore` interface + `InMemory` + `Noop`

Pure-logic, lives entirely in commonMain.

**Files:**
- Create: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/notify/LastOpenChatStore.kt`

- [ ] **Step 1: Write the file**

```kotlin
package io.nisfeb.talon.notify

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Per-ship persistent record of which chat was open when the user
 * last sent the app to background or quit. Powers the wide-window
 * "open straight to your last conversation" behaviour driven by
 * [io.nisfeb.talon.ui.ChatPaneScaffold].
 *
 * Keyed on the ship's patp, value is the conversation `whom` —
 * same identifier the existing `openChat` state and chat-list rows
 * use. Reads happen on first composition / ship-switch; writes on
 * every `openChat` flip.
 *
 * The store is intentionally thin: no expiry, no cross-ship index.
 * A persisted entry pointing at a chat that no longer exists in
 * the local DB is harmless — the seed lookup returns null and the
 * scaffold renders [io.nisfeb.talon.ui.EmptyChatPane].
 */
interface LastOpenChatStore {
    val state: StateFlow<Map<String, String>>
    fun set(patp: String, whom: String)
    fun clear(patp: String)
}

class InMemoryLastOpenChatStore(
    initial: Map<String, String> = emptyMap(),
) : LastOpenChatStore {
    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<Map<String, String>> = _state.asStateFlow()
    override fun set(patp: String, whom: String) {
        if (_state.value[patp] == whom) return
        _state.value = _state.value + (patp to whom)
    }
    override fun clear(patp: String) {
        if (patp !in _state.value) return
        _state.value = _state.value - patp
    }
}

object NoopLastOpenChatStore : LastOpenChatStore {
    override val state: StateFlow<Map<String, String>> =
        MutableStateFlow(emptyMap()).asStateFlow()
    override fun set(patp: String, whom: String) = Unit
    override fun clear(patp: String) = Unit
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew :composeApp:compileKotlinDesktop`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/notify/LastOpenChatStore.kt
git commit -m "notify: LastOpenChatStore interface + InMemory + Noop"
```

---

### Task 2.2: Tests for `InMemoryLastOpenChatStore`

**Files:**
- Create: `composeApp/src/commonTest/kotlin/io/nisfeb/talon/notify/LastOpenChatStoreTest.kt`

- [ ] **Step 1: Write tests**

```kotlin
package io.nisfeb.talon.notify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LastOpenChatStoreTest {
    @Test
    fun `set and read round-trips`() {
        val s = InMemoryLastOpenChatStore()
        s.set("~sampel", "~friend")
        assertEquals("~friend", s.state.value["~sampel"])
    }

    @Test
    fun `set is per-patp - other patps unaffected`() {
        val s = InMemoryLastOpenChatStore()
        s.set("~sampel", "~friend")
        s.set("~other", "~someone")
        assertEquals("~friend", s.state.value["~sampel"])
        assertEquals("~someone", s.state.value["~other"])
    }

    @Test
    fun `clear removes the entry for a patp`() {
        val s = InMemoryLastOpenChatStore()
        s.set("~sampel", "~friend")
        s.clear("~sampel")
        assertNull(s.state.value["~sampel"])
    }

    @Test
    fun `set with same value is a no-op (no flow emission storm)`() {
        val s = InMemoryLastOpenChatStore()
        s.set("~sampel", "~friend")
        val before = s.state.value
        s.set("~sampel", "~friend")
        assertEquals(before, s.state.value)
    }

    @Test
    fun `initial map seeds the flow`() {
        val s = InMemoryLastOpenChatStore(initial = mapOf("~a" to "~x"))
        assertEquals("~x", s.state.value["~a"])
    }
}
```

- [ ] **Step 2: Run tests**

Run: `./gradlew :composeApp:desktopTest --tests 'io.nisfeb.talon.notify.LastOpenChatStoreTest'`
Expected: 5 tests pass.

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/commonTest/kotlin/io/nisfeb/talon/notify/LastOpenChatStoreTest.kt
git commit -m "test(notify): InMemoryLastOpenChatStore behaviour"
```

---

### Task 2.3: `DesktopLastOpenChatStore` — JSON file impl

**Files:**
- Create: `composeApp/src/desktopMain/kotlin/io/nisfeb/talon/notify/DesktopLastOpenChatStore.kt`

- [ ] **Step 1: Write the file**

```kotlin
package io.nisfeb.talon.notify

import io.nisfeb.talon.util.AppDirs
import io.nisfeb.talon.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * JSON-file-backed [LastOpenChatStore] for desktop. The map is
 * serialised verbatim under `<config>/last-open-chat.json`. Writes
 * go via a `<file>.tmp + atomic rename` so a crash mid-write
 * leaves the previous version intact (Files.move with REPLACE_EXISTING
 * + ATOMIC_MOVE).
 *
 * The store reads the file on construction; if the file is absent
 * or unreadable the in-memory map starts empty and the next write
 * creates it.
 */
class DesktopLastOpenChatStore(
    private val path: Path = AppDirs.config().resolve(FILE_NAME),
) : LastOpenChatStore {

    @Serializable
    private data class Snapshot(val entries: Map<String, String> = emptyMap())

    private val _state = MutableStateFlow(loadOrEmpty())
    override val state: StateFlow<Map<String, String>> = _state.asStateFlow()

    override fun set(patp: String, whom: String) {
        val prev = _state.value
        if (prev[patp] == whom) return
        val next = prev + (patp to whom)
        _state.value = next
        persist(next)
    }

    override fun clear(patp: String) {
        val prev = _state.value
        if (patp !in prev) return
        val next = prev - patp
        _state.value = next
        persist(next)
    }

    private fun loadOrEmpty(): Map<String, String> = runCatching {
        if (!Files.exists(path)) return emptyMap()
        Files.newInputStream(path).use { stream ->
            JSON.decodeFromStream<Snapshot>(stream).entries
        }
    }.getOrElse {
        Log.w(TAG, "last-open-chat read failed; starting empty", it)
        emptyMap()
    }

    private fun persist(map: Map<String, String>) {
        runCatching {
            Files.createDirectories(path.parent)
            val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
            Files.newOutputStream(tmp).use { out ->
                out.write(JSON.encodeToString(Snapshot.serializer(), Snapshot(map)).toByteArray())
            }
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.onFailure { Log.w(TAG, "last-open-chat write failed", it) }
    }

    private companion object {
        private const val TAG = "DesktopLastOpenChatStore"
        const val FILE_NAME = "last-open-chat.json"
        val JSON = Json {
            ignoreUnknownKeys = true
            prettyPrint = false
        }
    }
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew :composeApp:compileKotlinDesktop`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/desktopMain/kotlin/io/nisfeb/talon/notify/DesktopLastOpenChatStore.kt
git commit -m "notify: DesktopLastOpenChatStore — JSON file under AppDirs.config"
```

---

### Task 2.4: `DesktopLastOpenChatStore` round-trip test

**Files:**
- Create: `composeApp/src/desktopTest/kotlin/io/nisfeb/talon/notify/DesktopLastOpenChatStoreTest.kt`

- [ ] **Step 1: Write the test**

```kotlin
package io.nisfeb.talon.notify

import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopLastOpenChatStoreTest {

    private val tempPath = Files.createTempFile("talon-last-open-test", ".json").also {
        // Files.createTempFile creates an empty file; remove so the store
        // sees "no file yet" on first construction (the typical real path).
        Files.deleteIfExists(it)
    }

    @AfterTest
    fun cleanup() {
        tempPath.deleteIfExists()
        tempPath.resolveSibling(tempPath.fileName.toString() + ".tmp").deleteIfExists()
    }

    @Test
    fun `set persists then a fresh store reads it back`() {
        DesktopLastOpenChatStore(tempPath).set("~sampel", "~friend")
        val reloaded = DesktopLastOpenChatStore(tempPath)
        assertEquals("~friend", reloaded.state.value["~sampel"])
    }

    @Test
    fun `clear persists removal`() {
        val s1 = DesktopLastOpenChatStore(tempPath)
        s1.set("~sampel", "~friend")
        s1.clear("~sampel")
        val s2 = DesktopLastOpenChatStore(tempPath)
        assertNull(s2.state.value["~sampel"])
    }

    @Test
    fun `missing file means empty state - no exception`() {
        // tempPath was deleted in init — store should construct cleanly.
        val s = DesktopLastOpenChatStore(tempPath)
        assertTrue(s.state.value.isEmpty())
    }

    @Test
    fun `corrupt file means empty state - no exception`() {
        Files.writeString(tempPath, "{not valid json")
        val s = DesktopLastOpenChatStore(tempPath)
        assertTrue(s.state.value.isEmpty())
    }

    @Test
    fun `multiple ships round-trip independently`() {
        val s = DesktopLastOpenChatStore(tempPath)
        s.set("~a", "~x")
        s.set("~b", "~y")
        val reloaded = DesktopLastOpenChatStore(tempPath)
        assertEquals("~x", reloaded.state.value["~a"])
        assertEquals("~y", reloaded.state.value["~b"])
    }
}
```

- [ ] **Step 2: Run the tests**

Run: `./gradlew :composeApp:desktopTest --tests 'io.nisfeb.talon.notify.DesktopLastOpenChatStoreTest'`
Expected: 5 tests pass.

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/desktopTest/kotlin/io/nisfeb/talon/notify/DesktopLastOpenChatStoreTest.kt
git commit -m "test(notify): DesktopLastOpenChatStore round-trip + corruption recovery"
```

---

### Task 2.5: `AndroidLastOpenChatStore` — SharedPreferences impl

**Files:**
- Create: `composeApp/src/androidMain/kotlin/io/nisfeb/talon/notify/AndroidLastOpenChatStore.kt`

- [ ] **Step 1: Write the file**

```kotlin
package io.nisfeb.talon.notify

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * SharedPreferences-backed [LastOpenChatStore]. Each ship (patp) gets
 * one preference key — `whom:<patp> → <whom>`. Avoids the full-map
 * read/write cycle of the JSON impl while preserving the same
 * external behaviour.
 *
 * Reads on construction populate the StateFlow snapshot so the
 * scaffold can seed its right-pane on first composition without a
 * suspending call.
 */
class AndroidLastOpenChatStore(context: Context) : LastOpenChatStore {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(snapshot())
    override val state: StateFlow<Map<String, String>> = _state.asStateFlow()

    override fun set(patp: String, whom: String) {
        if (_state.value[patp] == whom) return
        prefs.edit().putString(KEY_PREFIX + patp, whom).apply()
        _state.value = _state.value + (patp to whom)
    }

    override fun clear(patp: String) {
        if (patp !in _state.value) return
        prefs.edit().remove(KEY_PREFIX + patp).apply()
        _state.value = _state.value - patp
    }

    private fun snapshot(): Map<String, String> {
        val out = mutableMapOf<String, String>()
        for ((key, value) in prefs.all) {
            if (key.startsWith(KEY_PREFIX) && value is String) {
                out[key.removePrefix(KEY_PREFIX)] = value
            }
        }
        return out
    }

    private companion object {
        private const val PREFS_NAME = "talon.last_open_chat"
        private const val KEY_PREFIX = "whom:"
    }
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew :composeApp:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/androidMain/kotlin/io/nisfeb/talon/notify/AndroidLastOpenChatStore.kt
git commit -m "notify: AndroidLastOpenChatStore — SharedPreferences-backed impl"
```

---

### Task 2.6: Wire the store into `App.kt` and the platform entrypoints

Adds a `lastOpenChatStore` parameter to `App()` (defaulted to `NoopLastOpenChatStore` so tests don't need to wire one) and uses it to seed `openChat` on first composition / ship switch, plus mirror `openChat` flips back into the store.

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/compose/App.kt`
- Modify: `composeApp/src/desktopMain/kotlin/io/nisfeb/talon/compose/Main.kt`
- Modify: `composeApp/src/androidMain/kotlin/io/nisfeb/talon/MainActivity.kt`

- [ ] **Step 1: Add the parameter to `App()`**

Find the `App()` parameter list (search `fun App(` in App.kt). Add:

```kotlin
/** Per-ship "what chat was open last" memory. Wide windows seed
 *  the right pane from this so the user lands back on their
 *  conversation instead of an empty pane. Default Noop for tests. */
lastOpenChatStore: io.nisfeb.talon.notify.LastOpenChatStore =
    io.nisfeb.talon.notify.NoopLastOpenChatStore,
```

- [ ] **Step 2: Seed `openChat` on ship key**

Inside the ship-keyed block (near the existing `key(shipKey)` / `remember`), add a `LaunchedEffect(shipKey)` that, if `openChat` is null, sets it from the store. Locate the existing `loggedInShip != null` block in App.kt; add this near where `openChat` is declared:

```kotlin
val seedChat by lastOpenChatStore.state.collectAsState()
LaunchedEffect(loggedInShip) {
    if (openChat == null && loggedInShip != null) {
        seedChat[loggedInShip]?.let { openChat = it }
    }
}
```

- [ ] **Step 3: Mirror `openChat` back into the store**

Right next to the seed effect:

```kotlin
LaunchedEffect(openChat, loggedInShip) {
    val ship = loggedInShip ?: return@LaunchedEffect
    val whom = openChat
    if (whom != null) lastOpenChatStore.set(ship, whom)
    // Note: explicit null is "user backed out" — keep the persisted
    // entry so re-open lands them in the same chat. Don't clear here.
}
```

- [ ] **Step 4: Wire the desktop graph**

In `Main.kt`'s `DesktopAppGraph` (around line 70-90 where other settings are constructed), add:

```kotlin
val lastOpenChatStore: io.nisfeb.talon.notify.LastOpenChatStore =
    io.nisfeb.talon.notify.DesktopLastOpenChatStore()
```

Then in the `App(` call inside `application { Window { … } }` add `lastOpenChatStore = graph.lastOpenChatStore,` to the parameter list.

- [ ] **Step 5: Wire the Android entrypoint**

Find the `setContent { App( … ) }` block in `MainActivity.kt`. Add:

```kotlin
lastOpenChatStore = remember {
    io.nisfeb.talon.notify.AndroidLastOpenChatStore(applicationContext)
},
```

The `remember` keeps the same instance across recompositions so the SharedPreferences read happens once.

- [ ] **Step 6: Compile both targets + run tests**

```
./gradlew :composeApp:compileKotlinDesktop :composeApp:compileDebugKotlinAndroid :composeApp:desktopTest
```
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 7: Manual smoke (desktop)**

```
./gradlew :composeApp:run
```
Open a chat, quit Talon, reopen → chat should be selected on the right pane immediately on a wide window. Switch ships → right pane swaps to that ship's last-open chat (or empty if none).

- [ ] **Step 8: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/compose/App.kt \
        composeApp/src/desktopMain/kotlin/io/nisfeb/talon/compose/Main.kt \
        composeApp/src/androidMain/kotlin/io/nisfeb/talon/MainActivity.kt
git commit -m "ui(app): persist + restore last-open chat per ship via LastOpenChatStore"
```

---

## Phase 3 — Drag-to-resize

### Task 3.1: Extend `UiSettings` with `chatPaneListFraction`

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/UiSettings.kt`

- [ ] **Step 1: Add field to interface**

After `setGroupChannelOrder(...)` in the interface body:

```kotlin
/**
 * Ratio of total width given to the chat-list pane on wide
 * windows (≥840dp). Clamped to 0.20–0.50 at the setter; corrupt
 * values from disk get normalised on next write. See
 * [ChatPaneScaffold] / [DEFAULT_LIST_FRACTION].
 */
val chatPaneListFraction: StateFlow<Float>
fun setChatPaneListFraction(value: Float)
```

- [ ] **Step 2: Implement in `InMemoryUiSettings`**

Inside the existing `InMemoryUiSettings` class, after the `_groupChannelOrder` block:

```kotlin
private val _chatPaneListFraction = MutableStateFlow(
    initialChatPaneListFraction.coerceIn(0.20f, 0.50f),
)
override val chatPaneListFraction: StateFlow<Float> =
    _chatPaneListFraction.asStateFlow()
override fun setChatPaneListFraction(value: Float) {
    _chatPaneListFraction.value = value.coerceIn(0.20f, 0.50f)
}
```

Add a constructor parameter to `InMemoryUiSettings`:

```kotlin
class InMemoryUiSettings(
    initialHide: Boolean = false,
    initialAccent: AccentSettings = AccentSettings(),
    initialGroupOrder: GroupChannelOrder = GroupChannelOrder.Recent,
    initialChatPaneListFraction: Float = 0.30f,
) : UiSettings { ... }
```

- [ ] **Step 3: Compile**

Run: `./gradlew :composeApp:compileKotlinDesktop`
Expected: This *will fail* on `DesktopUiSettings` and `AndroidUiSettings` because they don't implement the new method yet — that's fixed in 3.2/3.3. Stop here, do not commit.

---

### Task 3.2: Persist `chatPaneListFraction` in `DesktopUiSettings`

**Files:**
- Modify: `composeApp/src/desktopMain/kotlin/io/nisfeb/talon/ui/DesktopUiSettings.kt`

- [ ] **Step 1: Add the field to the persisted snapshot**

Find the `data class` body inside `DesktopUiSettings` (around line 25 — search for `hideComposerButtons: Boolean = false`). Add:

```kotlin
val chatPaneListFraction: Float = 0.30f,
```

- [ ] **Step 2: Wire the StateFlow + setter**

After the existing `_groupChannelOrder` block (around line 52), add:

```kotlin
private val _chatPaneListFraction = MutableStateFlow(
    initial.chatPaneListFraction.coerceIn(0.20f, 0.50f),
)
override val chatPaneListFraction: StateFlow<Float> =
    _chatPaneListFraction.asStateFlow()
override fun setChatPaneListFraction(value: Float) {
    val clamped = value.coerceIn(0.20f, 0.50f)
    if (_chatPaneListFraction.value == clamped) return
    _chatPaneListFraction.value = clamped
    persist()
}
```

- [ ] **Step 3: Update the `persist()` snapshot**

Find `persist()` and add `chatPaneListFraction = _chatPaneListFraction.value` to the snapshot literal.

- [ ] **Step 4: Compile**

Run: `./gradlew :composeApp:compileKotlinDesktop`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit (still need Android impl before tests run cleanly across both targets)**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/UiSettings.kt \
        composeApp/src/desktopMain/kotlin/io/nisfeb/talon/ui/DesktopUiSettings.kt
git commit -m "ui: chatPaneListFraction in UiSettings + DesktopUiSettings"
```

---

### Task 3.3: Persist `chatPaneListFraction` in `AndroidUiSettings`

**Files:**
- Modify: `composeApp/src/androidMain/kotlin/io/nisfeb/talon/ui/AndroidUiSettings.kt`

- [ ] **Step 1: Inspect the existing pattern**

Run: `grep -n 'groupChannelOrder' composeApp/src/androidMain/kotlin/io/nisfeb/talon/ui/AndroidUiSettings.kt`
Expected: a few lines showing where the existing field is read from and written to SharedPreferences.

- [ ] **Step 2: Add the new field**

Mirror the existing `groupChannelOrder` pattern. Add a private SharedPreferences key:

```kotlin
private const val KEY_CHAT_PANE_LIST_FRACTION = "chat_pane_list_fraction"
```

Add the StateFlow + setter inside the class:

```kotlin
private val _chatPaneListFraction = MutableStateFlow(
    prefs.getFloat(KEY_CHAT_PANE_LIST_FRACTION, 0.30f).coerceIn(0.20f, 0.50f),
)
override val chatPaneListFraction: StateFlow<Float> =
    _chatPaneListFraction.asStateFlow()
override fun setChatPaneListFraction(value: Float) {
    val clamped = value.coerceIn(0.20f, 0.50f)
    if (_chatPaneListFraction.value == clamped) return
    prefs.edit().putFloat(KEY_CHAT_PANE_LIST_FRACTION, clamped).apply()
    _chatPaneListFraction.value = clamped
}
```

- [ ] **Step 3: Compile both targets**

```
./gradlew :composeApp:compileKotlinDesktop :composeApp:compileDebugKotlinAndroid :composeApp:desktopTest
```
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/androidMain/kotlin/io/nisfeb/talon/ui/AndroidUiSettings.kt
git commit -m "ui: chatPaneListFraction in AndroidUiSettings"
```

---

### Task 3.4: Add the drag handle to `ChatPaneScaffold`

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/ChatPaneScaffold.kt`

- [ ] **Step 1: Add the handle composable**

Inside `ChatPaneScaffold.kt`, add a private composable below the public one:

```kotlin
@Composable
private fun PaneDragHandle(
    onDragDelta: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .fillMaxHeight()
            .width(4.dp)
            .androidx.compose.ui.input.pointer.pointerHoverIcon(
                androidx.compose.ui.input.pointer.PointerIcon.Hand,
            )
            .androidx.compose.foundation.gestures.draggable(
                orientation = androidx.compose.foundation.gestures.Orientation.Horizontal,
                state = androidx.compose.foundation.gestures.rememberDraggableState { delta ->
                    onDragDelta(delta)
                },
            )
            .androidx.compose.foundation.background(
                color = androidx.compose.material3.MaterialTheme.colorScheme.outlineVariant,
            ),
    ) { /* visual only */ }
}
```

(Drop the inline `androidx.compose.…` qualifications by adding the imports at the top of the file: `androidx.compose.foundation.background`, `androidx.compose.foundation.gestures.Orientation`, `androidx.compose.foundation.gestures.draggable`, `androidx.compose.foundation.gestures.rememberDraggableState`, `androidx.compose.ui.input.pointer.PointerIcon`, `androidx.compose.ui.input.pointer.pointerHoverIcon`. The qualifications above are only there because the rest of the file uses imports but I want to spell them out in the example — please use bare imports in the actual edit.)

- [ ] **Step 2: Modify `ChatPaneScaffold` to take a `onListFractionChange` lambda and embed the handle**

Update the public composable signature:

```kotlin
@Composable
fun ChatPaneScaffold(
    list: @Composable () -> Unit,
    detail: (@Composable () -> Unit)?,
    listFraction: Float = DEFAULT_LIST_FRACTION,
    onListFractionChange: (Float) -> Unit = {},
    modifier: Modifier = Modifier,
)
```

Change the expanded-mode `Row` body to insert the handle between list and detail. Replace the body with:

```kotlin
Row(modifier = Modifier.fillMaxSize()) {
    val totalWidthPx = with(LocalDensity.current) { maxWidth.toPx() }
    val listWidth = maxWidth * listFraction.coerceIn(MIN_LIST_FRACTION, MAX_LIST_FRACTION)
    Box(modifier = Modifier.width(listWidth).fillMaxHeight()) { list() }
    PaneDragHandle(onDragDelta = { deltaPx ->
        val delta = deltaPx / totalWidthPx
        onListFractionChange((listFraction + delta).coerceIn(MIN_LIST_FRACTION, MAX_LIST_FRACTION))
    })
    Box(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
        if (detail != null) detail() else EmptyChatPane()
    }
}
```

Add the import: `import androidx.compose.ui.platform.LocalDensity`.

- [ ] **Step 3: Pass the fraction through `App.kt`**

Inside the `App()` body where you invoke `ChatPaneScaffold`, supply:

```kotlin
val listFraction by uiSettings.chatPaneListFraction.collectAsState()
ChatPaneScaffold(
    list = { DmListScreen(/* … */) },
    detail = detailSlot,
    listFraction = listFraction,
    onListFractionChange = { uiSettings.setChatPaneListFraction(it) },
)
```

- [ ] **Step 4: Compile + test + smoke**

```
./gradlew :composeApp:compileKotlinDesktop :composeApp:desktopTest
./gradlew :composeApp:run
```

Drag the handle, observe the panes resize. Quit + restart — fraction persists. Resize beyond 0.20/0.50 — clamps.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/ChatPaneScaffold.kt \
        composeApp/src/commonMain/kotlin/io/nisfeb/talon/compose/App.kt
git commit -m "ui(scaffold): draggable handle between list and detail panes"
```

---

## Phase 4 — Keyboard shortcuts

### Task 4.1: `KeyboardShortcuts.kt` — pure mapping

**Files:**
- Create: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/KeyboardShortcuts.kt`

- [ ] **Step 1: Write the file**

```kotlin
package io.nisfeb.talon.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/**
 * Discrete actions the keyboard-shortcut layer can request. Hosts
 * (App.kt today) interpret each by flipping their existing state
 * setters or new request flags.
 */
sealed interface ShortcutAction {
    data object FocusSearch : ShortcutAction
    data object NewDm : ShortcutAction
    data object Back : ShortcutAction
    data object OpenSettings : ShortcutAction
    data class SwitchShip(val index: Int) : ShortcutAction
}

/**
 * Pure mapping from a [KeyEvent] to a [ShortcutAction]. Returns null
 * for any event that doesn't match — caller passes the event through
 * to the focused widget unchanged.
 *
 * - macOS uses Cmd (`isMetaPressed`); other platforms use Ctrl.
 *   The selector is determined by [isMacHost] so the same code path
 *   serves Android (always Ctrl), Linux/Windows desktop (Ctrl), and
 *   macOS desktop (Cmd).
 * - Only KeyDown events trigger; key-up is ignored so a held key
 *   fires once per press.
 * - Shift / Alt qualifiers must be absent — combinations like
 *   Ctrl+Shift+K are not bound here and pass through to the editor.
 */
fun keyEventToShortcut(event: KeyEvent, isMacHost: Boolean = false): ShortcutAction? {
    if (event.type != KeyEventType.KeyDown) return null
    if (event.isShiftPressed || event.isAltPressed) return null
    val modifierActive = if (isMacHost) event.isMetaPressed else event.isCtrlPressed
    if (!modifierActive && event.key != Key.Escape) return null
    return when (event.key) {
        Key.Escape -> ShortcutAction.Back
        Key.K -> ShortcutAction.FocusSearch
        Key.N -> ShortcutAction.NewDm
        Key.Comma -> ShortcutAction.OpenSettings
        Key.One -> ShortcutAction.SwitchShip(0)
        Key.Two -> ShortcutAction.SwitchShip(1)
        Key.Three -> ShortcutAction.SwitchShip(2)
        Key.Four -> ShortcutAction.SwitchShip(3)
        Key.Five -> ShortcutAction.SwitchShip(4)
        Key.Six -> ShortcutAction.SwitchShip(5)
        Key.Seven -> ShortcutAction.SwitchShip(6)
        Key.Eight -> ShortcutAction.SwitchShip(7)
        Key.Nine -> ShortcutAction.SwitchShip(8)
        else -> null
    }
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew :composeApp:compileKotlinDesktop`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/KeyboardShortcuts.kt
git commit -m "ui: keyEventToShortcut pure mapping for desktop+tablet keyboards"
```

---

### Task 4.2: Tests for the mapping

**Files:**
- Create: `composeApp/src/commonTest/kotlin/io/nisfeb/talon/ui/KeyboardShortcutsTest.kt`

- [ ] **Step 1: Write tests**

```kotlin
package io.nisfeb.talon.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.nativeKeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KeyboardShortcutsTest {

    @Test
    fun `Ctrl+K maps to FocusSearch`() {
        val ev = ctrlKeyDown(Key.K)
        assertEquals(ShortcutAction.FocusSearch, keyEventToShortcut(ev))
    }

    @Test
    fun `Ctrl+N maps to NewDm`() {
        assertEquals(ShortcutAction.NewDm, keyEventToShortcut(ctrlKeyDown(Key.N)))
    }

    @Test
    fun `Esc with no modifiers maps to Back`() {
        assertEquals(ShortcutAction.Back, keyEventToShortcut(plainKeyDown(Key.Escape)))
    }

    @Test
    fun `Ctrl+Comma maps to OpenSettings`() {
        assertEquals(ShortcutAction.OpenSettings, keyEventToShortcut(ctrlKeyDown(Key.Comma)))
    }

    @Test
    fun `Ctrl+1 through Ctrl+9 map to SwitchShip(0..8)`() {
        val pairs = listOf(
            Key.One to 0, Key.Two to 1, Key.Three to 2, Key.Four to 3,
            Key.Five to 4, Key.Six to 5, Key.Seven to 6, Key.Eight to 7,
            Key.Nine to 8,
        )
        for ((k, idx) in pairs) {
            assertEquals(ShortcutAction.SwitchShip(idx), keyEventToShortcut(ctrlKeyDown(k)))
        }
    }

    @Test
    fun `KeyUp is ignored`() {
        val ev = ctrlKeyUp(Key.K)
        assertNull(keyEventToShortcut(ev))
    }

    @Test
    fun `Ctrl+Shift+K is ignored - reserved for editor`() {
        assertNull(keyEventToShortcut(ctrlShiftKeyDown(Key.K)))
    }

    @Test
    fun `macOS Cmd+K maps via the meta branch`() {
        assertEquals(ShortcutAction.FocusSearch, keyEventToShortcut(metaKeyDown(Key.K), isMacHost = true))
    }

    @Test
    fun `Ctrl+K does not map when isMacHost is true`() {
        assertNull(keyEventToShortcut(ctrlKeyDown(Key.K), isMacHost = true))
    }

    // — helpers — these use java.awt.event.KeyEvent under the hood
    // (the Compose desktop KeyEvent is a wrapper around it). Tests
    // run only on JVM via desktopTest path (commonTest pulls
    // through to JVM), which is fine for keyboard tests since
    // physical keyboards are a desktop+tablet concern.

    private fun makeEvent(
        keyCode: Int,
        isCtrl: Boolean = false,
        isMeta: Boolean = false,
        isShift: Boolean = false,
        isAlt: Boolean = false,
        type: KeyEventType = KeyEventType.KeyDown,
    ): KeyEvent {
        var mods = 0
        if (isCtrl) mods = mods or java.awt.event.InputEvent.CTRL_DOWN_MASK
        if (isMeta) mods = mods or java.awt.event.InputEvent.META_DOWN_MASK
        if (isShift) mods = mods or java.awt.event.InputEvent.SHIFT_DOWN_MASK
        if (isAlt) mods = mods or java.awt.event.InputEvent.ALT_DOWN_MASK
        val awtType = when (type) {
            KeyEventType.KeyDown -> java.awt.event.KeyEvent.KEY_PRESSED
            KeyEventType.KeyUp -> java.awt.event.KeyEvent.KEY_RELEASED
            else -> java.awt.event.KeyEvent.KEY_PRESSED
        }
        val src = javax.swing.JLabel()
        return KeyEvent(
            java.awt.event.KeyEvent(src, awtType, 0L, mods, keyCode, java.awt.event.KeyEvent.CHAR_UNDEFINED)
        )
    }

    // Hardcode the AWT virtual key codes for the keys we test.
    // Compose's Key.keyCode packing has changed between versions
    // (high-bits vs low-bits) so deriving them at runtime is fragile;
    // a small lookup table is stable across upgrades.
    private val awtCodeFor: Map<Key, Int> = mapOf(
        Key.K to java.awt.event.KeyEvent.VK_K,
        Key.N to java.awt.event.KeyEvent.VK_N,
        Key.Comma to java.awt.event.KeyEvent.VK_COMMA,
        Key.Escape to java.awt.event.KeyEvent.VK_ESCAPE,
        Key.One to java.awt.event.KeyEvent.VK_1,
        Key.Two to java.awt.event.KeyEvent.VK_2,
        Key.Three to java.awt.event.KeyEvent.VK_3,
        Key.Four to java.awt.event.KeyEvent.VK_4,
        Key.Five to java.awt.event.KeyEvent.VK_5,
        Key.Six to java.awt.event.KeyEvent.VK_6,
        Key.Seven to java.awt.event.KeyEvent.VK_7,
        Key.Eight to java.awt.event.KeyEvent.VK_8,
        Key.Nine to java.awt.event.KeyEvent.VK_9,
    )

    private fun ctrlKeyDown(k: Key) = makeEvent(awtCodeFor.getValue(k), isCtrl = true)
    private fun ctrlKeyUp(k: Key) = makeEvent(awtCodeFor.getValue(k), isCtrl = true, type = KeyEventType.KeyUp)
    private fun ctrlShiftKeyDown(k: Key) = makeEvent(awtCodeFor.getValue(k), isCtrl = true, isShift = true)
    private fun metaKeyDown(k: Key) = makeEvent(awtCodeFor.getValue(k), isMeta = true)
    private fun plainKeyDown(k: Key) = makeEvent(awtCodeFor.getValue(k))
}
```

- [ ] **Step 2: Run tests**

Run: `./gradlew :composeApp:desktopTest --tests 'io.nisfeb.talon.ui.KeyboardShortcutsTest'`
Expected: 9 tests pass.

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/commonTest/kotlin/io/nisfeb/talon/ui/KeyboardShortcutsTest.kt
git commit -m "test(ui): keyEventToShortcut mapping table — Ctrl/Cmd, KeyDown/Up, modifiers"
```

---

### Task 4.3: Wire `onPreviewKeyEvent` on desktop + Android

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/compose/App.kt`

- [ ] **Step 1: Add the request flags inside `App()`**

Near the other `MutableState` declarations in `App()` (search for `var openChat`):

```kotlin
var focusSearchRequest by remember { mutableStateOf(false) }
var showNewDmRequest by remember { mutableStateOf(false) }
```

- [ ] **Step 2: Detect the host's command-modifier**

Near the top of `App()`, before the navigation block:

```kotlin
val isMacHost = remember {
    System.getProperty("os.name", "").lowercase().let { "mac" in it || "darwin" in it }
}
```

(This is desktop-relevant only; Android always returns false.)

- [ ] **Step 3: Wrap the root content with the handler**

Find the top-level container in `App()` (the outermost `Box` or `Surface` inside `App()`'s body). Add the handler as a `Modifier.onPreviewKeyEvent`:

```kotlin
.onPreviewKeyEvent { event ->
    val action = io.nisfeb.talon.ui.keyEventToShortcut(event, isMacHost = isMacHost)
        ?: return@onPreviewKeyEvent false
    when (action) {
        io.nisfeb.talon.ui.ShortcutAction.Back -> {
            // Close the rightmost layer in priority order — open chat
            // first, then settings/profile/etc, then no-op at the
            // list root.
            when {
                openThreadParent != null -> openThreadParent = null
                openChat != null -> openChat = null
                showSettings -> showSettings = false
                else -> return@onPreviewKeyEvent false
            }
        }
        io.nisfeb.talon.ui.ShortcutAction.OpenSettings -> showSettings = true
        io.nisfeb.talon.ui.ShortcutAction.NewDm -> showNewDmRequest = true
        io.nisfeb.talon.ui.ShortcutAction.FocusSearch -> focusSearchRequest = true
        is io.nisfeb.talon.ui.ShortcutAction.SwitchShip -> {
            allShips.getOrNull(action.index)?.let { onSwitchShip(it) }
        }
    }
    true
}
```

Add the import: `import androidx.compose.ui.input.key.onPreviewKeyEvent`. The handler attaches via `Modifier`; if the App root isn't already a Modifier-receiving composable (e.g. just a `Surface` without modifiers), use `Modifier.onPreviewKeyEvent { … }` on whatever Box / Surface is the outermost layout in `App()`. The handler also requires the receiver to be focusable — add `.focusable()` and a `LaunchedEffect` that requests focus once on mount.

For the focus request:

```kotlin
val focusRequester = remember { FocusRequester() }
LaunchedEffect(Unit) { focusRequester.requestFocus() }
// modifier chain on the App root:
Modifier
    .focusRequester(focusRequester)
    .focusable()
    .onPreviewKeyEvent { … }
```

Imports: `androidx.compose.ui.focus.FocusRequester`, `androidx.compose.ui.focus.focusRequester`, `androidx.compose.foundation.focusable`.

- [ ] **Step 4: Compile both targets**

```
./gradlew :composeApp:compileKotlinDesktop :composeApp:compileDebugKotlinAndroid
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Manual smoke (desktop)**

```
./gradlew :composeApp:run
```

Press Esc in a chat → returns to the list (with split-pane: clears the right pane). Ctrl+, → Settings. Ctrl+1, Ctrl+2 → switches ships if more than one is logged in. Ctrl+K and Ctrl+N → triggered (focusSearchRequest / showNewDmRequest visible to whoever consumes them; consumers wired in 4.4).

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/compose/App.kt
git commit -m "ui(app): hook keyboard shortcuts via onPreviewKeyEvent on App root"
```

---

### Task 4.4: Consume the request flags in `DmListScreen`

The flags fire from `App.kt` but actually do something only when the list screen reads them.

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/DmListScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/compose/App.kt` (call site)

- [ ] **Step 1: Add params to `DmListScreen`**

Locate `fun DmListScreen(` (around line 109). Add to the parameter list:

```kotlin
/** When set to true by a keyboard-shortcut handler upstream, focus
 *  the search field and reset the flag. */
focusSearchRequest: Boolean = false,
onFocusSearchHandled: () -> Unit = {},
/** When set, open the New-DM dialog and reset the flag. */
showNewDmRequest: Boolean = false,
onShowNewDmHandled: () -> Unit = {},
```

- [ ] **Step 2: Find the existing search-field state + new-DM trigger**

In `DmListScreen` body, find where the search query state lives (likely `var searchQuery by remember { mutableStateOf("") }` or similar; search for `searchQuery` or `OutlinedTextField`) and where `onNewMessage` is invoked.

- [ ] **Step 3: React to the request flags**

Add inside the composable body, after the existing state declarations:

```kotlin
val searchFocusRequester = remember { FocusRequester() }
LaunchedEffect(focusSearchRequest) {
    if (focusSearchRequest) {
        searchFocusRequester.requestFocus()
        onFocusSearchHandled()
    }
}
LaunchedEffect(showNewDmRequest) {
    if (showNewDmRequest) {
        onNewMessage()
        onShowNewDmHandled()
    }
}
```

Then attach `searchFocusRequester` to the search `OutlinedTextField`'s modifier chain: `.focusRequester(searchFocusRequester)`.

Imports: `androidx.compose.ui.focus.FocusRequester`, `androidx.compose.ui.focus.focusRequester`.

- [ ] **Step 4: Wire from `App.kt`**

Pass through the flags + reset callbacks at the `DmListScreen(...)` call site:

```kotlin
DmListScreen(
    /* … existing params … */
    focusSearchRequest = focusSearchRequest,
    onFocusSearchHandled = { focusSearchRequest = false },
    showNewDmRequest = showNewDmRequest,
    onShowNewDmHandled = { showNewDmRequest = false },
)
```

- [ ] **Step 5: Compile + smoke**

```
./gradlew :composeApp:compileKotlinDesktop :composeApp:compileDebugKotlinAndroid :composeApp:desktopTest
./gradlew :composeApp:run
```

Press Ctrl+K → search field gets focus and a typed character flows to it. Press Ctrl+N → new DM dialog opens.

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/DmListScreen.kt \
        composeApp/src/commonMain/kotlin/io/nisfeb/talon/compose/App.kt
git commit -m "ui(list): focus search + open new-DM via keyboard shortcut requests"
```

---

## Phase 5 — Polish

### Task 5.1: Verify ship-switch clears `openChat` cleanly

A bug pattern: switching ships on a wide window keeps the previous ship's chat showing in the right pane for a frame before the new ship's seed lookup fires.

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/compose/App.kt` (the existing ship-switch handler)

- [ ] **Step 1: Find the ship-switch path**

Run: `grep -n 'onSwitchShip' composeApp/src/commonMain/kotlin/io/nisfeb/talon/compose/App.kt`
Expected: a few matches — one at the param declaration, one in the handler.

- [ ] **Step 2: Ensure `openChat` resets before the switch**

In the lambda that invokes the actual ship-switch logic, prepend `openChat = null` and `openThreadParent = null`. Then the seed effect fires for the new ship's last-open chat on the next composition.

- [ ] **Step 3: Manual smoke**

`./gradlew :composeApp:run`
Open a chat in ship A, switch to ship B → no flash of A's chat in B's surface.

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/compose/App.kt
git commit -m "ui(app): clear openChat on ship-switch so the new ship's seed loads cleanly"
```

---

### Task 5.2: Manual smoke matrix + RELEASE.md note

**Files:**
- Modify: `RELEASE.md`

- [ ] **Step 1: Add a smoke checklist**

Append to `RELEASE.md` under the existing release-readiness section:

```markdown
### Desktop split-pane smoke

- [ ] Wide window (>=840dp): chat list left, chat detail right.
- [ ] Drag handle resizes panes; range clamps at ~20% / ~50%.
- [ ] Quit + relaunch: same chat selected on the right pane.
- [ ] Resize narrow (<840dp): collapses to stacked nav, current chat preserved.
- [ ] Tablet landscape (Android): split-pane visible.
- [ ] Tablet portrait (Android): stacked nav.
- [ ] Ctrl+K focuses search; typing flows to it.
- [ ] Ctrl+N opens new-DM dialog.
- [ ] Esc closes thread → chat → settings, in that order. Final Esc is no-op.
- [ ] Ctrl+, opens Settings.
- [ ] Ctrl+1..9 switches ships when multiple are signed in.
- [ ] Cmd+ variants on macOS work the same.
```

- [ ] **Step 2: Commit**

```bash
git add RELEASE.md
git commit -m "docs(release): add desktop split-pane smoke checklist"
```

---

### Task 5.3: Bump version + tag the branch as a pre-release

(Run only when phases 1-5 are merged or feature-complete.)

**Files:**
- Modify: `composeApp/build.gradle.kts`

- [ ] **Step 1: Bump version**

```kotlin
val talonVersionCode = 60
val talonVersionName = "0.9.0-rc1"
```

- [ ] **Step 2: Compile both targets, run all tests**

```
./gradlew :composeApp:assembleRelease :composeApp:desktopTest :relay:test
```

Expected: BUILD SUCCESSFUL on both, all tests pass.

- [ ] **Step 3: Commit + push branch + tag**

```bash
git add composeApp/build.gradle.kts
git commit -m "release: 0.9.0-rc1 — desktop split-pane + keyboard shortcuts"
git tag -a v0.9.0-rc1 -m "Talon 0.9.0-rc1 — desktop split-pane"
git push github desktop-split-pane
git push github v0.9.0-rc1
```

CI will build and publish a pre-release at the standard URL. Manual smoke matrix (5.2) gates promoting to a non-rc tag.

---

## Done criteria

- All 5 phases committed on the `desktop-split-pane` branch.
- Every checklist item in 5.2 manually verified.
- `desktopTest` and `commonTest` pass.
- v0.9.0-rc1 tagged and pre-released; user has installed and used it on a real desktop window for at least an hour.

After that: PR / fast-forward to `master`, drop the `-rc1`, re-tag `v0.9.0`.
