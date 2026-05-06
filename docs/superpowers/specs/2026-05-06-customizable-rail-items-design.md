# Customizable rail items (Phase 4) design

**Status:** Design approved 2026-05-06. Ready for implementation plan.
**Builds on:** [Phase 2 desktop rail](./2026-05-05-desktop-rail-and-secondary-surfaces-design.md) (added the 4-tab rail at ≥840dp). This phase extends the rail into a user-configurable launcher bar covering everything in the chat-list kebab dropdown.

## Goal

Turn the desktop rail from a fixed 4-tab list-pane switcher into a user-configurable launcher bar that holds every destination currently in the chat-list kebab dropdown. User toggles visibility per item via a Settings sub-page. Visibility prefs sync per-ship across all logged-in clients via `%settings`.

## Non-goals (v1)

- Drag-to-reorder. Order is the canonical enum order. Reorder is a tracked follow-up — the existing folder-member reorder pattern is reusable when it lands.
- Long-press to hide / show from the rail itself. Settings sub-page is the only customization surface in v1.
- Plugin-style "register a new app on the rail." The universe is hard-coded to the 10 enum values described below. Adding a new app is an enum addition + new handler wire-up. The user's "more apps coming" framing is preserved as an extension point — the architecture doesn't paint us into a corner — but the v1 surface is fixed.
- Mobile rail. The rail isn't rendered on compact (existing DesktopShell behaviour). Mobile users always reach every item via the kebab.

## Architecture

### `RailItem` — the customizable universe

Superset of the existing `RailTab` enum. Every clickable rail entry is a `RailItem`. Some `RailItem`s map to a `RailTab` (the four list-pane tabs that swap left-pane content); the others are modal actions that fire the same handler the corresponding kebab item fires today.

```kotlin
enum class RailItem(val isPaneTab: Boolean) {
    // List-pane tabs — clicking switches activeRailTab; chat detail stays.
    Chats(true), Statuses(true), Bookmarks(true), Activity(true),

    // Modal actions — clicking opens the corresponding full-screen
    // screen (or sheet) the kebab item opens today. No "selected"
    // state.
    Profile(false), Watchwords(false), TodaysBrief(false),
    Administration(false), Invites(false), Settings(false),
    ;

    /** Maps a pane-tab item to its `RailTab` for the existing
     *  activeRailTab plumbing. Returns null for modal items. */
    fun toRailTab(): RailTab? = if (isPaneTab) RailTab.valueOf(name) else null
}
```

`RailTab` remains the single-source-of-truth for `activeRailTab` storage. The two enums coexist: `RailTab` describes "what's in the left pane right now," `RailItem` describes "what's clickable on the rail."

### Always-on / never-on items

- **`RailItem.Chats` is always on the rail.** It's the home — there's no other way back to the chat list once the user is in another screen. Settings sub-page renders it as an explainer row, not a toggle.
- **Sign out is never on the rail.** It's a destructive action; "destructive requires extra click" is a real safety property. Sign out lives only in the kebab dropdown. It's not a `RailItem`.

### `TodaysBrief` gate

The kebab today shows "Today's brief" only when the AI-feature `dailyDigestEnabled` flag is true. The rail respects the same gate, layered on user visibility:

```
rail shows TodaysBrief = (dailyDigestEnabled AND user-visible)
```

If the user disables daily digest at the AI-feature level, the icon disappears from the rail regardless of the visibility pref. The Settings sub-page row is greyed out + explainer ("Enable Daily Digest to use this") in that case.

### Rail rendering

`DesktopShell.DesktopRail` API change:

- `activeRailTab: RailTab` — kept (drives the selection pill on pane-tab items).
- `onSelectRailTab: (RailTab) -> Unit` — replaced by the more general `onItemClicked` below.
- New: `enabledItems: List<RailItem>` — canonical order, already filtered by user visibility + `TodaysBrief` gate.
- New: `onItemClicked: (RailItem) -> Unit` — fires for every click. App.kt is the router.

Selection-pill rule: `isSelected = item.isPaneTab && item.toRailTab() == activeRailTab`. Modal items never get the pill.

The rail's vertical scroll behaviour: at ≥10 icons (the v1 maximum) the column fits without scrolling at most window heights. If a future "more apps" addition pushes past what fits, the rail wraps in a `verticalScroll(rememberScrollState())`. Out of scope for v1 (`enabledItems.size <= 10`).

### Routing in App.kt

```kotlin
val onItemClicked: (RailItem) -> Unit = { item ->
    item.toRailTab()?.let { tab ->
        uiSettings.setActiveRailTab(tab)
    } ?: when (item) {
        RailItem.Profile -> showSelfProfile = true
        RailItem.Watchwords -> showWatchwords = true
        RailItem.TodaysBrief -> showDailyDigest = true
        RailItem.Administration -> showGroupAdminList = true
        RailItem.Invites -> showInvites = true
        RailItem.Settings -> showSettings = true
        // pane tabs handled above
        else -> Unit
    }
}
```

The modal handlers are exactly the existing kebab handlers — no behaviour change for users who tap from the kebab today.

### Kebab content (breakpoint-aware)

`DmListScreen` already takes a series of `onOpen*` handlers for kebab items. New parameter:

- `kebabItems: Set<RailItem>` — the items the kebab should show.

App.kt computes this:

```kotlin
val kebabItems: Set<RailItem> = if (expanded) {
    // Wide: kebab is the overflow tray — items NOT on the rail,
    // plus Sign out (which is rendered separately, always shown).
    RailItem.entries.filter { it !in enabledItems }.toSet()
} else {
    // Compact: rail not visible. Kebab shows everything so the
    // user has access to the full menu.
    RailItem.entries.toSet()
}
```

`DmListScreen.kt`'s `DropdownMenu` block iterates the kebab items in a fixed canonical order, rendering each as a `DropdownMenuItem` only if `kebabItems.contains(item)`. Sign out renders unconditionally as the final item.

When the user has all items on the rail (default), the kebab on wide collapses to a single "Sign out" entry.

## Persistence

### `SettingsSync` bucket: `rail-items`

Cross-device per-ship (same shape as `BUCKET_BOOKMARK_FOLDERS` / `BUCKET_NOTIFY_PREFS`). Schema:

```
{
  "rail-items": {
    "Settings": { "visible": false },
    "Watchwords": { "visible": false }
  }
}
```

Each entry's key is the `RailItem.name`. Value is a `JsonObject` with `visible: Boolean`. **Absence of an entry means visible.** This makes "all on by default" emerge naturally — a new install / fresh ship has no `rail-items` entries and renders every item.

When the user toggles an item off, an entry with `{visible: false}` is written + poked. **When the user toggles back on, the entry is deleted via `pokeDelEntry`** rather than re-written with `{visible: true}`. This keeps the wire form sparse: the bucket only ever contains the items the user has explicitly hidden, which makes it easy to reason about ("everything else is on by default"). Tests assert the effective rendered state, but the wire form is canonical: visible = absent.

### Storage layer: new Room table

For sync-able prefs the established pattern (notify prefs at `notify_prefs`, folder layout at `folders` + `folder_members`) is Room storage that `SettingsSyncImpl` writes directly. `UiSettings`'s existing local-only fields (`chatPaneListFraction`, `activeRailTab`) stay where they are — they're per-device preferences that don't need to sync. Rail visibility is a sync-able pref and gets its own table.

**`RailItemPrefEntity`:**

```kotlin
@Entity(tableName = "rail_item_prefs")
data class RailItemPrefEntity(
    @PrimaryKey val itemName: String,   // RailItem.name
    val visible: Boolean,
)
```

The table is sparse — only contains rows for items the user has explicitly hidden. Read sites that resolve "is this item visible" default `true` for absent rows.

**`RailItemPrefDao`:**

```kotlin
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

    @Transaction
    suspend fun replaceAll(rows: List<RailItemPrefEntity>) {
        clearAll()
        if (rows.isNotEmpty()) insertAll(rows)
    }
}
```

**`AppDatabase` migration:** version bump (current 30 → 31), new entity in `@Database(entities = [...])`, new `abstract fun railItemPrefs(): RailItemPrefDao`, `MIGRATION_30_31` creating the table. Same shape as the Phase 3 `MIGRATION_29_30` (which added `message_media`).

### `SettingsSyncImpl` additions

```kotlin
const val BUCKET_RAIL_ITEMS = "rail-items"

override suspend fun setRailItemVisibility(item: RailItem, visible: Boolean) {
    if (visible) {
        // Default-visible items are absent from the table; deleting
        // restores the default. Same on the wire — the bucket entry
        // is also deleted.
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

Inbound apply paths:

- `applyEntry(BUCKET_RAIL_ITEMS, entry, value)` — `entry` is `RailItem.name`, `value` is `{visible: bool}`. Writes through to the DAO. Unknown enum names are ignored (forward-compat).
- `applyBucket(BUCKET_RAIL_ITEMS, entries)` — `replaceAll` semantics. Map entries to `RailItemPrefEntity` rows (skipping the `visible: true` ones since absence already means visible) and call `replaceAll`.
- `bootstrap()` adds `applyBucket(BUCKET_RAIL_ITEMS, deskMap[BUCKET_RAIL_ITEMS] as? JsonObject)` to the existing list at lines 108-126 of `SettingsSyncImpl.kt`.

### `SettingsSync` interface

New method on the narrow interface (default no-op so non-Android platforms / tests don't have to override):

```kotlin
suspend fun setRailItemVisibility(item: RailItem, visible: Boolean) {}
```

### `UiSettings.railVisibility`

```kotlin
val railVisibility: StateFlow<Map<RailItem, Boolean>>
```

(Read-only flow on UiSettings — no setter. Mutation goes through `SettingsSyncImpl.setRailItemVisibility(...)`. Composables that need to toggle call `repo.settingsSync?.setRailItemVisibility(item, visible)` directly, same as the existing `setNotifyLevel` pattern.)

The flow is built by the per-platform `UiSettings` impl from the DAO:

```kotlin
private val _railVisibility: StateFlow<Map<RailItem, Boolean>> =
    db.railItemPrefs().streamAll()
        .map { rows ->
            rows.mapNotNull { row ->
                runCatching { RailItem.valueOf(row.itemName) }.getOrNull()?.to(row.visible)
            }.toMap()
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())
override val railVisibility: StateFlow<Map<RailItem, Boolean>> = _railVisibility
```

`Map<RailItem, Boolean>` is sparse — only contains explicit overrides. Default visibility (true) is computed at the read site:

```kotlin
fun Map<RailItem, Boolean>.isVisible(item: RailItem): Boolean = this[item] ?: true
```

### Computed `enabledItems`

A helper in `App.kt`:

```kotlin
val enabledItems: List<RailItem> = remember(railVisibility, dailyDigestEnabled) {
    RailItem.entries.filter { item ->
        // Explicit visibility (default true)
        val visible = railVisibility[item] ?: true
        // TodaysBrief gate
        val gateOk = item != RailItem.TodaysBrief || dailyDigestEnabled
        visible && gateOk
    }
}
```

The order is the enum's declaration order — Chats / Statuses / Bookmarks / Activity / Profile / Watchwords / TodaysBrief / Administration / Invites / Settings.

## Customization UI

### New `SidebarSettingsScreen`

Reachable from the existing `SettingsScreen` via a new row "Sidebar" (with a chevron and short subtitle "Choose what shows in the rail").

The sub-page is a `LazyColumn` of toggle rows, one per `RailItem` in canonical order. Each row:

- Leading icon (the same icon the rail / kebab uses for that item).
- Title (the same label).
- Optional subtitle / explainer text (for special-case rows: Chats, TodaysBrief).
- Trailing `Switch` reflecting the current visibility (default true).

Special cases:

- **Chats row**: explainer "Always on the rail." Switch is replaced by a static "On" badge.
- **TodaysBrief row** when `dailyDigestEnabled = false`: explainer "Enable Daily Digest in AI settings to use this." Switch is disabled (greyed, not interactive). When digest is enabled, the row behaves like every other.
- **All other rows**: the toggle works. Toggling fires `repo.settingsSync?.setRailItemVisibility(item, newValue)` — writes the `rail_item_prefs` DAO + pokes `%settings` for cross-device sync.

The screen has no Save / Apply button — toggles persist on change (matches the existing UI pattern for notification prefs, AI feature toggles, etc.).

### Mobile

The Sidebar settings screen is reachable on mobile too. A mobile-only user can still configure their rail for when they use desktop. The toggles do nothing visible on mobile (rail isn't rendered) but persist correctly via `%settings` so the next time they use desktop, their prefs are already in place.

## Data flow on visibility toggle

1. User toggles Settings off in the Sidebar settings screen.
2. `repo.settingsSync?.setRailItemVisibility(RailItem.Settings, false)` called.
3. `SettingsSyncImpl` writes to `db.railItemPrefs()` (Room) + `pokePutEntry(BUCKET_RAIL_ITEMS, "Settings", {visible: false})`.
4. Ship's `%settings` agent stores the entry.
5. Room's flow fires; `UiSettings.railVisibility` recomputes.
6. `enabledItems` recomputes; `Settings` is filtered out.
7. `DesktopShell.DesktopRail` re-renders without the Settings icon.
8. `kebabItems` recomputes; `Settings` enters the kebab.
9. `DmListScreen`'s kebab `DropdownMenu` re-renders with the Settings entry.

A second device subscribed to `%settings /desk/talon` receives the SSE put-entry event and applies it via `applySettingsEvent` → `applyEntry` → local DB write → flow emits → that device's rail also drops the Settings icon.

## Error handling

- **Network failure on poke**: `pokePutEntry` swallows errors with a log warning (existing pattern). Local state is correctly updated; the ship eventually syncs on reconnect via `bootstrap()` re-scry (which 0.8.12 / 0.8.13 made unconditional). Worst case: user toggles off on device A while offline, ship doesn't get the change, device B doesn't see it. On device A's next reconnect, the bootstrap re-scry pulls ship's stale state and overwrites — known limitation of the offline-write model.
- **Unknown enum value in `applyEntry`**: `RailItem.valueOf(name)` throws on unknown. Wrap in `runCatching` and ignore unknown entries (handles a future ship that's been talking to a newer version with a `RailItem` we don't know).
- **All items toggled off**: the rail still renders Chats (always-on). The user can never end up with a fully empty rail.

## Testing

| Test | Type | Coverage |
|---|---|---|
| `RailItemTest` | commonTest | `isPaneTab`, `toRailTab()` round-trip for every value. |
| `EnabledItemsTest` | commonTest | The compute-helper's behaviour: default-visible, explicit-false, TodaysBrief gate. |
| `SettingsSyncRailItemsTest` | desktopTest | `applyBucket(BUCKET_RAIL_ITEMS, ...)` round-trips a sparse Map. `setRailItemVisibility(true)` issues `pokeDelEntry`. `setRailItemVisibility(false)` issues `pokePutEntry`. Unknown enum value in incoming bucket is ignored. |
| `DesktopShellRailItemsTest` | desktopTest | Rail renders only the items in `enabledItems`. Selection pill on the active pane-tab. No pill on modal items. Click on a modal item fires `onItemClicked` with the right enum. |
| `KebabContentTest` | desktopTest | Wide kebab collapses to "Sign out" when all items visible. Wide kebab includes a toggled-off item. Compact kebab shows all items regardless. |

## Migration

- New `rail_item_prefs` Room table — `MIGRATION_30_31` (current AppDatabase version is 30 post-Phase-3). Single `CREATE TABLE` statement; no data backfill needed (table starts empty, absence = visible default).
- New SettingsSync bucket — no schema work, just bucket-key plumbing in `applyEntry` / `applyBucket` / `bootstrap()`.
- Existing users on first launch of the new version: empty `rail_item_prefs` table + no `rail-items` entries on ship → all items visible by default. Rail renders 10 icons (or 9 if digest disabled).
- The kebab on wide collapses to "Sign out" only on the same first launch — surprise factor: the user opens the kebab and sees only Sign out, expecting the old menu. Mitigation: the rail is now visible with all the items, so the menu's contents are still accessible — just from a different location. RELEASE.md smoke checklist will document the change.

## Out of scope (post-v1)

- Drag-to-reorder rail items.
- Long-press to hide.
- Per-window rail customization (e.g., different rails for different ships beyond what `%settings`'s per-ship nature gives us).
- Adding a new app: would need both an enum value AND a click handler in App.kt's router. Not a click-and-add affordance.
- Configurable rail width, icon size, or accent override.

## Done criteria

- All listed components committed to a feature branch off `desktop-split-pane` (or successor).
- All listed tests green.
- Manual smoke (per RELEASE.md): toggle each item off in Sidebar settings → icon disappears from rail → corresponding entry appears in kebab → toggle back on → reverses cleanly. Cross-device: toggle on desktop → mobile kebab updates within seconds (after rc6+ era reconnect-rescry). Toggle on mobile → desktop rail updates similarly.
- Rail stays at 10 icons or fewer for now (no scroll behaviour to verify).
- Sign out remains in the kebab on both wide and compact regardless of every other toggle's state.
