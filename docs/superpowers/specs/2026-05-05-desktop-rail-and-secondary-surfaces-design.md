# Desktop rail + secondary list-pane surfaces (Phase 2) — design

Date: 2026-05-05
Author: Claude (Opus 4.7) with sneagan
Branch: `desktop-split-pane` (continues Phase 1)
Predecessor spec: [`2026-05-05-desktop-split-pane-design.md`](2026-05-05-desktop-split-pane-design.md)

## Goal

Extend the desktop / tablet-landscape split-pane shipped in Phase 1
into a **column-based** layout: a vertical icon rail on the far left
that lets the user switch the left-pane content between Chats,
Statuses, Bookmarks, and Activity, while the right pane (chat detail)
stays put. Lay the foundation so a future Phase 3 can add a fourth
column on the right for threads, shared files, and links — Telegram
Desktop's right-sidebar pattern.

Mobile / compact (<840dp) behaviour is unchanged. Every existing
screen keeps working through its existing entry points (kebab menu,
`PlatformBackHandler`, etc.) so no Android user sees any difference.

## Out of scope this pass

- The right sidebar itself (Phase 3). We reserve the slot in the
  scaffold but render it null until then.
- Settings, Profile, Group home, Search results — they stay
  full-screen replace on both compact and wide. Rail does not get
  icons for them. We can add later if a clear case emerges.
- Compact-mode tabbed navigation (replacing the kebab menu with a
  bottom-tab-bar on phones). Out of scope; could be a follow-up.
- New surfaces under the rail. Phase 2 ships exactly four tabs:
  Chats, Statuses, Bookmarks, Activity.

## Background

Phase 1 introduced `ChatPaneScaffold`: at ≥840dp the chat list lives
on the left and the chat detail on the right. The user asked for the
non-chat list surfaces (Statuses, Bookmarks, Activity) to feel
similarly first-class on desktop instead of full-screen replacing the
chat surface. They also identified that the eventual right-sidebar
columns for threads / files / links suggest a column-based desktop UI
overall, and asked for the Phase 2 design to plan for that.

## Approach

A new `DesktopShell` composable wraps the existing `ChatPaneScaffold`
and adds:

1. A 64dp vertical icon rail on the far left, visible only at ≥840dp.
   Four icons: Chats, Statuses, Bookmarks, Activity. Tapping flips an
   `activeRailTab` state.
2. A `ChatPaneScaffold` whose `list` slot is driven by
   `activeRailTab`: `Chats → DmListScreen`, others → newly-extracted
   list-only composables for each surface.
3. A reserved (but unused) slot for the future right sidebar.

Below the breakpoint, `DesktopShell` is a passthrough — only the
existing chat surface renders, and the rail is not constructed at all
(no off-screen widgets, no measure-tree work). Mobile keeps reaching
Statuses / Bookmarks / Activity through the existing kebab menu and
its `showStatusFeed` / `showBookmarks` / `showActivity` flags.

## Layout breakpoints (forward-looking)

```
≥1280dp:  [ Rail | Active List | Chat Detail | Right Sidebar ]
≥840dp:   [ Rail | Active List | Chat Detail ]
<840dp:   [ Active surface ]                                   ← mobile path
```

Phase 2 implements columns 1-3 above the 840dp breakpoint. The right
sidebar (Phase 3) adds column 4 at ≥1280dp; we reserve the slot in
`ChatPaneScaffold` now so Phase 3 doesn't have to re-thread state.

## Components

### `commonMain/ui/DesktopShell.kt` (new)

```kotlin
@Composable
fun DesktopShell(
    activeRailTab: RailTab,
    onSelectRailTab: (RailTab) -> Unit,
    list: @Composable () -> Unit,
    detail: (@Composable () -> Unit)?,
    listFraction: Float,
    onListFractionChange: (Float) -> Unit,
    rightSidebar: (@Composable () -> Unit)? = null,  // Phase 3 hook
    modifier: Modifier = Modifier,
)
```

Below `ExpandedThreshold` (840dp): renders only `detail` if non-null,
otherwise `list`. Passthrough — no rail, no sidebar.

At/above the threshold: renders the rail in a fixed 64dp column
followed by `ChatPaneScaffold(list, detail, …, rightSidebar)`. The
scaffold itself stays responsible for the list/detail split + drag
handle; Phase 2 just wraps it.

### `commonMain/ui/RailTab.kt` (new)

```kotlin
enum class RailTab { Chats, Statuses, Bookmarks, Activity }
```

Single source of truth for the rail's switchable surfaces. Adding a
fifth tab in the future means adding an enum value and one branch in
`DesktopShell`'s list resolver — nothing else.

### `commonMain/ui/RailIcon.kt` (new, internal)

Renders one icon button: vector icon + tooltip + selected-state ring.
Tooltip uses Material3's `BasicTooltipBox`. The icons we already use
elsewhere in Talon all live in the slim Material Icons keep-list, so
this doesn't introduce new R8 strip risk on Android (rail isn't
rendered on Android phones anyway, but its imports affect the
universal APK).

### `commonMain/ui/screens/StatusFeedList.kt` (new)

Extracted list body from `StatusFeedScreen`. Renders only the rows
+ the inline self-status row, with no header / back arrow. Takes the
same params as today's screen minus `onBack`.

`StatusFeedScreen` becomes a thin wrapper around `StatusFeedList`
that adds the existing header. Existing call sites are byte-for-byte
unchanged — no API change to the screen, no `embedded: Boolean`
parameter (that pattern is the trap that breaks mobile back arrows).

### `commonMain/ui/screens/BookmarksList.kt` (new)

Same shape as `StatusFeedList` but for `BookmarksScreen`. Extracted
list body, screen wrapper unchanged.

### `commonMain/ui/screens/ActivityList.kt` (new)

Same shape, for `ActivityFeedScreen`.

### `UiSettings` extension

Add `activeRailTab: StateFlow<RailTab>` + `setActiveRailTab(tab)`.
Persists per ship across launches alongside the other UI prefs.
Default: `RailTab.Chats`. Stored as the enum's `name` string in JSON
/ SharedPreferences (same pattern as `groupChannelOrder`).

### `App.kt` integration

Replace the direct `ChatPaneScaffold(...)` call with
`DesktopShell(...)`. The new shell receives:

- `activeRailTab` from `uiSettings.activeRailTab.collectAsState()`
- `onSelectRailTab` setting the same flow
- `list` slot resolved from `activeRailTab` via a `when {}` block
- `detail`, `listFraction`, `onListFractionChange` unchanged from
  Phase 1

The kebab-menu paths (`showStatusFeed = true` etc.) stay the
mobile / compact-window navigation. On wide windows the same kebab
entry flips `activeRailTab` instead of `showStatusFeed`; same
drawer item, same icon, different effect at different breakpoints.
The compact-mode flags (`showStatusFeed`, `showBookmarks`,
`showActivity`) are never set on wide. The rail-tab state is never
set on compact. Each navigation surface owns its own state; the
breakpoint at which we are determines which one the kebab fires.

This avoids the trap of having both flag families set simultaneously
— a wide-then-narrow resize would otherwise leave a compact-mode
flag stuck on under the rail.

The `when {}` resolver decides which list to render based on
`activeRailTab` AND the existing flags (`showSettings` etc.) which
still produce full-screen replace on either breakpoint. Order of
checks: full-screen-replace flags first, then rail tab.

## Data flow

```
┌──────────────┐                ┌─────────────────────┐
│ UiSettings   │ activeRailTab  │ DesktopShell        │
│ (per ship)   ├───────────────▶│                     │
└──────────────┘                │ ┌──┐ ┌─────────────┐│
        ▲                       │ │R │ │             ││
        │ setActiveRailTab      │ │a │ │ Active List ││
        │                       │ │i │ │             ││
   rail click                   │ │l │ │             ││
        ▲                       │ └──┘ └─────────────┘│
        │                       └────────┬────────────┘
        │                                │
        │                                ▼
        │                       ┌─────────────────────┐
        │                       │ ChatPaneScaffold    │
        └───────────────────────│ list = (resolved)   │
                                │ detail = openChat   │
                                │ rightSidebar = null │ ← Phase 3
                                └─────────────────────┘
```

`activeRailTab` and `openChat` are independent. Tapping a row in any
tab that "opens conversation" sets `openChat`; the right pane updates
but the rail tab stays where it was. The user can browse Activity
while a chat is selected on the right.

## Persistence

`UiSettings.activeRailTab` joins the existing per-ship settings.
First-launch default is `RailTab.Chats`. After a switch, the value
survives app restart and ship-switch (per ship). Like
`chatPaneListFraction`, it's purely a desktop concern but lives in
the shared `UiSettings` interface so the JSON / SharedPreferences
impls handle it without diverging.

## Mobile / compact contract

The four risks that need to stay locked down:

1. **Rail never renders at compact widths.** `DesktopShell`'s
   `BoxWithConstraints` gate is the only entry point for the rail —
   the rail composable is called only inside the expanded branch.

2. **`activeRailTab` state never leaks into mobile nav.** Mobile
   reaches Statuses/Bookmarks/Activity via `showStatusFeed` /
   `showBookmarks` / `showActivity` from the kebab menu, same as
   today. Those flags continue to drive full-screen replace and are
   not consulted by `DesktopShell` below the breakpoint.

3. **Existing screens (`StatusFeedScreen`, `BookmarksScreen`,
   `ActivityFeedScreen`) keep their existing public API, signatures,
   composition shape, and back-button behaviour.** The new
   `*List.kt` composables are extracted *list bodies*, called by
   both the screen wrapper (mobile path) and the rail (wide path).
   No `embedded: Boolean` parameter; no header conditionals.

4. **`PlatformBackHandler` flow is untouched.** Hardware back on
   Android still returns to the chat list from
   StatusFeedScreen/BookmarksScreen/ActivityFeedScreen via the
   existing `onBack` callback. No state changes there.

## Testing

- `commonTest/RailTabTest.kt` — pure-data tests for the enum (next-tab
  cycling helper if any, name round-trip for persistence).
- `desktopTest/DesktopShellTest.kt` — Compose UI tests for the
  breakpoint behaviour:
  * narrow window: rail does NOT render
  * wide window: rail renders 64dp wide on the left
  * tapping a rail icon fires `onSelectRailTab`
  * `list` slot content changes when `activeRailTab` changes
  * `rightSidebar = null` doesn't affect layout (Phase 3 forward-compat)
- `desktopTest/StatusFeedListTest.kt` /
  `desktopTest/BookmarksListTest.kt` /
  `desktopTest/ActivityListTest.kt` — Compose UI tests that the
  extracted list bodies render the same row data as the existing
  screen wrappers do. Snapshot-style: same DB state in, same row
  IDs visible.
- `desktopTest/UiSettingsActiveRailTabTest.kt` — round-trip of the
  new field through `DesktopUiSettings`'s JSON file, plus the
  enum-name parse-fallback to `Chats` on a corrupt value.

## Phases

1. **Persistence + types** (~half day): `RailTab` enum, `UiSettings`
   field across all three impls (interface, in-memory, desktop JSON,
   Android SharedPrefs). Tests for round-trip.

2. **List body extraction** (~1 day): `StatusFeedList`,
   `BookmarksList`, `ActivityList`. Refactor existing screens to
   delegate to them. Snapshot tests verify mobile path unchanged.

3. **`DesktopShell` + rail** (~1 day): new composable, rail
   composable, threshold gating. UI tests for narrow + wide
   behaviour.

4. **App.kt integration** (~half day): swap
   `ChatPaneScaffold(...)` → `DesktopShell(...)`. Wire kebab-menu
   entries to flip the rail tab on wide and the existing flags on
   compact (the dual-path bit). Manual smoke matrix.

5. **Polish** (~half day): rail icon tooltips, selected-state visual,
   updates to `RELEASE.md` smoke checklist, version bump to
   `0.9.0-rc3`. Keyboard shortcuts for rail navigation (e.g.
   `Ctrl+Shift+1..4`) are deliberately deferred — Phase 1's keyboard
   layer can pick them up in a follow-up if users ask.

Estimated total: 3-3.5 days. Same branch as Phase 1; ships under the
existing `0.9.0-rc*` series, with Phase 1 + Phase 2 going GA together
as `0.9.0`.

## Open questions

None — the user has confirmed:
- Tab placement: vertical rail (option B from brainstorming)
- State model: `activeRailTab` independent of `openChat`
- Mobile: full backwards-compat via kebab-menu + existing screen
  wrappers
- Right sidebar: reserved slot now, fill in Phase 3
