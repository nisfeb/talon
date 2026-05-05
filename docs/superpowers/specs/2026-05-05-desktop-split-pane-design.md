# Desktop split-pane + keyboard shortcuts — design

Date: 2026-05-05
Author: Claude (Opus 4.7) with sneagan
Branch: `desktop-split-pane`

## Goal

Make Talon feel native on desktop and tablet by replacing the
mobile-style "tap a chat → full-screen replace" navigation with a
list/detail split-pane on wide windows, and add a small set of
keyboard shortcuts users expect on a desktop chat client.

Out of scope for this pass: group navigation in split-pane (Phase 1
groups remain full-screen replace), three-pane (`list | chat |
thread`), drag-to-detach into a separate window, custom keybinding
configuration UI, right-click message context menus.

## Background

Today every Talon screen is a stack-style "replace" — tap a chat,
the chat list disappears and the conversation fills the window. On
a phone that's correct; on a 1920×1080 desktop window it wastes
real estate and reads as "this is just an Android app running on my
laptop." Users have asked for the standard list-on-left, content-
on-right layout that Slack / Telegram Desktop / Discord all use.

The current navigation owner is the `when {}` block in
`composeApp/src/commonMain/kotlin/io/nisfeb/talon/compose/App.kt`
that fans out from a few state flags (`openChat`, `openThreadParent`,
`showSettings`, etc.) to the appropriate screen composable. We're
not touching that — the split-pane is a *layout* concern, not a
navigation concern.

## Approach

A new composable `ChatPaneScaffold` reads the current window width
via `BoxWithConstraints`. Below 840dp it renders the same content
the old `when {}` block did (stacked, mobile behaviour). At
≥840dp it renders the chat-list slot and chat-detail slot
side-by-side with a drag handle between them, plus an
`EmptyChatPane` on the right when nothing is selected.

The breakpoint matches Material3's `WindowSizeClass.Expanded`
threshold and applies uniformly on Android and Desktop — Android
phones never cross it, tablets in landscape do, desktop windows do
unless dragged narrow. No platform-specific gating; the same
composable serves both targets.

## Components

### `commonMain/kotlin/io/nisfeb/talon/ui/ChatPaneScaffold.kt` (new)

```kotlin
@Composable
fun ChatPaneScaffold(
    list: @Composable () -> Unit,
    detail: (@Composable () -> Unit)?,
    listFraction: Float,
    onListFractionChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
)
```

Behaviour:

- Wraps content in `BoxWithConstraints`. Stores the width threshold
  (`840.dp`) as a constant.
- Below threshold: when `detail != null` render only `detail`,
  otherwise render only `list`. Equivalent to the current
  replace-style flow.
- At/above threshold: row layout with `list` taking
  `listFraction * maxWidth`, a 4dp drag handle, then `detail` (or
  `EmptyChatPane`) taking the rest.
- Drag handle: `Box` with pointer-input that translates horizontal
  drag deltas into fraction changes (clamped to `0.20..0.50`) and
  reports them via `onListFractionChange`. Hover/drag cursor on
  desktop via `Modifier.pointerHoverIcon(PointerIcon.Hand)`.

### `commonMain/kotlin/io/nisfeb/talon/ui/EmptyChatPane.kt` (new)

A centered column showing the Talon logo (existing
`talonLogoPainter()`) above a single line "Select a chat to begin"
in `MaterialTheme.typography.bodyMedium` /
`colorScheme.onSurfaceVariant`. Pure presentation — no state.

### `commonMain/kotlin/io/nisfeb/talon/notify/LastOpenChatStore.kt` (new)

```kotlin
interface LastOpenChatStore {
    val state: StateFlow<Map<String, String>>     // patp -> whom
    fun set(patp: String, whom: String)
    fun clear(patp: String)
}

object NoopLastOpenChatStore : LastOpenChatStore { ... }
```

In-memory default for tests; per-platform persistent impls:

- `androidMain/.../AndroidLastOpenChatStore.kt` — SharedPreferences-
  backed (`talon.last_open_chat`).
- `desktopMain/.../DesktopLastOpenChatStore.kt` — JSON file under
  `AppDirs.config / "last-open-chat.json"`. Format:
  `{ "<patp>": "<whom>", … }`. Atomic write via temp-file + rename.

`App.kt` reads `state[activeShip]` on first paint to seed the
right-pane chat. Writes happen via a `LaunchedEffect(openChat)` on
every flip. When the user signs out or switches ships, no write
clears the previous ship's entry — staying available for re-open.

### `commonMain/kotlin/io/nisfeb/talon/ui/KeyboardShortcuts.kt` (new)

```kotlin
sealed interface ShortcutAction {
    data object FocusSearch : ShortcutAction
    data object NewDm : ShortcutAction
    data object Back : ShortcutAction
    data object OpenSettings : ShortcutAction
    data class SwitchShip(val index: Int) : ShortcutAction
}

fun keyEventToShortcut(event: KeyEvent): ShortcutAction?
```

Pure mapping function. The KeyEvent inputs we accept:

| Combo                     | Action          |
|---------------------------|-----------------|
| Ctrl+K (Cmd+K on macOS)   | FocusSearch     |
| Ctrl+N (Cmd+N on macOS)   | NewDm           |
| Esc                       | Back            |
| Ctrl+, (Cmd+, on macOS)   | OpenSettings    |
| Ctrl+1..Ctrl+9            | SwitchShip(n-1) |

`KeyEventType.KeyDown` only — we ignore key-up so a held key
fires once per press. Detection of macOS happens via
`System.getProperty("os.name")` on desktop; Android always uses Ctrl
(no command key on physical Android keyboards).

### Wiring shortcut handler

Both targets attach `Modifier.onPreviewKeyEvent` to the root focus
host. Desktop: the `Window`'s content. Android:
`MainActivity`'s `setContent { … }` root. The handler:

```kotlin
.onPreviewKeyEvent { event ->
    val action = keyEventToShortcut(event) ?: return@onPreviewKeyEvent false
    when (action) {
        ShortcutAction.Back -> openChat = null   // or close dialog
        ShortcutAction.OpenSettings -> showSettings = true
        ShortcutAction.NewDm -> showNewDmRequest = true
        ShortcutAction.FocusSearch -> focusSearchRequest = true
        is ShortcutAction.SwitchShip -> {
            allShips.getOrNull(action.index)?.let { onSwitchShip(it) }
        }
    }
    true   // consume so it doesn't reach the focused widget
}
```

`focusSearchRequest` and `showNewDmRequest` are new
`MutableState<Boolean>` flags in `App.kt`. `DmListScreen` consumes
them via `LaunchedEffect(focusSearchRequest)` blocks that perform
the action and reset the flag.

### `UiSettings` extension

```kotlin
val chatPaneListFraction: StateFlow<Float>   // default 0.30
fun setChatPaneListFraction(value: Float)
```

Persisted alongside the other UiSettings entries. Range clamp to
`0.20..0.50` happens at the setter (not the getter) so a corrupt
file value gets normalised on next write. Tests cover both bounds
and the clamp.

## Data flow

```
                                       ┌──────────────────────┐
                                       │ LastOpenChatStore    │
                                       │ Map<patp,whom>       │
                                       └────────────▲─────────┘
                                                    │ write on flip
                                                    │ read on first paint
┌───────────────┐                          ┌────────┴─────────┐
│ KeyEvent      │ ── keyEventToShortcut ──▶│ App.kt           │
└───────────────┘                          │ openChat,        │
                                           │ showSettings, …  │
                                           └────────┬─────────┘
                                                    │
                                                    ▼
                                         ┌──────────────────────┐
                                         │ ChatPaneScaffold     │
                                         │ (BoxWithConstraints) │
                                         └─────┬─────────┬──────┘
                                          list │         │ detail
                                  ┌────────────▼──┐  ┌───▼─────────────┐
                                  │ DmListScreen  │  │ DmChatScreen    │
                                  │ (or whatever  │  │ ThreadScreen    │
                                  │  list view)   │  │ EmptyChatPane   │
                                  └───────────────┘  └─────────────────┘
```

## Error handling and edge cases

- **Chat persisted as last-open is no longer reachable** (deleted,
  unsubscribed, ship not in DB): the seed lookup returns null →
  `EmptyChatPane` renders. The persisted entry stays so a later
  bootstrap that reintroduces the row makes it open on next launch.
- **Window resized across the threshold mid-session**: scaffold
  re-renders with the new layout; `openChat` state survives so the
  same conversation displays correctly in either mode.
- **Ship switch on a wide window**: clear `openChat`, then on the
  next composition the seed lookup happens for the new ship's
  last-open. Avoids a flash of the old ship's chat in the new ship's
  context.
- **Keyboard focus during text input**: `onPreviewKeyEvent` runs
  *before* the focused widget receives the event, so we'd hijack
  Ctrl+K / Ctrl+N even while the user is typing. Mitigation: check
  `event.isCtrlPressed && !event.isAltPressed` and only consume
  combos that the editor itself doesn't bind (Ctrl+K/N/, are not
  standard text-edit combos on any platform).
- **macOS Cmd-key combos**: the mapping function already checks
  meta-vs-ctrl by OS detection. Android always uses Ctrl.
- **Drag handle below the breakpoint**: not rendered (no split, no
  handle). The persisted fraction survives across resize.

## Testing

- `commonTest/ChatPaneScaffoldTest.kt` — Compose UI tests with two
  `setContent` blocks at different `LocalDensity` constraints,
  verifying the right pane appears only when wide enough and that
  `EmptyChatPane` shows when `detail == null` in expanded mode.
- `commonTest/KeyboardShortcutsTest.kt` — table-driven tests of the
  pure `keyEventToShortcut` mapping, one row per combo, covering
  modifier mismatches and macOS Cmd-vs-Ctrl branching.
- `commonTest/LastOpenChatStoreTest.kt` — set/clear/observe through
  the in-memory impl.
- `desktopTest/DesktopLastOpenChatStoreTest.kt` — round-trip through
  a temp file, including atomic-replace on partial-write recovery.
- Manual smoke (added to release.md checklist): tablet landscape on
  Android (split-pane visible), desktop window dragged narrow
  (collapses to stacked), drag handle resize persists across launch,
  every shortcut from the table.

## Phases

Sequenced so each phase is mergeable to master independently if we
need to ship partial. Phases 2-4 each depend on Phase 1 (the
scaffold must exist before "back" or per-ship persistence has a
target) but are otherwise parallelisable.

1. **Scaffold + breakpoint** — `ChatPaneScaffold`, `EmptyChatPane`,
   wire into `App.kt`. No persistence yet (in-memory selection,
   default fraction). User can see the split working. ~half day.
2. **Last-open chat persistence** — `LastOpenChatStore` interface +
   both impls + UiSettings field for fraction. Wide-window opens
   to last chat. ~half day.
3. **Drag-to-resize** — drag handle, fraction state, persisted to
   UiSettings. ~half day.
4. **Keyboard shortcuts** — pure mapping + handler wiring on both
   targets + new `focusSearchRequest` / `showNewDmRequest` flags
   consumed by `DmListScreen`. ~half day.
5. **Polish + edge cases** — ship-switch flicker, threshold-crossing
   state preservation, focused-widget hijack mitigation, manual
   smoke matrix. ~half day.

Estimated total: 2-3 days of focused work.

## Open questions

None — full scope confirmed in brainstorming. (Right-click context
menus and broader desktop polish are explicitly fast-follow on a
separate branch.)
