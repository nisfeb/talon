# Right column for threads, group info, and media drilldowns (Phase 3)

**Status:** Design approved 2026-05-05. Ready for implementation plan.
**Builds on:** [Phase 2 design](./2026-05-05-desktop-rail-and-secondary-surfaces-design.md), which reserved the `rightSidebar` slot in `DesktopShell` for this work.

## Goal

Fill the right column at ≥840dp with a single shared host that renders one of three things at a time: a thread, a group-info panel, or a category drilldown from that panel. Mobile (<840dp) gets the same conceptual flow linearised as full-screen replaces with a back-stack. Add an info icon to the chat header on both platforms as the entry point.

The group-info panel matches Telegram's shared-media stats UX — counts of photos, videos, gifs, voice messages, audio, files, and links, with each count drilling down into a list of the items.

## Non-goals (v1)

- DM / 1:1 group-info content. Architecture is chat-shape-aware so DM support is a content-only add later.
- Pinned messages, inline member-list rendering (defers to existing `GroupAdminScreen`).
- Search-within-category, sorting controls.
- Cursor-anchored tooltips on the rail (still deferred from Phase 2).

## Architecture

### Right-pane state machine

A single sealed sum type owns what the right pane shows:

```kotlin
sealed interface RightPaneContent {
    data class Thread(
        val whom: String,
        val parentId: String,
        val replyAnchor: String?,
    ) : RightPaneContent
    data class GroupInfo(val whom: String) : RightPaneContent
    data class GroupInfoDrilldown(
        val whom: String,
        val category: MediaCategory,
    ) : RightPaneContent
}
```

`null` = pane closed.

Mutually exclusive: opening a thread closes group info, opening group info closes a thread. Within group info, the user can sub-navigate to a drilldown without leaving the pane.

State storage in `App.kt` stays flat — same shape as today: `openThreadParent: String?` / `openThreadReplyAnchor: String?` keep their existing semantics; we add `groupInfoOpenFor: String?` and `groupInfoDrilldown: MediaCategory?`. The `RightPaneContent` sealed type is computed at render time from those flat vars; it's the *content delivered* to `RightPaneHost`, not the persistence model:

```kotlin
val rightPaneContent: RightPaneContent? = when {
    openThreadParent != null -> RightPaneContent.Thread(...)
    groupInfoDrilldown != null -> RightPaneContent.GroupInfoDrilldown(...)
    groupInfoOpenFor != null -> RightPaneContent.GroupInfo(...)
    else -> null
}
```

Mutual exclusion is enforced at the *write* sites: opening a thread clears `groupInfoOpenFor` and `groupInfoDrilldown`; opening group info clears `openThreadParent` and `openThreadReplyAnchor`. This keeps the existing thread navigation handlers working unchanged for the compact / mobile path.

### Breakpoint behaviour

At ≥840dp, `RightPaneContent` is rendered into `DesktopShell.rightSidebar`. The `DesktopShell` Phase-2 implementation already passes `rightSidebar = null` and refuses to render an empty fourth column — Phase 3 simply starts passing a non-null slot.

At <840dp, the right pane is full-screen, navigated as a stack via `PlatformBackHandler`:
- Back from `GroupInfoDrilldown` → `GroupInfo`
- Back from `GroupInfo` → chat
- Back from `Thread` → chat (unchanged from today)

Same conceptual states as desktop, linearised.

### Components

| File | Responsibility |
|---|---|
| `commonMain/.../ui/RightPaneHost.kt` (new) | Pure dispatch: takes a `RightPaneContent` and renders the matching pane. Wraps the content with a close button on wide; on compact it's host-less and the full-screen wrappers handle their own headers. |
| `commonMain/.../ui/screens/GroupInfoPane.kt` (new) | Header (avatar + name + member count) → mute toggle row → "View members (N)" tap row → media stats grid → "Leave group" button. Pure UI; takes `Flow`s for counts and a `NotifyPreferenceEntity`. |
| `commonMain/.../ui/screens/GroupInfoScreen.kt` (new) | Full-screen wrapper around `GroupInfoPane` for mobile. Owns `onBack`, header markup, `windowInsetsPadding`. Same wrapper-vs-list shape as Phase 2's screen extractions. |
| `commonMain/.../ui/screens/MediaListPane.kt` (new) | LazyColumn for one `MediaCategory`. Tap → image viewer / video player / voice player / link / file open. Reuses existing `viewerImageUrl`, `LocalInlineMediaPlayer`, `uriHandler.openUri` machinery. |
| `commonMain/.../ui/screens/MediaListScreen.kt` (new) | Full-screen wrapper around `MediaListPane` for mobile. |
| `commonMain/.../ui/screens/ThreadScreen.kt` (modify) | Refactor to extract a headerless `ThreadList` body (same shape as Phase 2's `StatusFeedList` split) so `RightPaneHost` can render the thread inside the wide-pane host without the screen-level header. Mobile path keeps `ThreadScreen` byte-for-byte. |
| `commonMain/.../ui/screens/DmChatScreen.kt` (modify) | Add an info icon button in the chat header next to the existing kebab. Single click handler emitted as a new `onOpenGroupInfo: () -> Unit` parameter. The icon is shown only when the chat shape is group or club; hidden for 1:1 DMs (v1 scope — DM info content not built). This is a chat-shape predicate, not a platform `Capabilities.kt` flag. |
| `commonMain/.../compose/App.kt` (modify) | New `RightPaneContent?` state, glue to wire chat-header info-icon → `RightPaneContent.GroupInfo`, glue to migrate `openThreadParent` → `RightPaneContent.Thread` on wide, two new `PlatformBackHandler`s for the mobile stack. |
| `commonMain/.../urbit/MediaClassifier.kt` (new) | Pure function `extractMedia(MessageEntity): List<MessageMediaEntity>`. Walks the `Story` parts and applies the categorisation rules. Single function, exhaustively tested. |

## Data model

### `MessageMediaEntity`

```kotlin
@Entity(
    tableName = "message_media",
    primaryKeys = ["whom", "messageId", "url"],
    indices = [Index(value = ["whom", "category", "sentMs"])],
)
data class MessageMediaEntity(
    val whom: String,
    val messageId: String,
    val url: String,
    val category: String,    // MediaCategory.name
    val displayText: String?, // link's user-visible label (e.g. "🎙 Voice 12s")
    val sentMs: Long,
    val author: String,
)

enum class MediaCategory { Photo, Video, Gif, Voice, Audio, File, Link }
```

Composite primary key on `(whom, messageId, url)` so a single message can contribute multiple media rows (e.g., a message with three image URLs lands as three rows). The `(whom, category, sentMs)` index covers both the count query (`SELECT category, COUNT(*) ... GROUP BY`) and the drilldown query (`WHERE whom = ? AND category = ? ORDER BY sentMs DESC`).

### `MessageMediaDao`

```kotlin
@Dao
interface MessageMediaDao {
    @Query("""SELECT category, COUNT(*) AS n
              FROM message_media WHERE whom = :whom
              GROUP BY category""")
    fun streamCounts(whom: String): Flow<List<CategoryCount>>

    @Query("""SELECT * FROM message_media
              WHERE whom = :whom AND category = :category
              ORDER BY sentMs DESC LIMIT :limit OFFSET :offset""")
    fun streamCategory(
        whom: String,
        category: String,
        limit: Int,
        offset: Int,
    ): Flow<List<MessageMediaEntity>>

    @Transaction
    suspend fun replaceForMessage(
        whom: String,
        messageId: String,
        rows: List<MessageMediaEntity>,
    ) {
        deleteForMessage(whom, messageId)
        if (rows.isNotEmpty()) insertAll(rows)
    }

    @Query("DELETE FROM message_media WHERE whom = :whom AND messageId = :id")
    suspend fun deleteForMessage(whom: String, id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<MessageMediaEntity>)

    @Query("SELECT COUNT(*) FROM message_media")
    suspend fun totalCount(): Int   // backfill idempotency check
}

data class CategoryCount(val category: String, val n: Int)
```

The `Flow<List<CategoryCount>>` shape (rather than `Flow<Map>`) is what Room emits naturally; the `GroupInfoPane` collects and converts to a `Map<MediaCategory, Int>` for rendering.

### Ingest hooks

`PostIngest.kt` already inserts every message into `messages`. We add a single call site that, in the same transaction:

1. Calls `MediaClassifier.extractMedia(message)` to get the rows for the message.
2. Calls `messageMediaDao.replaceForMessage(whom, id, rows)`.

Edits go through the same path (Tlon edits replace the message body). Deletions cascade through `replaceForMessage(whom, id, emptyList())`.

### Backfill

On first launch after the upgrade ships, a one-shot worker:

1. Checks `messageMediaDao.totalCount()`. If non-zero, skip — backfill already ran.
2. Iterates over all `messages` rows in chunks of 1000, classifying each, and writing rows in batches.
3. Commits each chunk in its own transaction so the UI stays responsive.
4. Marks completion in a single-row preferences entry (`backfillCompletedAt: Long`) so we never re-run.

While backfill is running, `GroupInfoPane` shows a "Indexing N messages…" affordance above the stats grid. The grid still updates live as the backfill commits chunks (Room's flows fire as soon as inserts land).

The worker runs on a background `Dispatchers.IO` coroutine launched at app start, *after* the initial navigation has rendered, so the user doesn't see a blank screen.

## Categorisation

`MediaClassifier.extractMedia(message: MessageEntity): List<MessageMediaEntity>` walks the parsed `Story` and applies these rules in order. The first matching bucket wins.

| Bucket | Rule |
|---|---|
| **Photo** | `StoryPart.Image` (first-class image part) OR URL ending in `.jpg / .jpeg / .png / .webp` |
| **Gif** | URL ends in `.gif` (regardless of whether annotated as image or link) |
| **Video** | URL ends in `.mp4 / .webm / .mov / .m4v` |
| **Voice** | Link display text starts with the `🎙` emoji (Talon's voice convention from `DmChatScreen.kt`) AND URL has an audio extension |
| **Audio** | Audio extension (`.mp3 / .m4a / .aac / .wav / .ogg / .flac`) and not voice |
| **File** | URL ends in `.pdf / .zip / .doc / .docx / .xls / .xlsx / .ppt / .pptx / .txt / .csv / .tar / .gz / .7z / .rar` |
| **Link** | Any other URL annotation |

Extension matching is case-insensitive and ignores query strings + fragment anchors (the existing `classifyMediaUrl` in `StoryRenderer.kt` already does this — reuse the lowercase-and-trim helper).

The extension lists are `private val` constants in `MediaClassifier.kt`. Adjusting a bucket is a one-line change.

A single message can produce multiple rows (e.g., three image URLs in one post → three `MessageMediaEntity`s). The composite primary key `(whom, messageId, url)` handles this; duplicate URLs in the same message are de-duplicated to one row.

## Group info pane content (Telegram-light)

```
┌──────────────────────────────┐
│  [avatar]                  ✕ │
│  Group Name                  │
│  47 members                  │
│                              │
│  ─────────────────────────   │
│  🔔 Mute                  ▢  │
│  👥 View members (47)     ›  │
│  ─────────────────────────   │
│  Shared media                │
│  ┌────┬────┬────┬────┐      │
│  │ 12 │  3 │  8 │  1 │      │
│  │📷  │🎥  │🎞  │🎙  │      │
│  ├────┼────┼────┼────┤      │
│  │  5 │ 22 │ 14 │    │      │
│  │🎵  │📄  │🔗  │    │      │
│  └────┴────┴────┴────┘      │
│                              │
│  ─────────────────────────   │
│  🚪 Leave group              │
└──────────────────────────────┘
```

Buckets with count 0 are hidden — only render rows that have content. The grid reflows.

The mute toggle reads/writes `NotifyPreferenceEntity` (which already exists per-chat).

"View members (47)" navigates to the existing `GroupAdminScreen` via the same handler the existing kebab uses. This is full-screen on both platforms — separate from the right-pane state machine for v1. (A later improvement could host members inside the right pane.)

"Leave group" is a confirm-and-do. The leave path through `TlonChatRepo` already exists; reuse it.

## Mobile mapping

Two new App.kt state vars:

```kotlin
var showGroupInfoFor by remember { mutableStateOf<String?>(null) }
var groupInfoDrilldown by remember { mutableStateOf<MediaCategory?>(null) }
```

Two new `PlatformBackHandler`s, ordered so drilldown back fires first:

```kotlin
PlatformBackHandler(enabled = groupInfoDrilldown != null) {
    groupInfoDrilldown = null
}
PlatformBackHandler(enabled = showGroupInfoFor != null && groupInfoDrilldown == null) {
    showGroupInfoFor = null
}
```

Navigation `when {}` block adds two branches:

```kotlin
groupInfoDrilldown != null -> MediaListScreen(...)
showGroupInfoFor != null   -> GroupInfoScreen(...)
```

## Testing

| Test | Type | Coverage |
|---|---|---|
| `MediaClassifierTest` | commonTest | Each bucket exercised with representative URLs. Edge cases: query strings, fragment anchors, mixed-case extensions, 🎙 voice detection, multiple URLs per message, empty messages. |
| `MessageMediaDaoTest` | desktopTest | Insert/update/delete round-trip, count queries, category-filtered streams, `replaceForMessage` atomicity. |
| `BackfillTest` | desktopTest | Empty DB after first run with seeded messages → populated `message_media` table. Idempotency: second invocation is a no-op. |
| `RightPaneHostTest` | desktopTest | State-machine transitions: thread closes group info; group info closes thread; drilldown back returns to group info, not chat. |
| `GroupInfoPaneRenderTest` | desktopTest | Hides zero-count rows; renders correctly with 0/1/all categories populated. |

## Migration

Single Room migration. The from→to version numbers depend on the current `AppDatabase` schema version at implementation time (grep `version = ` in `AppDatabase.kt` to find it; `MIGRATION_N_TO_N_PLUS_1` below is a placeholder for the actual `MIGRATION_X_TO_Y` constant the implementer creates).

```kotlin
// Adds message_media table + index. No data backfill in the migration —
// that runs lazily on first app start (see Backfill section).
val MIGRATION_N_TO_N_PLUS_1 = object : Migration(N, N + 1) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS message_media (
                whom TEXT NOT NULL,
                messageId TEXT NOT NULL,
                url TEXT NOT NULL,
                category TEXT NOT NULL,
                displayText TEXT,
                sentMs INTEGER NOT NULL,
                author TEXT NOT NULL,
                PRIMARY KEY (whom, messageId, url)
            )
        """.trimIndent())
        db.execSQL("""
            CREATE INDEX IF NOT EXISTS index_message_media_whom_category_sentMs
            ON message_media(whom, category, sentMs)
        """.trimIndent())
    }
}
```

Bump the `AppDatabase` version. Backfill is a runtime concern, not a migration concern, so it doesn't gate the upgrade.

## Risks

- **Backfill perf on huge archives.** Mitigation: 1000-message chunks, runs in background after first navigation render, live count updates via Room flows so the user sees progress without a hard "indexing…" wall.
- **Mobile back-stack ordering with multiple `PlatformBackHandler`s.** Mitigation: explicit `enabled` predicates that make the order unambiguous (drilldown handler is `enabled` only when in a drilldown; group-info handler is `enabled` only when in group info AND not in drilldown).
- **Thread-screen refactor breaking mobile.** Mitigation: same wrapper-vs-list pattern Phase 2 used three times for `StatusFeedScreen` / `BookmarksScreen` / `ActivityFeedScreen`. Header markup preserved byte-for-byte.
- **Categorisation surprise** (a `.gif` someone meant as a video, an audio file someone meant as a download). Mitigation: rules are documented and the constants live together; the user can re-categorise by code change if a real-world pattern emerges.

## Done criteria

- All listed components landed on a `desktop-rightcol` branch (or a successor of `desktop-split-pane`).
- All listed tests green.
- Manually verified: open Talon → click info icon in a group chat header → see counts → drill into Photos → see list → tap an item → image viewer opens → close → drilldown still showing → back → group info → close → back to chat. Both wide and narrow.
- Backfill completes silently on a real Talon install with at least one chat history.
- 0.10.0-rc1 tagged and built.
