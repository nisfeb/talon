# Customizable rail items (Phase 4) — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the desktop rail from a fixed 4-tab list-pane switcher into a user-configurable launcher bar holding every kebab destination (10 items total). Per-ship cross-device sync via a new `%settings` bucket. Settings sub-page for visibility toggles.

**Architecture:** New `RailItem` enum (superset of `RailTab`) covers the full universe. Mixed click behaviour — pane-tab items still swap the left pane via the existing `activeRailTab` plumbing; modal items fire the matching kebab handlers (full-screen). New `rail_item_prefs` Room table backs the visibility prefs. New `BUCKET_RAIL_ITEMS` settings bucket syncs them across devices per-ship. Default is "all visible" (absence-of-row in the table = visible).

**Tech Stack:** Room 2.7 KMP (new entity + DAO + migration 30→31), kotlinx.coroutines.flow, Material3 Switch + LazyColumn for the settings sub-page, existing SettingsSyncImpl pattern (matches `BUCKET_NOTIFY_PREFS`).

**Spec:** [`docs/superpowers/specs/2026-05-06-customizable-rail-items-design.md`](../specs/2026-05-06-customizable-rail-items-design.md)

---

## File structure

**Create:**

| Path | Responsibility |
|---|---|
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/RailItem.kt` | Enum + extensions (isPaneTab, toRailTab, icon / label) |
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/data/RailItemPrefEntity.kt` | Room entity for sparse visibility table |
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/data/RailItemPrefDao.kt` | DAO: streamAll, upsert, delete, replaceAll |
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/SidebarSettingsScreen.kt` | Settings sub-page with toggles |
| `composeApp/src/commonTest/kotlin/io/nisfeb/talon/ui/RailItemTest.kt` | Enum predicates + name round-trip |
| `composeApp/src/desktopTest/kotlin/io/nisfeb/talon/data/RailItemPrefDaoTest.kt` | DAO insert/delete/stream round-trip |
| `composeApp/src/desktopTest/kotlin/io/nisfeb/talon/urbit/SettingsSyncRailItemsTest.kt` | applyBucket / applyEntry / setRailItemVisibility round-trip + sparse-form |

**Modify:**

| Path | What |
|---|---|
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/data/AppDatabase.kt` | Add `RailItemPrefEntity`, bump version 30→31, add `railItemPrefs()` accessor |
| `composeApp/src/androidMain/kotlin/io/nisfeb/talon/data/AppDatabase.android.kt` | Mirror entity list + version, add MIGRATION_30_31, register accessor |
| `composeApp/src/desktopMain/kotlin/io/nisfeb/talon/data/AppDatabase.desktop.kt` | Mirror accessor (desktop uses fallbackToDestructiveMigration) |
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/UiSettings.kt` | Add `railVisibility: StateFlow<Map<RailItem, Boolean>>` (read-only) |
| `composeApp/src/desktopMain/kotlin/io/nisfeb/talon/ui/DesktopUiSettings.kt` | Wire railVisibility flow off the new DAO |
| `composeApp/src/androidMain/kotlin/io/nisfeb/talon/ui/AndroidUiSettings.kt` | Same |
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/urbit/SettingsSync.kt` | Add `setRailItemVisibility` interface method (default no-op) |
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/urbit/SettingsSyncImpl.kt` | Add BUCKET_RAIL_ITEMS, set/apply/bootstrap wiring |
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/DesktopShell.kt` | Replace RailTab.entries iteration with enabledItems param + onItemClicked callback |
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/DmListScreen.kt` | Take `kebabItems: Set<RailItem>` param; conditionally render kebab DropdownMenuItems |
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/compose/App.kt` | Compute enabledItems + kebabItems; route onItemClicked; pass to DesktopShell + DmListScreen |
| `composeApp/src/androidMain/kotlin/io/nisfeb/talon/ui/TalonApp.kt` | Same as App.kt for the Android composition |
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/SettingsScreen.kt` | Add "Sidebar" row that drills into SidebarSettingsScreen |
| `composeApp/build.gradle.kts` | Bump version 0.10.0-rcN → 0.11.0-rc1 (or rcN as appropriate) |
| `RELEASE.md` | Add Phase 4 smoke checklist |

---

## Phase 1 — Types and enums

### Task 1.1: `RailItem` enum

**Files:**
- Create: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/RailItem.kt`

- [ ] **Step 1: Write the file**

```kotlin
package io.nisfeb.talon.ui

/**
 * Every clickable entry on the desktop rail. Superset of [RailTab]:
 * pane-tab items (Chats / Statuses / Bookmarks / Activity) still swap
 * the left pane via the existing activeRailTab plumbing; modal items
 * fire the same kebab handlers they always have.
 *
 * Order is canonical (declaration order). Reorder UI is deferred —
 * see the spec's "Out of scope" section.
 *
 * Sign out is intentionally not a RailItem — it's destructive and
 * stays exclusively in the kebab dropdown.
 */
enum class RailItem(val isPaneTab: Boolean) {
    Chats(true),
    Statuses(true),
    Bookmarks(true),
    Activity(true),
    Profile(false),
    Watchwords(false),
    TodaysBrief(false),
    Administration(false),
    Invites(false),
    Settings(false),
    ;

    /**
     * Maps a pane-tab item to its [RailTab] counterpart for the
     * existing activeRailTab plumbing. Returns null for modal items
     * (which don't have a "selected" state).
     */
    fun toRailTab(): RailTab? = if (isPaneTab) RailTab.valueOf(name) else null
}

/**
 * Persistence helper. [RailItem.valueOf] throws on unknown names; this
 * version returns null on any parse failure so a future enum rename
 * doesn't blow up rows / wire entries that pre-date the change.
 */
fun railItemOrNull(name: String?): RailItem? {
    if (name.isNullOrBlank()) return null
    return runCatching { RailItem.valueOf(name) }.getOrNull()
}

/**
 * Read-site helper: default-visible if not in the map. The Map is
 * sparse — only contains rows for items the user has explicitly
 * hidden.
 */
fun Map<RailItem, Boolean>.isVisible(item: RailItem): Boolean = this[item] ?: true
```

- [ ] **Step 2: Compile**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :composeApp:compileKotlinDesktop`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/RailItem.kt
git commit -m "ui: RailItem enum + parse-fallback + isVisible helper"
```

---

### Task 1.2: `RailItem` tests

**Files:**
- Create: `composeApp/src/commonTest/kotlin/io/nisfeb/talon/ui/RailItemTest.kt`

- [ ] **Step 1: Write the tests**

```kotlin
package io.nisfeb.talon.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RailItemTest {

    @Test
    fun `pane-tab items map to a RailTab and round-trip`() {
        for (item in RailItem.entries.filter { it.isPaneTab }) {
            val tab = item.toRailTab()
            assertNotNull(tab, "pane-tab item $item should produce a RailTab")
            assertEquals(item.name, tab!!.name)
        }
    }

    @Test
    fun `modal items return null toRailTab`() {
        for (item in RailItem.entries.filter { !it.isPaneTab }) {
            assertNull(item.toRailTab(), "modal item $item should not have a RailTab")
        }
    }

    @Test
    fun `every RailTab has a RailItem of the same name`() {
        // Pinned the contract so adding a RailTab without a RailItem
        // would surface here, not as a runtime crash in the rail code.
        for (tab in RailTab.entries) {
            val item = railItemOrNull(tab.name)
            assertNotNull(item, "RailTab $tab has no matching RailItem")
            assertTrue(item!!.isPaneTab, "RailItem ${tab.name} should be a pane tab")
        }
    }

    @Test
    fun `railItemOrNull returns null for null, blank, unknown`() {
        assertNull(railItemOrNull(null))
        assertNull(railItemOrNull(""))
        assertNull(railItemOrNull("   "))
        assertNull(railItemOrNull("NotAValue"))
    }

    @Test
    fun `railItemOrNull round-trips every value`() {
        for (item in RailItem.entries) {
            assertEquals(item, railItemOrNull(item.name))
        }
    }

    @Test
    fun `isVisible defaults to true for absent items`() {
        val empty: Map<RailItem, Boolean> = emptyMap()
        for (item in RailItem.entries) {
            assertTrue(empty.isVisible(item), "absent $item should be visible by default")
        }
    }

    @Test
    fun `isVisible respects explicit false`() {
        val map: Map<RailItem, Boolean> = mapOf(RailItem.Settings to false)
        assertEquals(false, map.isVisible(RailItem.Settings))
        assertEquals(true, map.isVisible(RailItem.Chats))
    }
}
```

- [ ] **Step 2: Run tests**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :composeApp:desktopTest --tests 'io.nisfeb.talon.ui.RailItemTest'`
Expected: 7 tests pass.

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/commonTest/kotlin/io/nisfeb/talon/ui/RailItemTest.kt
git commit -m "test(ui): RailItem predicates + round-trip + isVisible defaults"
```

---

## Phase 2 — Persistence layer

### Task 2.1: `RailItemPrefEntity`

**Files:**
- Create: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/data/RailItemPrefEntity.kt`

- [ ] **Step 1: Write the file**

```kotlin
package io.nisfeb.talon.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Sparse rail-visibility override. Only contains rows for items the
 * user has explicitly hidden. Absence of a row means the item is
 * visible (the default). [SettingsSyncImpl] keeps the wire form
 * sparse the same way — a "show on rail" toggle deletes the row
 * + the `%settings` entry; "hide" upserts both.
 */
@Entity(tableName = "rail_item_prefs")
data class RailItemPrefEntity(
    @PrimaryKey val itemName: String,   // RailItem.name
    val visible: Boolean,
)
```

- [ ] **Step 2: Compile**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :composeApp:compileKotlinDesktop`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/data/RailItemPrefEntity.kt
git commit -m "data: RailItemPrefEntity for sparse rail visibility table"
```

---

### Task 2.2: `RailItemPrefDao`

**Files:**
- Create: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/data/RailItemPrefDao.kt`

- [ ] **Step 1: Write the file**

```kotlin
package io.nisfeb.talon.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface RailItemPrefDao {

    @Query("SELECT * FROM rail_item_prefs")
    fun streamAll(): Flow<List<RailItemPrefEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: RailItemPrefEntity)

    @Query("DELETE FROM rail_item_prefs WHERE itemName = :name")
    suspend fun delete(name: String)

    @Query("DELETE FROM rail_item_prefs")
    suspend fun clearAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<RailItemPrefEntity>)

    /** Bulk replace — used by the [SettingsSync] inbound put-bucket
     *  apply path. Atomic so a partial replacement can't leak. */
    @Transaction
    suspend fun replaceAll(rows: List<RailItemPrefEntity>) {
        clearAll()
        if (rows.isNotEmpty()) insertAll(rows)
    }
}
```

- [ ] **Step 2: Compile**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :composeApp:compileKotlinDesktop`
Expected: BUILD SUCCESSFUL. (Room's annotation processor doesn't see the DAO until Task 2.3 wires it into AppDatabase; the file compiles standalone.)

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/data/RailItemPrefDao.kt
git commit -m "data: RailItemPrefDao — streamAll, upsert, delete, replaceAll"
```

---

### Task 2.3: Wire into `AppDatabase` + migration 30→31

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/data/AppDatabase.kt`
- Modify: `composeApp/src/androidMain/kotlin/io/nisfeb/talon/data/AppDatabase.android.kt`
- Modify: `composeApp/src/desktopMain/kotlin/io/nisfeb/talon/data/AppDatabase.desktop.kt`

- [ ] **Step 1: Add entity to commonMain**

In `composeApp/src/commonMain/kotlin/io/nisfeb/talon/data/AppDatabase.kt`:
- Append `RailItemPrefEntity::class` to the `@Database(entities = [...])` array.
- Bump `version = 30` to `version = 31`.
- Add `abstract fun railItemPrefs(): RailItemPrefDao` next to the other accessors (alphabetical with `railItemPrefs` after `notifyPrefs` is a good slot).

- [ ] **Step 2: Mirror on Android actual**

In `composeApp/src/androidMain/kotlin/io/nisfeb/talon/data/AppDatabase.android.kt`:
- Update the `@Database` annotation entities + version to match commonMain.
- Add `actual abstract fun railItemPrefs(): RailItemPrefDao`.
- Add the migration:

```kotlin
val MIGRATION_30_31 = object : Migration(30, 31) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS rail_item_prefs (
                itemName TEXT NOT NULL PRIMARY KEY,
                visible INTEGER NOT NULL
            )
        """.trimIndent())
    }
}
```

Append `MIGRATION_30_31` to the existing `addMigrations(...)` chain (search for `MIGRATION_29_30` to find the call).

- [ ] **Step 3: Mirror on Desktop actual**

In `composeApp/src/desktopMain/kotlin/io/nisfeb/talon/data/AppDatabase.desktop.kt`:
- Add `actual abstract fun railItemPrefs(): RailItemPrefDao`.
- No migration list change (desktop uses `fallbackToDestructiveMigration(dropAllTables = true)` per the existing pattern).

- [ ] **Step 4: Compile both targets + run tests**

```
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :composeApp:compileKotlinDesktop :composeApp:compileDebugKotlinAndroid :composeApp:desktopTest
```
Expected: BUILD SUCCESSFUL on both targets, all existing tests still pass.

If a test holds a literal version `30` somewhere, update it. Search:
```
grep -rn 'version = 30\|.version(30)' composeApp/src --include='*.kt'
```

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/data/AppDatabase.kt \
        composeApp/src/androidMain/kotlin/io/nisfeb/talon/data/AppDatabase.android.kt \
        composeApp/src/desktopMain/kotlin/io/nisfeb/talon/data/AppDatabase.desktop.kt
git commit -m "data: AppDatabase 30→31 — rail_item_prefs table"
```

---

### Task 2.4: `RailItemPrefDaoTest`

**Files:**
- Create: `composeApp/src/desktopTest/kotlin/io/nisfeb/talon/data/RailItemPrefDaoTest.kt`

- [ ] **Step 1: Write the tests**

The desktopTest source set already has an in-memory DB factory pattern — the most recent example is `composeApp/src/desktopTest/kotlin/io/nisfeb/talon/urbit/MediaBackfillWorkerTest.kt` (Phase 3 Task 3.2). Reuse the temp-file `Room.databaseBuilder<AppDatabase>(...).setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()` pattern.

```kotlin
package io.nisfeb.talon.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RailItemPrefDaoTest {

    private lateinit var tmpDir: File
    private lateinit var db: AppDatabase

    @BeforeTest
    fun setUp() {
        tmpDir = Files.createTempDirectory("rail-item-prefs-test-").toFile()
        val dbFile = File(tmpDir, "ui.db")
        db = Room.databaseBuilder<AppDatabase>(name = dbFile.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }

    @AfterTest
    fun tearDown() {
        db.close()
        tmpDir.deleteRecursively()
    }

    @Test
    fun `empty table streams empty list`() = runBlocking {
        val rows = db.railItemPrefs().streamAll().first()
        assertEquals(emptyList(), rows)
    }

    @Test
    fun `upsert then stream returns the row`() = runBlocking {
        db.railItemPrefs().upsert(RailItemPrefEntity("Settings", visible = false))
        val rows = db.railItemPrefs().streamAll().first()
        assertEquals(listOf(RailItemPrefEntity("Settings", false)), rows)
    }

    @Test
    fun `upsert with same key replaces the value`() = runBlocking {
        db.railItemPrefs().upsert(RailItemPrefEntity("Settings", visible = false))
        db.railItemPrefs().upsert(RailItemPrefEntity("Settings", visible = true))
        val rows = db.railItemPrefs().streamAll().first()
        assertEquals(listOf(RailItemPrefEntity("Settings", true)), rows)
    }

    @Test
    fun `delete removes the row`() = runBlocking {
        db.railItemPrefs().upsert(RailItemPrefEntity("Settings", visible = false))
        db.railItemPrefs().delete("Settings")
        val rows = db.railItemPrefs().streamAll().first()
        assertEquals(emptyList(), rows)
    }

    @Test
    fun `replaceAll wipes existing rows and inserts new ones`() = runBlocking {
        db.railItemPrefs().upsert(RailItemPrefEntity("Settings", visible = false))
        db.railItemPrefs().upsert(RailItemPrefEntity("Profile", visible = false))
        db.railItemPrefs().replaceAll(listOf(RailItemPrefEntity("Watchwords", visible = false)))
        val rows = db.railItemPrefs().streamAll().first().sortedBy { it.itemName }
        assertEquals(listOf(RailItemPrefEntity("Watchwords", false)), rows)
    }

    @Test
    fun `replaceAll with empty list clears the table`() = runBlocking {
        db.railItemPrefs().upsert(RailItemPrefEntity("Settings", visible = false))
        db.railItemPrefs().replaceAll(emptyList())
        val rows = db.railItemPrefs().streamAll().first()
        assertEquals(emptyList(), rows)
    }
}
```

- [ ] **Step 2: Run tests**

```
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :composeApp:desktopTest --tests 'io.nisfeb.talon.data.RailItemPrefDaoTest'
```
Expected: 6 tests pass.

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/desktopTest/kotlin/io/nisfeb/talon/data/RailItemPrefDaoTest.kt
git commit -m "test(data): RailItemPrefDao round-trip + replaceAll semantics"
```

---

## Phase 3 — `UiSettings.railVisibility`

### Task 3.1: Interface + InMemoryUiSettings

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/UiSettings.kt`

- [ ] **Step 1: Add to the interface**

After the existing `activeRailTab: StateFlow<RailTab>` declaration in the interface body:

```kotlin
/**
 * Visibility overrides for [RailItem]s. Sparse Map — only contains
 * rows for items the user has explicitly hidden. Read sites should
 * use [Map.isVisible] (in `RailItem.kt`) which defaults absent
 * items to true.
 *
 * Mutation goes through [SettingsSync.setRailItemVisibility] (which
 * writes the underlying Room table + pokes %settings for cross-
 * device sync). UiSettings exposes the read-side flow only.
 */
val railVisibility: StateFlow<Map<RailItem, Boolean>>
```

- [ ] **Step 2: Add to `InMemoryUiSettings`**

Inside `InMemoryUiSettings`, after the `_activeRailTab` block:

```kotlin
private val _railVisibility = MutableStateFlow(initialRailVisibility)
override val railVisibility: StateFlow<Map<RailItem, Boolean>> =
    _railVisibility.asStateFlow()
fun setRailVisibility(item: RailItem, visible: Boolean) {
    _railVisibility.value = _railVisibility.value.toMutableMap().apply {
        if (visible) remove(item) else this[item] = false
    }
}
```

(Note: `setRailVisibility` is a **non-override** test-only mutator on `InMemoryUiSettings`, since the interface intentionally has no setter. Production mutation goes through `SettingsSyncImpl`.)

Add the corresponding ctor parameter:

```kotlin
class InMemoryUiSettings(
    initialHide: Boolean = false,
    initialAccent: AccentSettings = AccentSettings(),
    initialGroupOrder: GroupChannelOrder = GroupChannelOrder.Recent,
    initialChatPaneListFraction: Float = 0.30f,
    initialActiveRailTab: RailTab = RailTab.Chats,
    initialRailVisibility: Map<RailItem, Boolean> = emptyMap(),
) : UiSettings { ... }
```

- [ ] **Step 3: Compile**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :composeApp:compileKotlinDesktop`
Expected: BUILD FAILS — `DesktopUiSettings` and `AndroidUiSettings` impls are now non-compliant. Fixed in Tasks 3.2 + 3.3. Don't commit yet.

---

### Task 3.2: `DesktopUiSettings` derives railVisibility from DAO

**Files:**
- Modify: `composeApp/src/desktopMain/kotlin/io/nisfeb/talon/ui/DesktopUiSettings.kt`

- [ ] **Step 1: Inspect current ctor + fields**

Run: `grep -n 'class DesktopUiSettings\|fun streamAll\|streamAll()' composeApp/src/desktopMain/kotlin/io/nisfeb/talon/ui/DesktopUiSettings.kt`

The constructor takes a `db: AppDatabase` (or some equivalent) — verify by reading the class header. If not, the constructor signature needs a new `db` parameter passed through from the `createUiSettings` factory in `desktopMain/.../compose/Main.kt`.

If `DesktopUiSettings` doesn't currently take a DB reference (it's a JSON-file-only impl), add one as a constructor parameter. The new ctor signature:

```kotlin
class DesktopUiSettings(
    file: File,
    db: AppDatabase,
    scope: CoroutineScope,
) : UiSettings { ... }
```

- [ ] **Step 2: Wire railVisibility flow**

Inside `DesktopUiSettings`, after the `_activeRailTab` block, add:

```kotlin
override val railVisibility: StateFlow<Map<RailItem, Boolean>> =
    db.railItemPrefs().streamAll()
        .map { rows ->
            rows.mapNotNull { row ->
                val item = railItemOrNull(row.itemName) ?: return@mapNotNull null
                item to row.visible
            }.toMap()
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())
```

Add imports as needed: `kotlinx.coroutines.CoroutineScope`, `kotlinx.coroutines.flow.SharingStarted`, `kotlinx.coroutines.flow.map`, `kotlinx.coroutines.flow.stateIn`, `io.nisfeb.talon.ui.RailItem`, `io.nisfeb.talon.ui.railItemOrNull`.

- [ ] **Step 3: Update factory**

In `composeApp/src/desktopMain/kotlin/io/nisfeb/talon/compose/Main.kt`, find where `DesktopUiSettings(...)` is constructed and pass the new `db` and `scope` parameters. The `db` is the `AppDatabase` already constructed in the same scope; `scope` is a `CoroutineScope` — use `SupervisorJob() + Dispatchers.Default` if no convenient scope exists, or reuse the `updateScope` already declared in `Main.kt`.

- [ ] **Step 4: Compile (don't run tests yet — Android still missing)**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :composeApp:compileKotlinDesktop`
Expected: BUILD SUCCESSFUL on desktop.

---

### Task 3.3: `AndroidUiSettings` derives railVisibility from DAO

**Files:**
- Modify: `composeApp/src/androidMain/kotlin/io/nisfeb/talon/ui/AndroidUiSettings.kt`

Mirror Task 3.2's pattern.

- [ ] **Step 1: Add the field**

Inside `AndroidUiSettings`, after the existing `_activeRailTab` block:

```kotlin
override val railVisibility: StateFlow<Map<RailItem, Boolean>> =
    db.railItemPrefs().streamAll()
        .map { rows ->
            rows.mapNotNull { row ->
                val item = railItemOrNull(row.itemName) ?: return@mapNotNull null
                item to row.visible
            }.toMap()
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())
```

- [ ] **Step 2: Update ctor + factory**

If `AndroidUiSettings` doesn't already take `db: AppDatabase` and `scope: CoroutineScope`, add them and update the construction site in `androidMain/.../TalonApplication.kt`. Use the existing per-ship `db` and `appScope`-style scope.

- [ ] **Step 3: Compile both targets + run tests**

```
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :composeApp:compileKotlinDesktop :composeApp:compileDebugKotlinAndroid :composeApp:desktopTest
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit Tasks 3.1 + 3.2 + 3.3 together**

These three depend on each other and the codebase doesn't compile in between. One commit:

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/UiSettings.kt \
        composeApp/src/desktopMain/kotlin/io/nisfeb/talon/ui/DesktopUiSettings.kt \
        composeApp/src/desktopMain/kotlin/io/nisfeb/talon/compose/Main.kt \
        composeApp/src/androidMain/kotlin/io/nisfeb/talon/ui/AndroidUiSettings.kt \
        composeApp/src/androidMain/kotlin/io/nisfeb/talon/TalonApplication.kt
git commit -m "ui(settings): railVisibility flow on UiSettings (read-only, derived from DAO)"
```

---

## Phase 4 — SettingsSync wiring

### Task 4.1: SettingsSync interface — `setRailItemVisibility`

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/urbit/SettingsSync.kt`

- [ ] **Step 1: Add the interface method**

After the existing `setNotifyLevel` declaration:

```kotlin
// ───────── rail visibility ─────────
// Toggles whether a RailItem is visible on the desktop sidebar.
// Default no-op for tests / hosts that don't sync.
suspend fun setRailItemVisibility(item: io.nisfeb.talon.ui.RailItem, visible: Boolean) {}
```

- [ ] **Step 2: Compile**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :composeApp:compileKotlinDesktop`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/urbit/SettingsSync.kt
git commit -m "urbit(settings): setRailItemVisibility on the narrow interface"
```

---

### Task 4.2: SettingsSyncImpl — bucket + setter

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/urbit/SettingsSyncImpl.kt`

- [ ] **Step 1: Add the bucket constant**

In the companion object's BUCKET_* constants block:

```kotlin
const val BUCKET_RAIL_ITEMS = "rail-items"
```

- [ ] **Step 2: Add to bootstrap()**

Find the `applyBucket(...)` chain in `bootstrap()` (around lines 108-126). Add a call alongside the others:

```kotlin
applyBucket(BUCKET_RAIL_ITEMS, deskMap[BUCKET_RAIL_ITEMS] as? JsonObject)
```

Place it adjacent to the existing structural-personalization buckets (e.g., after `BUCKET_NOTIFY_PREFS` or `BUCKET_FOLDER_MEMBERS`).

- [ ] **Step 3: Add setRailItemVisibility override**

In the body of `SettingsSyncImpl`, after the existing `setNotifyLevel`:

```kotlin
override suspend fun setRailItemVisibility(item: RailItem, visible: Boolean) {
    if (visible) {
        // Default-visible items are absent from the table + bucket;
        // deleting restores the default.
        db.railItemPrefs().delete(item.name)
        pokeDelEntry(BUCKET_RAIL_ITEMS, item.name)
    } else {
        db.railItemPrefs().upsert(RailItemPrefEntity(item.name, visible = false))
        pokePutEntry(
            BUCKET_RAIL_ITEMS, item.name,
            buildJsonObject { put("visible", false) },
        )
    }
}
```

Add imports: `io.nisfeb.talon.ui.RailItem`, `io.nisfeb.talon.data.RailItemPrefEntity`.

- [ ] **Step 4: Compile**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :composeApp:compileKotlinDesktop`
Expected: BUILD FAILS — `applyBucket` and `applyEntry` don't have BUCKET_RAIL_ITEMS branches yet. Don't commit; continue to Task 4.3.

---

### Task 4.3: SettingsSyncImpl — applyBucket + applyEntry + clearBucketLocally

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/urbit/SettingsSyncImpl.kt`

- [ ] **Step 1: Add applyBucket branch**

Find `internal suspend fun applyBucket(...)` and the existing `BUCKET_NOTIFY_PREFS ->` branch. Add:

```kotlin
BUCKET_RAIL_ITEMS -> {
    val rows = entries.orEmpty().mapNotNull { (k, v) ->
        val item = io.nisfeb.talon.ui.railItemOrNull(k) ?: return@mapNotNull null
        val visible = (unwrap(v) as? JsonObject)?.get("visible").asBool()
            ?: return@mapNotNull null
        // Skip explicit `true` entries — absence is the default and
        // we don't want stale `true` rows to drift the read site.
        if (visible) return@mapNotNull null
        RailItemPrefEntity(item.name, visible = false)
    }
    db.railItemPrefs().replaceAll(rows)
}
```

If `JsonElement?.asBool()` doesn't exist as a helper, search for `asLong\|asStr\|asInt` in the file for the convention and add an analogous extension. Likely the existing extensions are in `urbit/AsX.kt` or inline in `SettingsSyncImpl.kt`.

- [ ] **Step 2: Add applyEntry branch**

Find `private suspend fun applyEntry(...)` and the existing `BUCKET_NOTIFY_PREFS ->` branch. Add:

```kotlin
BUCKET_RAIL_ITEMS -> {
    val item = io.nisfeb.talon.ui.railItemOrNull(entry) ?: return
    val visible = (unwrap(value) as? JsonObject)?.get("visible").asBool() ?: return
    if (visible) {
        db.railItemPrefs().delete(item.name)
    } else {
        db.railItemPrefs().upsert(RailItemPrefEntity(item.name, visible = false))
    }
}
```

- [ ] **Step 3: Add clearBucketLocally branch**

Find the existing `clearBucketLocally(bucket: String)` function (search for `BUCKET_NOTIFY_PREFS -> db.notifyPrefs().replaceAll(emptyList())`). Add:

```kotlin
BUCKET_RAIL_ITEMS -> db.railItemPrefs().replaceAll(emptyList())
```

- [ ] **Step 4: Add removeEntry branch**

Find the `removeEntry(bucket, entry)` function (the `del-entry` apply path; search for `BUCKET_NOTIFY_PREFS -> db.notifyPrefs().clear(entry)`). Add:

```kotlin
BUCKET_RAIL_ITEMS -> db.railItemPrefs().delete(entry)
```

- [ ] **Step 5: Compile + run tests**

```
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :composeApp:compileKotlinDesktop :composeApp:compileDebugKotlinAndroid :composeApp:desktopTest
```
Expected: BUILD SUCCESSFUL on both, all existing tests pass.

- [ ] **Step 6: Commit Tasks 4.2 + 4.3 together**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/urbit/SettingsSyncImpl.kt
git commit -m "urbit(settings): BUCKET_RAIL_ITEMS — set + apply + bootstrap"
```

---

### Task 4.4: SettingsSyncRailItemsTest

**Files:**
- Create: `composeApp/src/desktopTest/kotlin/io/nisfeb/talon/urbit/SettingsSyncRailItemsTest.kt`

- [ ] **Step 1: Write the tests**

Pattern reference: `composeApp/src/desktopTest/kotlin/io/nisfeb/talon/urbit/SettingsSyncApplyBucketTest.kt`. Follow its in-memory-DB + `SettingsSyncImpl` construction style.

```kotlin
package io.nisfeb.talon.urbit

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.RailItemPrefEntity
import io.nisfeb.talon.ui.RailItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsSyncRailItemsTest {

    private lateinit var tmpDir: File
    private lateinit var db: AppDatabase
    private lateinit var sync: SettingsSyncImpl

    @BeforeTest
    fun setUp() {
        tmpDir = Files.createTempDirectory("settings-sync-rail-items-test-").toFile()
        val dbFile = File(tmpDir, "ui.db")
        db = Room.databaseBuilder<AppDatabase>(name = dbFile.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        // Mirror SettingsSyncApplyBucketTest.setUp — same fakes + ctor.
        sync = SettingsSyncImpl(
            db = db,
            aiSettings = FakeAiSettings(),
            dailyDigestSettings = FakeDailyDigest(),
            rearmDailyDigest = { /* no-op for these tests */ },
        )
    }

    @AfterTest
    fun tearDown() {
        db.close()
        tmpDir.deleteRecursively()
    }

    @Test
    fun `applyBucket persists a 'visible false' entry as a row`() = runBlocking {
        val incoming: JsonObject = buildJsonObject {
            put("Settings", buildJsonObject { put("visible", false) })
        }
        sync.applyBucket(SettingsSyncImpl.BUCKET_RAIL_ITEMS, incoming)
        val rows = db.railItemPrefs().streamAll().first()
        assertEquals(listOf(RailItemPrefEntity("Settings", false)), rows)
    }

    @Test
    fun `applyBucket skips 'visible true' entries (absence is the default)`() = runBlocking {
        val incoming: JsonObject = buildJsonObject {
            put("Settings", buildJsonObject { put("visible", true) })
        }
        sync.applyBucket(SettingsSyncImpl.BUCKET_RAIL_ITEMS, incoming)
        val rows = db.railItemPrefs().streamAll().first()
        assertEquals(emptyList(), rows)
    }

    @Test
    fun `applyBucket replace semantics — old rows that are absent get cleared`() = runBlocking {
        // Pre-seed an explicit hide
        db.railItemPrefs().upsert(RailItemPrefEntity("Watchwords", false))
        // New bucket with only Settings hidden
        val incoming: JsonObject = buildJsonObject {
            put("Settings", buildJsonObject { put("visible", false) })
        }
        sync.applyBucket(SettingsSyncImpl.BUCKET_RAIL_ITEMS, incoming)
        val rows = db.railItemPrefs().streamAll().first().sortedBy { it.itemName }
        assertEquals(listOf(RailItemPrefEntity("Settings", false)), rows)
    }

    @Test
    fun `applyBucket ignores unknown enum names (forward-compat)`() = runBlocking {
        val incoming: JsonObject = buildJsonObject {
            put("Settings", buildJsonObject { put("visible", false) })
            put("UnknownFutureItem", buildJsonObject { put("visible", false) })
        }
        sync.applyBucket(SettingsSyncImpl.BUCKET_RAIL_ITEMS, incoming)
        val rows = db.railItemPrefs().streamAll().first()
        assertEquals(listOf(RailItemPrefEntity("Settings", false)), rows)
    }

    @Test
    fun `applyEntry put visible=false adds a row`() = runBlocking {
        sync.applyEntry(
            SettingsSyncImpl.BUCKET_RAIL_ITEMS,
            "Settings",
            buildJsonObject { put("visible", false) },
        )
        val rows = db.railItemPrefs().streamAll().first()
        assertEquals(listOf(RailItemPrefEntity("Settings", false)), rows)
    }

    @Test
    fun `applyEntry put visible=true deletes the row`() = runBlocking {
        db.railItemPrefs().upsert(RailItemPrefEntity("Settings", false))
        sync.applyEntry(
            SettingsSyncImpl.BUCKET_RAIL_ITEMS,
            "Settings",
            buildJsonObject { put("visible", true) },
        )
        val rows = db.railItemPrefs().streamAll().first()
        assertEquals(emptyList(), rows)
    }

    @Test
    fun `removeEntry deletes the row`() = runBlocking {
        db.railItemPrefs().upsert(RailItemPrefEntity("Settings", false))
        sync.removeEntry(SettingsSyncImpl.BUCKET_RAIL_ITEMS, "Settings")
        val rows = db.railItemPrefs().streamAll().first()
        assertEquals(emptyList(), rows)
    }

    @Test
    fun `clearBucketLocally wipes all rows`() = runBlocking {
        db.railItemPrefs().upsert(RailItemPrefEntity("Settings", false))
        db.railItemPrefs().upsert(RailItemPrefEntity("Profile", false))
        sync.clearBucketLocally(SettingsSyncImpl.BUCKET_RAIL_ITEMS)
        val rows = db.railItemPrefs().streamAll().first()
        assertEquals(emptyList(), rows)
    }
}
```

`FakeAiSettings` and `FakeDailyDigest` already exist in `composeApp/src/desktopTest/kotlin/io/nisfeb/talon/urbit/` (used by `SettingsSyncApplyBucketTest`). Add the imports:

```kotlin
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.urbit.FakeAiSettings
import io.nisfeb.talon.urbit.FakeDailyDigest
```

The visibility of `applyBucket`, `applyEntry`, `removeEntry`, `clearBucketLocally` is already `internal` (per the existing `SettingsSyncApplyBucketTest` which calls them directly).

- [ ] **Step 2: Run tests**

```
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :composeApp:desktopTest --tests 'io.nisfeb.talon.urbit.SettingsSyncRailItemsTest'
```
Expected: 8 tests pass.

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/desktopTest/kotlin/io/nisfeb/talon/urbit/SettingsSyncRailItemsTest.kt
git commit -m "test(urbit): SettingsSync rail-items bucket — apply/entry/clear paths"
```

---

## Phase 5 — Rail rendering

### Task 5.1: Update DesktopShell to take enabledItems + onItemClicked

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/DesktopShell.kt`

- [ ] **Step 1: Inspect current API**

Run: `grep -n 'fun DesktopShell\|fun DesktopRail\|RailIconButton\|onSelectRailTab\|RailTab.entries' composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/DesktopShell.kt`

The current DesktopShell takes `activeRailTab: RailTab` + `onSelectRailTab: (RailTab) -> Unit`, and the `DesktopRail` private composable iterates `RailTab.entries`. Replace with `enabledItems` + `onItemClicked`.

- [ ] **Step 2: Update DesktopShell signature**

Replace the rail-related parameters:

```kotlin
@Composable
fun DesktopShell(
    activeRailTab: RailTab,
    enabledItems: List<RailItem>,
    onItemClicked: (RailItem) -> Unit,
    list: @Composable () -> Unit,
    detail: (@Composable () -> Unit)?,
    listFraction: Float,
    onListFractionChange: (Float) -> Unit,
    rightSidebar: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // ... same body, but pass enabledItems + onItemClicked into DesktopRail
}
```

(`onSelectRailTab` is gone — its job is now done by the App.kt-side router that translates `onItemClicked(railItem)` into `setActiveRailTab(railItem.toRailTab())` for pane tabs.)

- [ ] **Step 3: Update DesktopRail**

```kotlin
@Composable
private fun DesktopRail(
    activeTab: RailTab,
    enabledItems: List<RailItem>,
    onItemClicked: (RailItem) -> Unit,
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
            for (item in enabledItems) {
                val isSelected = item.isPaneTab && item.toRailTab() == activeTab
                RailIconButton(
                    item = item,
                    isSelected = isSelected,
                    onClick = { onItemClicked(item) },
                )
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}
```

- [ ] **Step 4: Update RailIconButton**

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RailIconButton(
    item: RailItem,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (isSelected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant
    val label = railLabel(item)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(24.dp)
                .background(
                    if (isSelected) MaterialTheme.colorScheme.primary
                    else Color.Transparent,
                ),
        )
        TooltipBox(
            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
            tooltip = { PlainTooltip { Text(label) } },
            state = rememberTooltipState(),
        ) {
            IconButton(onClick = onClick) {
                Icon(
                    imageVector = railIcon(item),
                    contentDescription = label,
                    tint = tint,
                )
            }
        }
    }
}
```

- [ ] **Step 5: Update railIcon + railLabel for the full RailItem set**

```kotlin
private fun railIcon(item: RailItem): ImageVector = when (item) {
    // All icons resolve to material-icons-core (verified by
    // unzipping the core jar at plan-write time). Safe with the
    // slim-jar strip; auditIconKeepList catches any drift.
    RailItem.Chats -> Icons.Filled.Home
    RailItem.Statuses -> Icons.Filled.Person
    RailItem.Bookmarks -> Icons.Filled.Star
    RailItem.Activity -> Icons.Filled.Notifications
    RailItem.Profile -> Icons.Filled.AccountCircle
    RailItem.Watchwords -> Icons.Filled.Search
    RailItem.TodaysBrief -> Icons.Filled.DateRange
    RailItem.Administration -> Icons.Filled.Build
    RailItem.Invites -> Icons.Filled.Email
    RailItem.Settings -> Icons.Filled.Settings
}

private fun railLabel(item: RailItem): String = when (item) {
    RailItem.Chats -> "Chats"
    RailItem.Statuses -> "Statuses"
    RailItem.Bookmarks -> "Bookmarks"
    RailItem.Activity -> "Activity"
    RailItem.Profile -> "My profile"
    RailItem.Watchwords -> "Watchwords"
    RailItem.TodaysBrief -> "Today's brief"
    RailItem.Administration -> "Administration"
    RailItem.Invites -> "Invites"
    RailItem.Settings -> "Settings"
}
```

All icon picks above are pre-verified to be in `material-icons-core-desktop-*.jar` at plan-write time (unzip listing confirmed Home, Person, Star, Notifications, AccountCircle, Search, DateRange, Build, Email, Settings — every one). No `iconsExtendedKeep` updates required for Phase 4.

Add the corresponding imports:

```kotlin
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
```

(Home / Person / Star / Notifications are already imported from Phase 2.)

- [ ] **Step 6: Compile**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :composeApp:compileKotlinDesktop :composeApp:compileDebugKotlinAndroid`
Expected: BUILD FAILS — App.kt + TalonApp.kt still call the old `onSelectRailTab` API. Don't commit yet; continue.

---

### Task 5.2: App.kt — compute enabledItems, route onItemClicked

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/compose/App.kt`

- [ ] **Step 1: Read the current DesktopShell call site**

Run: `grep -n 'DesktopShell(\|onSelectRailTab\|activeRailTab' composeApp/src/commonMain/kotlin/io/nisfeb/talon/compose/App.kt | head -10`

- [ ] **Step 2: Read railVisibility + dailyDigestEnabled**

Near where `activeRailTab` is read (`uiSettings.activeRailTab.collectAsState()`), add:

```kotlin
val railVisibility by uiSettings.railVisibility.collectAsState()
val digestState = dailyDigestSettings?.state?.collectAsState()?.value
val dailyDigestEnabled = digestState?.enabled == true
```

- [ ] **Step 3: Compute enabledItems**

After the `railVisibility` read:

```kotlin
val enabledItems: List<RailItem> = remember(railVisibility, dailyDigestEnabled) {
    RailItem.entries.filter { item ->
        val visible = railVisibility[item] ?: true
        val gateOk = item != RailItem.TodaysBrief || dailyDigestEnabled
        visible && gateOk
    }
}
```

- [ ] **Step 4: Build the onItemClicked router**

```kotlin
val onRailItemClicked: (RailItem) -> Unit = { item ->
    item.toRailTab()?.let { tab ->
        uiSettings.setActiveRailTab(tab)
    } ?: when (item) {
        RailItem.Profile -> showSelfProfile = true
        RailItem.Watchwords -> showWatchwords = true
        RailItem.TodaysBrief -> showDailyDigest = true
        RailItem.Administration -> showGroupAdminList = true
        RailItem.Invites -> showInvites = true
        RailItem.Settings -> showSettings = true
        // pane tabs handled above; never reaches here
        RailItem.Chats, RailItem.Statuses, RailItem.Bookmarks, RailItem.Activity -> Unit
    }
}
```

- [ ] **Step 5: Update DesktopShell call site**

Replace:
```kotlin
DesktopShell(
    activeRailTab = activeRailTab,
    onSelectRailTab = { uiSettings.setActiveRailTab(it) },
    ...
)
```
with:
```kotlin
DesktopShell(
    activeRailTab = activeRailTab,
    enabledItems = enabledItems,
    onItemClicked = onRailItemClicked,
    ...
)
```

Add imports: `io.nisfeb.talon.ui.RailItem`.

- [ ] **Step 6: Compile**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :composeApp:compileKotlinDesktop`
Expected: BUILD SUCCESSFUL.

---

### Task 5.3: TalonApp.kt — same router for Android

**Files:**
- Modify: `composeApp/src/androidMain/kotlin/io/nisfeb/talon/ui/TalonApp.kt`

TalonApp.kt isn't a DesktopShell consumer (Android phones don't render the rail). However, TalonApp needs to use the same `enabledItems` / `onRailItemClicked` if/when Android tablet landscape ever uses the same DesktopShell path. Today, tablet landscape on Android still uses TalonApp.kt's flat composition.

For v1 — keep it simple: TalonApp.kt doesn't currently render a rail at any breakpoint, so no DesktopShell call site to update. **Skip this task; nothing to change in TalonApp.kt for the rail rendering itself.** The settings sub-page (Phase 7) is reachable on Android too, and the prefs sync correctly via SettingsSync, so when an Android user opens their desktop app the prefs are already in place.

- [ ] **Step 1: Verify no compile error**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :composeApp:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL (no changes were needed).

- [ ] **Step 2: No commit needed for this task** — proceed to 5.4.

---

### Task 5.4: Extend DesktopShellTest for the customizable rail

**Files:**
- Modify: `composeApp/src/desktopTest/kotlin/io/nisfeb/talon/ui/DesktopShellTest.kt`

The existing `DesktopShellTest` (Phase 2) verifies the breakpoint, rail icons, and right-sidebar reservation with the old `RailTab.entries` rendering. Phase 4 changes the API; the existing tests need their call sites updated, AND we want new assertions for the customization behaviour.

- [ ] **Step 1: Update existing DesktopShellTest call sites**

Replace each `DesktopShell(activeRailTab = ..., onSelectRailTab = {}, ...)` invocation with the new API:

```kotlin
DesktopShell(
    activeRailTab = RailTab.Chats,
    enabledItems = RailItem.entries.toList(),
    onItemClicked = {},
    list = { Text("LIST") },
    detail = { Text("DETAIL") },
    listFraction = 0.30f,
    onListFractionChange = {},
)
```

Update the existing tests to use `enabledItems = RailItem.entries.toList()` so they still cover the "all items shown" case.

- [ ] **Step 2: Add new tests**

```kotlin
@OptIn(ExperimentalTestApi::class)
@Test
fun `rail renders only enabled items`() = runComposeUiTest {
    setContent {
        Box(Modifier.size(width = 1200.dp, height = 800.dp)) {
            DesktopShell(
                activeRailTab = RailTab.Chats,
                enabledItems = listOf(RailItem.Chats, RailItem.Bookmarks),
                onItemClicked = {},
                list = { Text("LIST") },
                detail = { Text("DETAIL") },
                listFraction = 0.30f,
                onListFractionChange = {},
            )
        }
    }
    onNodeWithContentDescription("Chats").assertExists()
    onNodeWithContentDescription("Bookmarks").assertExists()
    onNodeWithContentDescription("Statuses").assertDoesNotExist()
    onNodeWithContentDescription("Activity").assertDoesNotExist()
    onNodeWithContentDescription("Settings").assertDoesNotExist()
}

@OptIn(ExperimentalTestApi::class)
@Test
fun `clicking a modal item fires onItemClicked with the right enum`() = runComposeUiTest {
    var clicked: RailItem? = null
    setContent {
        Box(Modifier.size(width = 1200.dp, height = 800.dp)) {
            DesktopShell(
                activeRailTab = RailTab.Chats,
                enabledItems = listOf(RailItem.Chats, RailItem.Settings),
                onItemClicked = { clicked = it },
                list = { Text("LIST") },
                detail = { Text("DETAIL") },
                listFraction = 0.30f,
                onListFractionChange = {},
            )
        }
    }
    onNodeWithContentDescription("Settings").performClick()
    assertEquals(RailItem.Settings, clicked)
}

@OptIn(ExperimentalTestApi::class)
@Test
fun `clicking a pane-tab item fires onItemClicked with the right enum`() = runComposeUiTest {
    var clicked: RailItem? = null
    setContent {
        Box(Modifier.size(width = 1200.dp, height = 800.dp)) {
            DesktopShell(
                activeRailTab = RailTab.Chats,
                enabledItems = RailItem.entries.toList(),
                onItemClicked = { clicked = it },
                list = { Text("LIST") },
                detail = { Text("DETAIL") },
                listFraction = 0.30f,
                onListFractionChange = {},
            )
        }
    }
    onNodeWithContentDescription("Bookmarks").performClick()
    assertEquals(RailItem.Bookmarks, clicked)
}
```

Pin: the existing tests assert by `contentDescription` on the icons. The new icon labels (Phase 5.1's `railLabel`) must produce strings the tests find. If a label changes ("Chats" → something else), update the assertion strings. The label-icon-contentDescription chain stays in lockstep across the tests.

- [ ] **Step 3: Run tests**

```
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :composeApp:desktopTest --tests 'io.nisfeb.talon.ui.DesktopShellTest'
```
Expected: All Phase 2 tests still pass + 3 new tests pass.

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/desktopTest/kotlin/io/nisfeb/talon/ui/DesktopShellTest.kt
git commit -m "test(ui): DesktopShellTest covers enabledItems filter + onItemClicked routing"
```

---

### Task 5.5: Commit Phase 5

- [ ] **Step 1: Run full test suite + auditIconKeepList**

```
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :composeApp:check :composeApp:compileDebugKotlinAndroid
```
Expected: BUILD SUCCESSFUL — auditIconKeepList passes (any new icons land in core, otherwise follow the test failure to update keep-list).

If auditIconKeepList fails, add the missing class paths to `iconsExtendedKeep` in `composeApp/build.gradle.kts` and re-run.

- [ ] **Step 2: Commit Tasks 5.1 + 5.2 (any uncommitted changes from those tasks)**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/DesktopShell.kt \
        composeApp/src/commonMain/kotlin/io/nisfeb/talon/compose/App.kt \
        composeApp/build.gradle.kts # only if iconsExtendedKeep updated
git commit -m "ui(rail): render enabledItems + route onItemClicked"
```

---

## Phase 6 — Kebab content

### Task 6.1: DmListScreen — kebabItems param + conditional DropdownMenuItems

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/DmListScreen.kt`

- [ ] **Step 1: Add the kebabItems parameter**

In the `DmListScreen` signature, add (next to other navigation handlers):

```kotlin
/**
 * Items the kebab dropdown should show. App.kt computes this:
 *  - On wide windows: items NOT on the rail (the rail is the
 *    primary surface, the kebab is the overflow tray).
 *  - On compact windows: every item (the rail isn't visible).
 * Sign out is rendered separately and isn't a [RailItem]; it
 * always appears as the final entry in the dropdown.
 */
kebabItems: Set<RailItem> = RailItem.entries.toSet(),
```

Default = "all items shown" so existing callers (tests, etc.) keep their old behaviour without modification.

Add the import: `io.nisfeb.talon.ui.RailItem`.

- [ ] **Step 2: Wrap each kebab DropdownMenuItem in a conditional**

For each existing `DropdownMenuItem` in the `DropdownMenu` block (around lines 636-721 of DmListScreen.kt today), wrap with the matching `RailItem` check:

```kotlin
if (RailItem.Profile in kebabItems) {
    DropdownMenuItem(
        text = { Text("My profile") },
        onClick = {
            menuOpen = false
            onOpenSelfProfile()
        },
    )
}
if (RailItem.Statuses in kebabItems) {
    DropdownMenuItem(
        text = { Text("Statuses") },
        trailingIcon = { if (hasFreshStatuses) MenuBadgeDot() },
        onClick = {
            menuOpen = false
            menuSeen.markStatusesSeenAt(System.currentTimeMillis())
            onOpenStatusFeed()
        },
    )
}
if (RailItem.Bookmarks in kebabItems) {
    DropdownMenuItem(
        text = { Text("Bookmarks") },
        onClick = {
            menuOpen = false
            onOpenBookmarks()
        },
    )
}
if (RailItem.Activity in kebabItems) {
    DropdownMenuItem(
        text = { Text("Activity") },
        onClick = {
            menuOpen = false
            onOpenActivity()
        },
    )
}
if (RailItem.Watchwords in kebabItems) {
    DropdownMenuItem(
        text = { Text("Watchwords") },
        onClick = {
            menuOpen = false
            onOpenWatchwords()
        },
    )
}
if (RailItem.TodaysBrief in kebabItems && digestEnabled) {
    DropdownMenuItem(
        text = { Text("Today's brief") },
        trailingIcon = { if (hasFreshDigest) MenuBadgeDot() },
        onClick = {
            menuOpen = false
            latestDigest?.dateLocal?.let { menuSeen.markDigestSeen(it) }
            onOpenDigest()
        },
    )
}
if (RailItem.Administration in kebabItems) {
    DropdownMenuItem(
        text = { Text("Administration") },
        onClick = {
            menuOpen = false
            onOpenAdministration()
        },
    )
}
if (RailItem.Invites in kebabItems) {
    DropdownMenuItem(
        text = { Text("Invites") },
        trailingIcon = { if (hasPendingInvites) MenuBadgeDot() },
        onClick = {
            menuOpen = false
            menuSeen.markInvitesSeen(invitesSnapshot)
            onOpenInvites()
        },
    )
}
if (RailItem.Settings in kebabItems) {
    DropdownMenuItem(
        text = { Text("Settings") },
        onClick = {
            menuOpen = false
            onOpenSettings()
        },
    )
}
// Sign out is always rendered last, never gated by kebabItems —
// it's not a RailItem and never lives on the rail.
DropdownMenuItem(
    text = { Text("Sign out") },
    onClick = {
        menuOpen = false
        onSignOut()
    },
)
```

- [ ] **Step 3: Compile**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :composeApp:compileKotlinDesktop :composeApp:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/DmListScreen.kt
git commit -m "ui(chat-list): conditional kebab items via kebabItems set"
```

---

### Task 6.2: App.kt — pass kebabItems to DmListScreen

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/compose/App.kt`

- [ ] **Step 1: Compute kebabItems**

Inside the BoxWithConstraints block, after `enabledItems` (Task 5.2 added that):

```kotlin
val kebabItems: Set<RailItem> = remember(expanded, enabledItems) {
    if (expanded) {
        // Wide: kebab is the overflow tray. Show only items NOT on
        // the rail. Chats is always on the rail (and wouldn't make
        // sense in the kebab anyway), so the difference is the
        // pane-tab + modal items the user has hidden.
        RailItem.entries.filter { it !in enabledItems }.toSet()
    } else {
        // Compact: rail not visible. Kebab shows everything so
        // mobile users always reach every destination.
        RailItem.entries.toSet()
    }
}
```

- [ ] **Step 2: Update DmListScreen call site**

Find the `DmListScreen(...)` invocation inside `railListSlot` (the `RailTab.Chats ->` branch). Add the new param:

```kotlin
DmListScreen(
    ...
    kebabItems = kebabItems,
    ...
)
```

- [ ] **Step 3: Compile + run tests**

```
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :composeApp:compileKotlinDesktop :composeApp:compileDebugKotlinAndroid :composeApp:desktopTest
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/compose/App.kt
git commit -m "ui(app): pass breakpoint-aware kebabItems to DmListScreen"
```

---

### Task 6.3: TalonApp.kt — same wiring for Android

**Files:**
- Modify: `composeApp/src/androidMain/kotlin/io/nisfeb/talon/ui/TalonApp.kt`

Android phone is always compact, so kebab always shows all items. Pass `kebabItems = RailItem.entries.toSet()` (the default works, but explicit is clearer).

- [ ] **Step 1: Update DmListScreen call site**

Find `DmListScreen(` in TalonApp.kt and add:

```kotlin
kebabItems = RailItem.entries.toSet(),
```

- [ ] **Step 2: Compile**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :composeApp:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/androidMain/kotlin/io/nisfeb/talon/ui/TalonApp.kt
git commit -m "ui(android): pass full kebabItems set to DmListScreen on phone"
```

---

## Phase 7 — Sidebar settings sub-page

### Task 7.1: SidebarSettingsScreen composable

**Files:**
- Create: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/SidebarSettingsScreen.kt`

- [ ] **Step 1: Write the screen**

```kotlin
package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.ui.RailItem
import io.nisfeb.talon.ui.UiSettings
import io.nisfeb.talon.ui.isVisible
import io.nisfeb.talon.urbit.SettingsSync
import io.nisfeb.talon.urbit.TlonChatRepo
import kotlinx.coroutines.launch

@Composable
fun SidebarSettingsScreen(
    repo: TlonChatRepo,
    uiSettings: UiSettings,
    dailyDigestEnabled: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val railVisibility by uiSettings.railVisibility.collectAsState()
    Column(modifier = modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                "Sidebar",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        HorizontalDivider()
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(RailItem.entries.toList(), key = { it.name }) { item ->
                SidebarItemRow(
                    item = item,
                    visible = railVisibility.isVisible(item),
                    dailyDigestEnabled = dailyDigestEnabled,
                    onToggle = { newVisible ->
                        scope.launch {
                            runCatching {
                                repo.settingsSync?.setRailItemVisibility(item, newVisible)
                            }
                        }
                    },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun SidebarItemRow(
    item: RailItem,
    visible: Boolean,
    dailyDigestEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val (label, subtitle, fixedAlwaysOn, gatedOff) = sidebarRowState(item, dailyDigestEnabled)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        when {
            fixedAlwaysOn -> {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        "On",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
            gatedOff -> {
                Switch(checked = false, onCheckedChange = null, enabled = false)
            }
            else -> {
                Switch(checked = visible, onCheckedChange = { onToggle(it) })
            }
        }
    }
}

private data class SidebarRowState(
    val label: String,
    val subtitle: String?,
    val fixedAlwaysOn: Boolean,
    val gatedOff: Boolean,
)

private fun sidebarRowState(
    item: RailItem,
    dailyDigestEnabled: Boolean,
): SidebarRowState = when (item) {
    RailItem.Chats -> SidebarRowState(
        label = "Chats",
        subtitle = "Always on the sidebar",
        fixedAlwaysOn = true,
        gatedOff = false,
    )
    RailItem.TodaysBrief -> SidebarRowState(
        label = "Today's brief",
        subtitle = if (!dailyDigestEnabled) "Enable Daily Digest in Settings to use this" else null,
        fixedAlwaysOn = false,
        gatedOff = !dailyDigestEnabled,
    )
    RailItem.Statuses -> SidebarRowState("Statuses", null, false, false)
    RailItem.Bookmarks -> SidebarRowState("Bookmarks", null, false, false)
    RailItem.Activity -> SidebarRowState("Activity", null, false, false)
    RailItem.Profile -> SidebarRowState("My profile", null, false, false)
    RailItem.Watchwords -> SidebarRowState("Watchwords", null, false, false)
    RailItem.Administration -> SidebarRowState("Administration", null, false, false)
    RailItem.Invites -> SidebarRowState("Invites", null, false, false)
    RailItem.Settings -> SidebarRowState("Settings", null, false, false)
}
```

- [ ] **Step 2: Compile**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :composeApp:compileKotlinDesktop :composeApp:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/SidebarSettingsScreen.kt
git commit -m "ui(screens): SidebarSettingsScreen — toggle rail visibility per RailItem"
```

---

### Task 7.2: Wire Sidebar row into SettingsScreen

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/SettingsScreen.kt`

- [ ] **Step 1: Inspect SettingsScreen for the row pattern**

Run: `grep -n 'fun SettingsScreen\|RowItem\|onClick = { onOpen' composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/SettingsScreen.kt | head -20`

Identify the existing pattern for navigation rows (e.g., AI settings, Watchwords, Daily digest sub-pages — whichever exists).

- [ ] **Step 2: Add `onOpenSidebarSettings: () -> Unit` parameter**

In the `SettingsScreen` signature, add a new parameter (next to other `onOpen*` callbacks):

```kotlin
onOpenSidebarSettings: () -> Unit = {},
```

- [ ] **Step 3: Add the row**

In the body, insert a new navigation row (modeled on existing rows) labeled "Sidebar" with subtitle "Choose what shows in the rail":

```kotlin
SettingsRow(
    title = "Sidebar",
    subtitle = "Choose what shows in the rail",
    onClick = onOpenSidebarSettings,
)
```

(Adjust the row composable name + signature to match what the file actually uses.)

- [ ] **Step 4: Compile**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :composeApp:compileKotlinDesktop :composeApp:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/SettingsScreen.kt
git commit -m "ui(settings): add Sidebar navigation row"
```

---

### Task 7.3: App.kt + TalonApp.kt — wire SidebarSettingsScreen into navigation

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/compose/App.kt`
- Modify: `composeApp/src/androidMain/kotlin/io/nisfeb/talon/ui/TalonApp.kt`

- [ ] **Step 1: Add state var**

In App.kt, near other `show*` flags:

```kotlin
var showSidebarSettings by remember { mutableStateOf(false) }
```

Same in TalonApp.kt.

- [ ] **Step 2: Add a navigation when{} branch**

In App.kt's outer-when block (where other show* branches live, e.g. `showSettings -> SettingsScreen(...)`), add:

```kotlin
showSidebarSettings -> SidebarSettingsScreen(
    repo = repo,
    uiSettings = uiSettings,
    dailyDigestEnabled = dailyDigestEnabled,
    onBack = { showSidebarSettings = false },
)
```

(Place it near the existing `showSettings` branch — the ordering within the when{} is one earlier or one later than `showSettings`, doesn't matter.)

Same shape in TalonApp.kt.

- [ ] **Step 3: Wire the SettingsScreen → SidebarSettings navigation**

Wherever `SettingsScreen(...)` is called from App.kt and TalonApp.kt, add:

```kotlin
onOpenSidebarSettings = { showSidebarSettings = true },
```

- [ ] **Step 4: Add a back-handler**

Near the other `PlatformBackHandler`s in App.kt:

```kotlin
PlatformBackHandler(enabled = showSidebarSettings) {
    showSidebarSettings = false
}
```

Same in TalonApp.kt (using the Android `BackHandler`).

- [ ] **Step 5: Compile + run tests**

```
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :composeApp:compileKotlinDesktop :composeApp:compileDebugKotlinAndroid :composeApp:desktopTest
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/compose/App.kt \
        composeApp/src/androidMain/kotlin/io/nisfeb/talon/ui/TalonApp.kt
git commit -m "ui(app): wire SidebarSettingsScreen + back-handler"
```

---

## Phase 8 — Polish + ship

### Task 8.1: RELEASE.md smoke checklist

**Files:**
- Modify: `RELEASE.md`

- [ ] **Step 1: Append after the Phase 3 right-column block**

```markdown
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
```

- [ ] **Step 2: Commit**

```bash
git add RELEASE.md
git commit -m "docs(release): Phase 4 customizable rail smoke checklist"
```

---

### Task 8.2: Bump version + tag 0.11.0-rc1

**Files:**
- Modify: `composeApp/build.gradle.kts`

- [ ] **Step 1: Bump**

Find `talonVersionCode = N` / `talonVersionName = "0.10.0-rcN"` and change to:

```kotlin
val talonVersionCode = <next>
val talonVersionName = "0.11.0-rc1"
```

(Use whatever versionCode is one above the current head.)

- [ ] **Step 2: Compile + check**

```
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :composeApp:check :composeApp:compileDebugKotlinAndroid
```
Expected: BUILD SUCCESSFUL — auditIconKeepList passes, all tests pass.

- [ ] **Step 3: Commit + tag + push**

```bash
git add composeApp/build.gradle.kts
git commit -m "release: 0.11.0-rc1 — customizable rail items"
git tag -a v0.11.0-rc1 -m "Talon 0.11.0-rc1 — customizable rail (every kebab destination, user-toggle visibility, cross-device sync)"
git push github desktop-split-pane
git push github v0.11.0-rc1
```

CI builds and publishes the pre-release.

---

## Done criteria

- All listed components committed on the `desktop-split-pane` branch (or successor).
- `desktopTest` and `commonTest` green on the rc1 commit. `auditIconKeepList` passes.
- Manually verified per the RELEASE.md smoke checklist on the rc1 build.
- Cross-device sync verified: toggle on desktop → mobile reflects within seconds (via %settings reconnect-rescry from rc6+ era).
- Sign out remains in the kebab on both wide and compact regardless of every other toggle's state.
- Existing users on first launch see all 10 rail icons (or 9 if digest disabled). Their old muscle memory still works because the kebab on wide collapses to "Sign out" + nothing else (all items moved to rail).

After validation, fast-forward to master, drop the `-rc1`, re-tag `v0.11.0`. Phase 4 ships as the 0.11.0 minor.
