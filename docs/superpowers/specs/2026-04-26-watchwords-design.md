# Watchwords — Design

## Overview

Watchwords let a user define a list of text terms; whenever a non-self message containing one of those terms arrives in any chat (or pre-existed in the local cache at term-creation time), Talon records a "hit" and — for terms flagged as "notify" — fires a system notification. Hits accumulate in a dedicated **Watchwords** screen accessible from the home overflow menu, filterable by term.

The feature is mention-style alerting expanded beyond `@-mentions`: any user-defined string can become a personalized notification trigger. Primary use cases are OSINT-style topical watches ("RFP", "Mars Society"), informal name-mention alerts ("my-handle"), and any keyword surveillance the user wants across their Urbit network.

## Decisions captured

Six clarifying questions resolved during brainstorming:

- **Q1 surface**: Notifications + a dedicated **Watchwords** feed screen (option B).
- **Q2 match semantics**: Word-boundary, case-insensitive (option B). No regex in v1.
- **Q3 scope**: Global with per-chat opt-out (option B). Skip own messages (Q3.1 = yes). Muted chats are excluded entirely — no notification *and* no feed entry (Q3.2 user override of original recommendation).
- **Q4 backfill**: Auto-backfill on term add (option B). Scans local message cache only.
- **Q5 per-term notify toggle**: Yes (option B). Each term has an independent `notify` flag.
- **Q6.1 cross-device sync**: Sync via `%settings`, default OFF, opt-in (option b).
- **Q6.2 hit retention**: 1000 hits per term, oldest pruned, plus an explicit "Clear hits" button (option c).
- **Q6.3 term management UI**: Inside the feed screen via a manage sheet (option a).

## Architecture

Approach **A — UI-listener integration** was selected over a deeper repo-internal alternative. The watchword check hooks into the existing `app.repo.messageListener` callback in `TalonApp.kt`, sharing the same lifecycle and dispatcher as the existing notification path. Backfill runs as a one-shot coroutine on `Dispatchers.Default`. Term and exclude state lives in a new `Watchwords` facade class at app-level (mirrors the `AiSettings` pattern) backed by Room.

```
TlonChatRepo (SSE pump)
   └─ messageListener?.invoke(m, replyToUs)
       └─ TalonApp DisposableEffect (existing)
           └─ appScope.launch:
               ├── existing notification path (unchanged)
               └── NEW watchword evaluation
                   ├─ skip self / muted / excluded
                   ├─ StoryCache.textFor → plain text
                   ├─ matches = terms.filter { matchesWordBoundary(text, it.term) }
                   ├─ if matches.isNotEmpty:
                   │   ├─ Watchwords.persistHits(...)
                   │   └─ Notifications.showWatchwordHit(...)  // notify=true terms only
```

The repo and the on-disk schema gain three Room tables and two `%settings` buckets. Nothing existing changes — additions only.

## Data model

### New Room tables (DB migration v25 → v26)

```kotlin
@Entity(
    tableName = "watchwords",
    indices = [Index(value = ["term"], unique = true)],
)
data class WatchwordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val term: String,
    val notify: Boolean,
    val createdMs: Long,
)

@Entity(
    tableName = "watchword_hits",
    primaryKeys = ["term", "whom", "postId"],
    indices = [
        Index(value = ["term", "sentMs"]),
        Index(value = ["sentMs"]),
    ],
)
data class WatchwordHitEntity(
    val term: String,
    val whom: String,
    val postId: String,
    val sentMs: Long,
    val snippet: String,
)

@Entity(tableName = "watchword_chat_excludes")
data class WatchwordChatExcludeEntity(
    @PrimaryKey val whom: String,
)
```

The hits PK `(term, whom, postId)` enforces "the same (term, message) pair upserts in place" — so a live match colliding with a backfill match for the same row is a no-op. Two different terms matching the same message produce two rows, which is correct: the per-term feed view must show each term's hits independently.

The `(term, sentMs)` index serves the per-term feed (`WHERE term = ? ORDER BY sentMs DESC`); the bare `(sentMs)` index serves the all-hits feed; together they also let the prune query (`DELETE WHERE term = ? AND sentMs NOT IN (… newest 1000)`) execute against the index instead of a full scan.

Room migration:

```kotlin
private val MIGRATION_25_26 = object : Migration(25, 26) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS watchwords (
            id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
            term TEXT NOT NULL,
            notify INTEGER NOT NULL,
            createdMs INTEGER NOT NULL
        )""")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_watchwords_term ON watchwords (term)")

        db.execSQL("""CREATE TABLE IF NOT EXISTS watchword_hits (
            term TEXT NOT NULL,
            whom TEXT NOT NULL,
            postId TEXT NOT NULL,
            sentMs INTEGER NOT NULL,
            snippet TEXT NOT NULL,
            PRIMARY KEY (term, whom, postId)
        )""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_watchword_hits_term_sentMs ON watchword_hits (term, sentMs)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_watchword_hits_sentMs ON watchword_hits (sentMs)")

        db.execSQL("""CREATE TABLE IF NOT EXISTS watchword_chat_excludes (
            whom TEXT NOT NULL PRIMARY KEY
        )""")
    }
}
```

### `Watchwords` facade

App-level singleton, instantiated in `TalonApplication.buildShipScoped` alongside `repo`/`drafts`/`shortcuts` (per-ship: each ship's watchwords live in that ship's DB).

```kotlin
class Watchwords(
    private val db: AppDatabase,
    private val repoOurPatp: () -> String,    // for self-message skip in backfill
    private val notifyPrefs: NotifyPreferenceDao,
    private val scope: CoroutineScope,        // appScope
) {
    val terms: StateFlow<List<WatchwordEntity>>
    val excludes: StateFlow<Set<String>>
    val syncEnabled: StateFlow<Boolean>

    val backfilling: StateFlow<Set<Long>>     // term ids currently scanning

    @Volatile var onChange: ((WatchwordChange, transitionedOffSync: Boolean) -> Unit)? = null

    suspend fun add(term: String, notify: Boolean): Long
    suspend fun remove(termId: Long)
    suspend fun setNotify(termId: Long, notify: Boolean)
    suspend fun clearHits(termId: Long)
    suspend fun excludeChat(whom: String, excluded: Boolean)
    suspend fun setSyncEnabled(enabled: Boolean)

    /** Synchronous-style evaluation called from messageListener. */
    suspend fun evaluateLive(
        m: MessageEntity,
        plainText: String,
    ): List<MatchedTerm>

    /** One-shot scan kicked off after [add]. Uses Default dispatcher. */
    suspend fun backfill(termId: Long)
}

sealed class WatchwordChange {
    data class Upsert(val term: WatchwordEntity) : WatchwordChange()
    data class Remove(val termText: String) : WatchwordChange()
    data class Exclude(val whom: String) : WatchwordChange()
    data class Unexclude(val whom: String) : WatchwordChange()
    data object SyncToggled : WatchwordChange()
}

data class MatchedTerm(val term: WatchwordEntity, val snippet: String)
```

`StateFlow` exposure mirrors `AiSettings.state` — backed internally by a Room flow with `distinctUntilChanged()`, hot-shared eagerly so the per-message listener reads `.value` without DB round-trips.

`WatchwordsDao` exposes the standard ops: `streamTerms()`, `streamExcludes()`, `upsertTerm`, `deleteTerm`, `streamHits(filterTerm: String? = null)`, `upsertHit`, `pruneToNewest(term, limit)`, `countForTerm`, `clearHitsForTerm`, plus a backfill candidates query (see §Backfill).

## Runtime flow

Insertion point: the existing `messageListener` block in `TalonApp.kt`. Watchword evaluation runs in the **same** `appScope.launch` as the existing notification work to share the per-whom `NotifyLevel` lookup and avoid a second DB call:

```kotlin
appScope.launch {
    val level = app.db.notifyPrefs().levelFor(m.whom) ?: NotifyLevel.DEFAULT
    val muted = level == NotifyLevel.NONE

    // ── existing notification path ─────────────────────
    val shouldNotify = !muted && (level != NotifyLevel.MENTIONS ||
        replyToUs || isMentioned(m.contentJson, loggedInShip ?: ""))
    if (shouldNotify) Notifications.showMessage(...)

    // ── watchword path (NEW) ───────────────────────────
    if (m.author == ourPatp) return@launch
    if (muted) return@launch
    if (m.whom in app.watchwords.excludes.value) return@launch
    val terms = app.watchwords.terms.value
    if (terms.isEmpty()) return@launch  // free fast-path

    val plainText = StoryCache.textFor(m.id, m.contentJson)
    val matches = app.watchwords.evaluateLive(m, plainText)
    if (matches.isEmpty()) return@launch

    val notifiable = matches.filter { it.term.notify }
    if (notifiable.isNotEmpty()) {
        Notifications.showWatchwordHit(
            context = context,
            whom = m.whom,
            postId = m.id,
            parentId = m.parentId,
            terms = notifiable.map { it.term.term },
            label = contactMap.conversationLabel(m.whom),
            body = plainText.take(160).replace('\n', ' '),
            sentMs = m.sentMs,
        )
    }
}
```

`evaluateLive` walks `terms`, runs `matchesWordBoundary`, and `upsertHit`s each match before returning the matched terms. Persist + notify-fire happen sequentially within the single launch.

### `matchesWordBoundary`

Pure-Kotlin word-boundary scanner that handles punctuation-heavy terms cleanly without regex escaping concerns:

```kotlin
internal fun matchesWordBoundary(haystack: String, needle: String): Boolean {
    val h = haystack.lowercase()
    val n = needle.lowercase()
    if (n.isEmpty()) return false
    var i = 0
    while (true) {
        val found = h.indexOf(n, i); if (found < 0) return false
        val before = if (found == 0) ' ' else h[found - 1]
        val end = found + n.length
        val after = if (end >= h.length) ' ' else h[end]
        if (!before.isLetterOrDigit() && !after.isLetterOrDigit()) return true
        i = found + 1
    }
}
```

Behavior:
- `"Mars"` matches `"Mars Society"`, `"(Mars)"`, `"Mars!"`; does NOT match `"Marshmallow"`.
- `"C++"` matches `"I love C++"` (non-letter on both sides).
- Multi-word phrases: `"Mars Society"` matches the literal substring with non-letter boundaries at the ends. Internal whitespace is matched as-is (so `"Mars\nSociety"` — split across newlines in the rendered plain text — does not match; this is a documented limit for v1).

## Backfill flow

Triggered after `Watchwords.add(term)` returns. Runs as a coroutine launched on the facade's `scope` with `Dispatchers.Default`, tracked in `backfilling: StateFlow<Set<Long>>` so the UI can show a per-term spinner.

```kotlin
suspend fun backfill(termId: Long) = withContext(Dispatchers.Default) {
    val term = db.watchwords().getTerm(termId) ?: return@withContext
    val excludes = excludes.value
    val mutedWhoms = notifyPrefs.mutedWhomSet()
    val ourPatp = repoOurPatp()
    val hits = ArrayList<WatchwordHitEntity>(64)

    for (m in db.messages().candidatesForBackfill(term.term, ourPatp)) {
        if (hits.size >= MAX_HITS_PER_TERM) break
        if (m.whom in excludes || m.whom in mutedWhoms) continue
        val plainText = StoryCache.textFor(m.id, m.contentJson)
        if (!matchesWordBoundary(plainText, term.term)) continue
        hits.add(WatchwordHitEntity(
            term = term.term,
            whom = m.whom,
            postId = m.id,
            sentMs = m.sentMs,
            snippet = plainText.take(200),
        ))
    }
    db.watchwords().upsertHits(hits)
}
```

```kotlin
@Query("""
    SELECT * FROM messages
    WHERE isDeleted = 0
      AND author != :exceptAuthor
      AND contentJson LIKE '%' || :term || '%' COLLATE NOCASE
    ORDER BY sentMs DESC
""")
abstract suspend fun candidatesForBackfill(term: String, exceptAuthor: String): List<MessageEntity>
```

Two-stage filter — SQL `LIKE` over `contentJson` narrows candidates without parsing JSON; in-memory `matchesWordBoundary` over the rendered plain text rejects false positives caused by JSON keys (e.g. a search for `"block"` would otherwise hit every message). The one-shot `List` + early-break-at-cap loop: a stop-word-ish term that LIKE-matches half the table still terminates in seconds once the hit cap is reached.

**Notifications do not fire during backfill.** Historical hits land silently in the feed.

A new `Watchwords.scanThisDevice(termId)` method (manually triggered from the manage sheet) re-runs `backfill(termId)` at any time — used by the user to re-scan after enough new chats have paginated locally.

## UI surfaces

### Home menu entry
In `DmListScreen`'s existing "More" `DropdownMenu`, a new `"Watchwords"` item slots in next to Activity. New `watchwordsOpen: Boolean` state in `TalonApp.kt` routes to `WatchwordsScreen`.

### `WatchwordsScreen`
- Top bar: back / "Watchwords" title / pencil ✏︎ button → opens `ManageTermsSheet`.
- Filter row: `LazyRow` of chips — `[All]` followed by `[<term> <hitCount>]` per term. Selected chip filters the feed.
- LazyColumn of hits (newest first, joined with conversation labels via `ContactMap`). Each row shows `term · convoLabel · relativeTimestamp` on the header and the snippet text below. Tap routes via the existing `onOpenConversation` + `pendingScrollMessageId` plumbing — same as notifications.
- Empty states: distinct messages for "no terms yet" (with CTA) vs "terms but no hits".
- Standard performance pattern: `streamHits` flow uses `distinctUntilChanged()` and `flowOn(Dispatchers.Default)`.

### `ManageTermsSheet`
A `ModalBottomSheet`, same shape as `FolderAssignmentSheet`. Top to bottom:
- **Add row**: `OutlinedTextField` ("Add a watchword…") + Notify `Switch` (default ON) + `Add` button. The button is disabled when `term.trim().isBlank()`.
- **Existing terms list**: each row `[term text] [hit-count chip] [Notify Switch] [trash icon]`.
  - Trash → `AlertDialog` confirmation: `"Delete '$term'? This clears its $count hits."`
  - Switch flips `notify` directly.
- **Sync row**: `Switch` "Sync watchwords across devices" tied to `Watchwords.syncEnabled`. Subtitle: `"Off keeps terms on this device only."`
- **Caveat row**: italic small text — `"Backfill scans messages cached on this device. Older history pages back-load as you scroll a chat."`

Rename is **not** supported in v1 (delete + re-add).

### Per-chat exclude
In `DmChatScreen`'s existing notification-level `DropdownMenu`, one new item:
- `"Exclude from watchwords"` / `"Include in watchwords"` (label flips on current state)

Storage: insert/delete from `watchword_chat_excludes`. The watchword evaluator's existing `excludes` check picks it up reactively.

### Notification format
New channel `CHANNEL_WATCHWORDS = "watchwords"` registered in `Notifications.ensureChannel` at `IMPORTANCE_HIGH`. New method:

```kotlin
fun showWatchwordHit(
    context: Context,
    whom: String,
    postId: String?,
    parentId: String?,
    terms: List<String>,
    label: String,
    body: String,
    sentMs: Long,
)
```

- Tag: `"watchword:$whom"` (separate namespace from `whom` so it never collides with regular `showMessage`).
- Title: `"${terms.joinToString(", ")} in $label"`.
- Body: 160-char plain-text snippet, newlines collapsed.
- Tap intent: identical to `showMessage` — `EXTRA_OPEN_WHOM`, `EXTRA_SCROLL_TO_MESSAGE`, optional `EXTRA_OPEN_THREAD`.

## Settings sync

Two new buckets in the `talon` desk on the user's ship, gated by a single user-toggleable `syncEnabled` flag (default OFF, mirrors `AiSettings.syncEnabled`).

### Buckets

```kotlin
const val BUCKET_WATCHWORDS = "watchwords"
const val BUCKET_WATCHWORD_EXCLUDES = "watchword-excludes"
```

`watchwords` bucket — one entry per term:
- key: `sanitizeTerm(term)` → `term.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')`
- value: `{ "term": "<raw term>", "notify": <bool>, "createdMs": <long> }`

`watchword-excludes` bucket — one entry per excluded chat:
- key: `whom` (e.g. `"chat/~darduc-mitfen/news-discussion"`)
- value: `true`

### Flow

`Watchwords.onChange` is wired in `TalonApplication` after `repo` + `settingsSync` exist (mirrors `AiSettings.onStateChange`):

```kotlin
watchwords.onChange = { evt, transitionedOffSync ->
    appScope.launch {
        runCatching {
            when {
                transitionedOffSync -> repo.settingsSync.clearWatchwordsOnShip()
                watchwords.syncEnabled.value -> when (evt) {
                    is WatchwordChange.Upsert     -> repo.settingsSync.pushWatchwordEntry(evt.term)
                    is WatchwordChange.Remove     -> repo.settingsSync.deleteWatchwordEntry(evt.termText)
                    is WatchwordChange.Exclude    -> repo.settingsSync.pushWatchwordExclude(evt.whom)
                    is WatchwordChange.Unexclude  -> repo.settingsSync.deleteWatchwordExclude(evt.whom)
                    is WatchwordChange.SyncToggled -> repo.settingsSync.pushAllWatchwords()  // initial flush on enable
                }
                else -> Unit
            }
        }
    }
}
```

`SettingsSync` gains:
- New branches in `applyEntry` / `applyBucket` / `removeEntry` / `clearBucketLocally` for both buckets — same pattern as the existing `BUCKET_NOTIFY_PREFS` branches.
- Push helpers:
  - `pushWatchwordEntry(term)` — single `put-entry` into `BUCKET_WATCHWORDS`.
  - `deleteWatchwordEntry(termText)` — single `del-entry` keyed on `sanitizeTerm(termText)`.
  - `pushWatchwordExclude(whom)` — single `put-entry` into `BUCKET_WATCHWORD_EXCLUDES`.
  - `deleteWatchwordExclude(whom)` — single `del-entry` from same.
  - `pushAllWatchwords()` — initial flush on sync-enable: pushes the full local state of *both* buckets (every term + every exclude) so the ship's settings start in lockstep with the device. Called once when `syncEnabled` flips to true.
  - `clearWatchwordsOnShip()` — pushes `del-bucket` for both buckets. Called once when `syncEnabled` flips to false.

### Conflicts

Last-write-wins, same as the rest of the buckets. No timestamp comparison. The most recent action on either device is canonical.

### Remote-applied terms do not trigger backfill

When `applyEntry` upserts a term received from the ship, `WatchwordChange.Upsert` is **not** fired (or is fired with a flag to suppress the local sync push back to the ship). Backfill is also suppressed: the originating device already backfilled, and re-running it on every device that receives the sync entry would duplicate work without adding value. The local device picks up future matches via the live listener; users can manually re-trigger backfill via a "Scan this device" button in the manage sheet.

### Term key collisions under sync

Two terms that sanitize to the same key (e.g. `"Mars-Society"` and `"mars society"`) collide on the ship side; second write wins. Locally both terms still exist with distinct ids. The manage UI surfaces a small inline warning when a newly-added term sanitizes to a key already in use: `"This term shares a sync key with '<existing>'. Pick distinct words to avoid clobbering it on other devices."`

### Hits do not sync

Each device computes its own hits against its own local message cache. There is no value in syncing them.

## Performance considerations

### Hot-path early-exit when no terms exist
Most users won't have watchwords configured for most of the lifetime of the app. The per-message check fast-bails on `terms.isEmpty()` after one `StateFlow.value` read — no `StoryCache` call, no DB query, no notification work. **A user who never sets up watchwords pays ~zero cost.**

### Shared `notifyPrefs.levelFor` lookup
Watchword evaluation lives in the same `appScope.launch` as the existing notification path so the per-whom `NotifyLevel` lookup happens once per message, not twice.

### `terms` and `excludes` are in-memory `StateFlow`
Backed by Room flows with `distinctUntilChanged()` and `stateIn(SharingStarted.Eagerly)`. The per-message check is `O(1)` set/list reads; no DB round-trips on the hot path. Same trick as `aiSettings.state.value`.

### `StoryCache.textFor` is the dominant per-message cost
~5ms cold, <1ms warm. Watchword evaluation effectively pre-warms `StoryCache` for incoming messages from chats the user isn't currently viewing — when they later open one of those chats, the cache is already populated, eliminating the "items arranging" flash that 0.2.12 was designed to address.

### Lazy pruning with 100-hit headroom
The 1000-hits-per-term cap is enforced *only* when `countForTerm` exceeds 1100, not on every insert. This means pruning runs ~1% as often as it would naively. A quiet term never triggers prune; a noisy term triggers it ~once every 100 hits.

### Backfill is bounded
`candidatesForBackfill` returns a `List`; the consumer iterates and `break`s on the 1000-hit cap. A stop-word-ish accidental term that LIKE-matches half the table still terminates in seconds once the hit cap is reached.

### Feed flow uses standard patterns
`streamHits(filterTerm)` is wrapped with `distinctUntilChanged()` + `flowOn(Dispatchers.Default)` — same pattern applied across the rest of the app's screens, keeping any list rebuild off Main even during high-traffic bursts.

### Storage ceiling
`1000 hits × 50 terms × ~250 bytes = ~12.5 MB` worst case. Typical user well under.

## Edge cases

| Case | Resolution |
|---|---|
| Live SSE traffic + backfill scan racing on the same message | `upsertHit` keyed by `(term, whom, postId)` makes the second write a no-op. |
| Term deleted while backfill is mid-flight | `Watchwords.remove` cancels the scoped backfill `Job` and cascades the delete; coroutine sees `CancellationException` and exits cleanly. |
| `applyEntry` arrives for a term we don't have | Upsert into `watchwords`; do **not** trigger backfill (originating device already did it). User can `Scan this device` manually. |
| `Story.parse` returns empty / malformed | `plainText.isEmpty()` → no match → silent skip. |
| Phrase term split across newlines in plain text | `String.indexOf` doesn't normalize whitespace; `"Mars Society"` does not match `"Mars\nSociety"`. **Documented limitation for v1.** |
| Empty / whitespace-only / pure-punctuation term | Rejected at UI layer (`Add` button disabled until `term.trim().isNotBlank()`). Stored after `.trim()`. |
| Two terms collide under sync key sanitization | Documented in §Settings sync. Inline warning in manage UI. |
| `NotifyLevel` flips from `ALL` → `NONE` after past hits exist | Past hits stay (valid at the time). Future hits filtered by the live evaluator. |
| Massive (multi-KB) message | `String.indexOf` is heavily optimized; ≪1ms even for 16KB × 50 terms. |
| Burst of inserts past prune threshold | Lazy prune absorbs 100-hit headroom without re-pruning each insert. |
| Term contains regex / SQL meta characters | Not regex (we use `String.indexOf`); LIKE pre-filter binds `:term` via Room's parameter substitution (no injection). |
| User toggles sync OFF with terms on the ship | `clearWatchwordsOnShip` pushes `del-bucket` for both buckets. Local terms preserved. |

## Testing

Mirrors the existing pattern: pure JVM unit tests for pure logic; no Android instrumentation harness. New test files in `app/src/test/kotlin/io/nisfeb/talon/ai/`:

### `WatchwordMatcherTest.kt`

Table-driven tests for `matchesWordBoundary`. Categories:

- Word-boundary basics: `"Mars"` matches `"Mars Society"`, does NOT match `"Marshmallow"`, `"Marshall"`, `"premars"`.
- Punctuation around terms: `"(Mars)"`, `"Mars!"`, `",Mars,"`, `"-Mars-"`.
- Multi-word phrases: `"Mars Society"` matches itself; does NOT match across `\n`; matches inside larger sentences.
- Punctuation-heavy terms: `"C++"` matches `"I love C++"` and `"C++!"`; does NOT match `"C++14"` (non-letter on right side).
- Empty / whitespace-only / single-character terms.
- Case insensitivity: `"Mars"` matches `"MARS"` and `"mars"`.
- Unicode sanity (does not crash): `"café"`, `"日本"` — no v1 commitment to correctness, only stability.

### `WatchwordSanitizerTest.kt`

- `sanitizeTerm` lowercases.
- `sanitizeTerm` collapses runs of non-alphanumeric to a single underscore.
- `sanitizeTerm` trims leading/trailing underscores.
- Collision detection: `"Mars Society"` and `"mars  society"` produce the same key; manage UI uses this for the inline warning.

### `WatchwordsDaoTest.kt`

Out of scope for v1. Project pattern is to leave Room DAOs uncovered by unit tests and rely on the type system + manual integration testing.

### Manual integration smoke

After implementation:
1. Add term `"test"` with notify ON. Confirm a notification fires when a non-self message containing the word is received.
2. Add term `"test"` with notify OFF. Confirm no notification but feed entry appears.
3. Mute a chat. Confirm matching messages do NOT appear in the feed.
4. Mark a chat as excluded from watchwords. Confirm matching messages do NOT appear in the feed.
5. Delete a term. Confirm hits are removed from the feed.
6. Toggle sync ON, add a term, scry `%settings /desk/talon/watchwords` on the ship — verify the entry is present.
7. Toggle sync OFF — verify `del-bucket` lands and the bucket disappears on the ship.
8. Re-open the manage sheet after navigating away — terms persist, hit counts up to date.

## Out of scope (v1 non-goals)

- Regex matching (Q2 = B). Power-user trap; needs validation UI.
- Per-term case-sensitivity flag. Case-insensitive default covers all known use cases.
- Term rename. Delete + re-add covers the use case.
- Pre-defined templates (e.g. `"my @mentions"` as a preset). Watch terms are user-driven.
- Hit synchronization across devices. Each device computes its own.
- Smart de-duplication of hits across multiple terms matching the same message in the all-hits feed view. Simple "two rows, two terms" is correct and consistent with the per-term view.
- Notification grouping in Android's bundled-notification sense. Tag-per-whom collapses repeated hits in the same chat naturally; we don't summarize across chats.

## Build order in the larger plan

This is feature 1 of 6 from the post-perf brainstorm:

1. **Watchwords** ← this spec
2. Custom slash commands (next; provides infrastructure used by 5)
3. Scheduled messages (uses WorkManager)
4. Daily digest (independent)
5. Native RSS-to-channel (uses 2 + 3 infra)
6. Webhook inbox (architecture conversation pending)

Each gets its own spec → plan → implementation cycle.
