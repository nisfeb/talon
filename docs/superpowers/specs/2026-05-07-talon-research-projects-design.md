# Research Projects — Design

**Status:** Brainstormed 2026-05-07. Ready for planning of Phase 1.

## Goal

Make Talon a structured-data layer over a user's Urbit chat life, queryable
in natural language, fed by their social graph, backed by `%obelisk` on
their own ship. The unit of organization is the **research project**:
an opt-in flag on a group whose chat / diary / heap traffic gets
indexed, link content gets fetched + AI-summarized, and the whole
corpus becomes queryable.

## Why this works

- **Groups are the right primitive.** Tlon already gives you chat
  (conversation), diary (long-form notes), and heap (link/media gallery).
  A research project doesn't invent any of these — it adds an
  index-and-summarize layer on top of what already exists.
- **Solo and shared land in one model.** Solo project = group of one.
  Shared project = invite collaborators using the standard Urbit group
  flow. No new permissions story.
- **Obelisk is the substrate, not %settings.** All research-domain
  state (project marks, schema, indexed content, saved questions, fetcher
  budget) lives in obelisk on the user's ship. `%settings` keeps only the
  things it already keeps (density, daily digest, AI provider/key,
  watchwords, folders). Single source of truth, no schema-version
  ping-pong, free multi-device sync.
- **Capability gating is honest.** No obelisk = no Research feature on
  that ship. Detected per-session, not compile-time.

## Phasing

Three sub-projects. We won't ship intermediate releases — we ship when
all three are integrated — but they plan and implement as discrete
units because the lessons of each phase shape the next.

| Phase | Scope | Demoable surface |
|-------|-------|------------------|
| **1 — Foundation** | mark-as-research, obelisk schema + migrations, indexer, Research rail tab, projects-overview screen, `/urql` slash | Marked groups appear in Research rail; project cards show live indexing stats; user can hand-write urQL via `/urql` |
| **2 — Intelligence** | link fetcher, content extractor (HTML, PDF), AI summarizer, tag extractor, AI summary chips on heap cards + diary headers | Heap cards gain a 2-line AI gist; tag chips appear on links and diary posts |
| **3 — Natural language** | NL→urQL pane, saved questions in obelisk, project-scoped and global ask surfaces | "Has anyone I know mentioned gecko toe research?" works |

The rest of this document specs Phase 1 in full implementation detail.
Phases 2 and 3 are designed at the architectural level — schema and
component shape — but their planning happens after Phase 1's
implementation has produced concrete lessons about urQL ergonomics,
indexer throughput, and obelisk operational behavior.

---

# Phase 1 — Foundation

## Decided product behavior

| Decision | Choice |
|----------|--------|
| Backfill on mark | Full local Room → obelisk via `%tape2` batches, with progress UI |
| Leave-group orphan policy | Mark + indexed data persist; re-join resumes from watermark |
| Explicit toggle-off | Stop indexing, keep data; destruction is a separate "Delete project data" action with its own confirm |
| Multi-device indexing | All devices index idempotently; watermark stored per-device-per-group; obelisk's set semantics make duplicate inserts no-ops |
| Project card content (v1) | Stats-rich: name, member count, last activity, indexed message / link / diary counts, top 3 contributors |

## User flows

### Marking a group as research

1. User opens an existing group's GroupHomeScreen.
2. Group menu → "Mark as research project" (toggle).
3. Toggle flips on. UI immediately shows "Backfilling…" indicator on the
   group's row in the Research rail.
4. Indexer starts: streams local Room messages for that group_flag in
   timestamp-ascending batches of 500, INSERTs into obelisk via `%tape2`
   scripts, advances watermark on success.
5. Backfill completes; project card on Research screen shows totals.
6. From this point forward, new messages arriving via Talon's normal
   sync pipeline get incrementally indexed by the same indexer
   listening to a Flow of new-event signals from sync.

### Creating a new research project

Talon already has group creation. The Research rail tab gets a "+ New
research project" affordance that opens the existing group-creation flow
with the research flag pre-checked. On group create, the flag is set
and the (empty) group becomes a project immediately.

### Toggling off

1. User opens a marked group.
2. Group menu → "Mark as research project" (toggle, currently on).
3. Toggle flips off. Indexer for that group halts.
4. Indexed data remains in obelisk. Project card disappears from the
   Research rail (no "archived" sub-section in v1; the data is just
   inert until re-marked).
5. To actually destroy the data, user invokes a separate "Delete
   research data for this project" action from the same menu, which
   prompts a destructive-confirm dialog. Only this action issues
   `DELETE FROM …` urQL.

### Leaving a marked group

No special handling. Mark stays. Data stays. Indexer simply has no new
content to consume from a group the user is no longer in. If user
re-joins, indexer resumes from the watermark.

### Switching ships

Research rail tab visibility tracks the active session's
`obeliskAvailable` flag. Switch to a ship without obelisk: tab
disappears. Switch to a ship with obelisk: tab appears, showing that
ship's marked projects.

## Capability detection

On session connect, after the standard auth/scry handshake:

1. Scry the desk arch: `.^(arch %cy /=obelisk=/)`. If absent,
   `obeliskAvailable = false`. Done.
2. If present, attempt a no-op urQL query (e.g. `SELECT 1`) via
   `%tape2` to confirm the desk is responsive and the API surface
   we depend on works. If this fails, log and treat as unavailable.
3. Read schema version: query `SELECT value FROM talon_meta WHERE
   key='schema_version'`. Three outcomes:
   - **Empty result:** no Talon schema present. Run init DDL → write
     `schema_version = 1`. Set `obeliskAvailable = true`.
   - **Version equal to Talon's `CURRENT_SCHEMA_VERSION`:** ready.
   - **Version less than Talon's:** run migration scripts in order,
     each as an atomic urQL script, ending with the version bump.
   - **Version greater than Talon's:** newer Talon has touched this
     ship. Set `obeliskAvailable = false` and surface a "Talon needs an
     update to use research on this ship" hint. Do not write anything.

The capability lives on the session as `obeliskAvailable: Flow<Boolean>`
plus a derived `researchSchemaVersion: Int?`. The rail tab and all
research surfaces gate on `obeliskAvailable`.

## Obelisk schema (v1)

All tables use the no-NULL convention. Where a column has no value for a
particular row, the empty string or 0 is used. urQL types
(`varchar`, `int`, `bigint`) follow obelisk's type system.

```sql
-- bookkeeping
talon_meta (
  key      varchar primary key,   -- 'schema_version', 'install_id', 'fetcher_budget_per_day', etc.
  value    varchar
)

-- a marked group
project (
  group_flag  varchar primary key,   -- e.g. '~zod/foo'
  marked_at   bigint,
  name        varchar                -- snapshot of group title at mark time; editable later
)

-- one row per indexed message/post regardless of channel kind
post (
  group_flag    varchar,
  channel       varchar,             -- channel name, e.g. 'general' or '~zod/intro'
  channel_kind  varchar,             -- 'chat' | 'diary' | 'heap' | 'thread'
  msg_id        varchar,             -- canonical id from Talon's Room cache
  ts            bigint,
  ship          varchar,             -- author
  title         varchar,             -- diary title; empty for chat/heap-without-title
  text          varchar,             -- full extracted plain-text body
  char_count    int,
  primary key (group_flag, msg_id)
)

-- one row per URL appearance (a single message can shed multiple links)
link (
  group_flag  varchar,
  msg_id      varchar,
  ts          bigint,
  ship        varchar,
  url         varchar,
  host        varchar,
  scheme      varchar,
  primary key (group_flag, msg_id, url)
)

-- one row per @-mention in a message
mention (
  group_flag  varchar,
  msg_id      varchar,
  ts          bigint,
  from_ship   varchar,
  to_ship     varchar,
  primary key (group_flag, msg_id, to_ship)
)

-- one row per non-link attachment
attachment (
  group_flag  varchar,
  msg_id      varchar,
  ts          bigint,
  ship        varchar,
  mime        varchar,
  kind        varchar,                -- 'image' | 'audio' | 'video' | 'file'
  primary key (group_flag, msg_id, mime)
)

-- per-device backfill / live-index watermark
index_state (
  install_id   varchar,               -- random UUID minted on first Talon run, persisted locally
  group_flag   varchar,
  last_ts      bigint,                -- highest ts the indexer has confirmed written for this device
  primary key (install_id, group_flag)
)
```

`install_id` lives in Talon's local prefs (Room), not in obelisk.
Different installations on the same ship coexist; each tracks its own
watermark.

`primary key (group_flag, msg_id)` on `post` makes batched `INSERT`s
naturally idempotent: if Device A and Device B both try to insert the
same message, one wins, the other is a no-op (relying on obelisk's
set semantics — confirm exact behavior during the plan stage; if
duplicate-PK is an error, indexer wraps inserts in `INSERT IGNORE` /
the urQL equivalent).

## Schema migration runner

```kotlin
// commonMain
object ResearchSchema {
    const val CURRENT_VERSION = 1

    val initScript: String = """
        CREATE TABLE talon_meta (...);
        CREATE TABLE project (...);
        ...
        INSERT INTO talon_meta (key, value) VALUES ('schema_version', '1');
    """.trimIndent()

    val migrations: Map<Int, String> = mapOf(
        // 1 -> 2: when Phase 2 lands
    )
}

class ResearchSchemaMigrator(private val obelisk: ObeliskClient) {
    suspend fun ensureCurrent(): Result<Int> {
        val current = readVersion() // null if no talon_meta
        return when {
            current == null -> {
                obelisk.script(ResearchSchema.initScript).map { ResearchSchema.CURRENT_VERSION }
            }
            current == ResearchSchema.CURRENT_VERSION -> Result.success(current)
            current > ResearchSchema.CURRENT_VERSION -> Result.failure(NewerSchemaPresent(current))
            else -> migrate(from = current, to = ResearchSchema.CURRENT_VERSION)
        }
    }
}
```

Each migration is one urQL script. Obelisk script atomicity gives us
the semantics we need: if a migration fails partway, the schema doesn't
end up in a half-applied state.

## Indexer architecture

### Components

```
ObeliskClient (commonMain interface)
├─ suspend fun query(urql: String): Result<List<Row>>
├─ suspend fun script(urql: String): Result<Unit>
└─ implementations:
    ├─ TlonObeliskClient — pokes %obelisk via %tape2 / %commands using TlonChatRepo.pokeRaw
    └─ NoopObeliskClient — for tests + when capability false

ProjectRepo (commonMain interface)
├─ fun observeProjects(): Flow<List<Project>>
├─ suspend fun mark(groupFlag: String, name: String): Result<Unit>
├─ suspend fun unmark(groupFlag: String): Result<Unit>
├─ suspend fun deleteData(groupFlag: String): Result<Unit>
└─ ObeliskProjectRepo — reads/writes project table

ResearchIndexer (commonMain)
├─ suspend fun indexProject(groupFlag: String): Flow<IndexProgress>
├─ Reads watermark from obelisk for (install_id, group_flag).
├─ Streams local Room messages where group_flag matches and ts > watermark.
├─ Builds batched %tape2 INSERT scripts (default batch size: 500 rows
│   across post/link/mention/attachment).
├─ Executes via ObeliskClient.script. On success: advances watermark.
├─ On failure: exponential backoff, max 3 retries, then surface error.
└─ Emits IndexProgress(groupFlag, processed, total, state)

IndexerOrchestrator (commonMain, session-scoped)
├─ Listens to ProjectRepo.observeProjects()
├─ For each project: spawn an indexer job (CoroutineScope.launch)
├─ Cancels jobs when project unmarked
├─ On new-message events from sync (existing observable flow): poke
│   the relevant indexer to run another pass (cheap if watermark is
│   already current)
└─ Lives behind obeliskAvailable — created and torn down with the
    capability flag

ResearchExtractor (commonMain)
├─ fun extract(message: MessageEntity): ExtractedRows
├─ Pulls the post row, link rows, mention rows, attachment rows from
│   a single Room message
├─ Uses existing Markdown / story parsers to normalize body text and
│   find URL fenceposts and @-mentions
└─ Output is an immutable bundle the indexer turns into INSERTs
```

### Why these boundaries

- `ObeliskClient` is the single chokepoint for talking to the desk.
  Tests stub it out; real impl lives next to `TlonChatRepo.pokeRaw`.
- `ProjectRepo` is the single chokepoint for project lifecycle. The
  rail tab observes it; the indexer observes it.
- `ResearchExtractor` is pure — given a Room message, returns rows.
  Trivially testable in commonTest with no network.
- `ResearchIndexer` is the I/O engine. One per project, lives as long
  as the project is marked.
- `IndexerOrchestrator` is the lifecycle manager. It's where the rules
  about "spawn one indexer per marked project" and "tear down on
  unmark" live.

### Concurrency model

- Each `ResearchIndexer.indexProject` job runs sequentially within
  itself: pull batch → write → advance watermark → repeat. No internal
  parallelism. Backfill is naturally rate-limited by `%tape2`
  throughput; we don't want to flood the desk.
- Different projects run their indexers concurrently in separate
  coroutines, bounded by a shared `Dispatchers.IO`-flavored scope.
- The orchestrator dedupes: if an indexer for project P is already
  active, a new "go" signal is dropped (the running job will pick up
  whatever's new on its next pass).

### Backfill UI

`IndexProgress` flows up to a `ResearchProgressRepo` that the
projects-overview screen subscribes to. Each project card renders:

- Idle: just the stats.
- Backfilling: a progress bar, "indexing 4,210 / 12,883…", and the
  stats it has so far.
- Error: an error chip with retry affordance.

## Research rail tab + projects-overview screen

### Rail wiring

```kotlin
// existing: enum class RailTab { Chats, Statuses, Bookmarks, Activity }
enum class RailTab { Chats, Research, Statuses, Bookmarks, Activity }
```

`Research` slots between `Chats` and `Statuses`.

The rail rendering already iterates a list of enabled tabs (via
`RailItemPref`). Add a filter step: omit `Research` if
`session.obeliskAvailable` is currently false. The tab
appears/disappears reactively when the user switches ships.

### Projects-overview screen (`ResearchScreen.kt`)

```
┌─ Research ─────────────────────────────────┐
│  My research projects                      │
│                                            │
│  ┌────────────────────────────────────────┐│
│  │ + New research project                 ││
│  └────────────────────────────────────────┘│
│                                            │
│  ┌────────────────────────────────────────┐│
│  │ Gecko Adhesion Lit Review              ││
│  │ ~zod/gecko-lit · 4 members · 2h ago    ││
│  │ 1,283 messages · 412 links · 7 entries ││
│  │ Top contributors: ~zod, ~bus, ~mister  ││
│  └────────────────────────────────────────┘│
│                                            │
│  ┌────────────────────────────────────────┐│
│  │ Cold-Forge Steel                       ││
│  │ Indexing 312 / 4,820… ▓▓▓░░░░░         ││
│  └────────────────────────────────────────┘│
└────────────────────────────────────────────┘
```

Card content (v1):

- Project name (defaults to group title at mark-time; user can rename)
- Group flag
- Member count (from existing Tlon group state)
- Last activity timestamp (max ts in `post` for that project)
- Counts: messages, links, diary entries
- Top 3 contributors (most-frequent ships in `post.ship` for that project)

All counts come from cheap obelisk aggregates:

```sql
SELECT count(*) FROM post WHERE group_flag = ?;
SELECT count(*) FROM link WHERE group_flag = ?;
SELECT ship, count(*) AS n FROM post WHERE group_flag = ? GROUP BY ship ORDER BY n DESC LIMIT 3;
```

Click a project card → drop into the existing `GroupHomeScreen` for
that group_flag. No new screen for "inside a research project" in
Phase 1; the existing group view is sufficient. Phase 2 will overlay
AI summary chips on heap cards rendered there.

Empty state (no marked projects): a hero block explaining what a
research project is, with a "Mark a group as research" button that
opens a group picker.

## `/urql` slash command

Reuses the rc40 `/poke` plumbing in `SlashCommands.kt` and the
`pokeRaw` helper in `TlonChatRepo`.

```kotlin
// commonMain SlashCommands.kt — add to the spec list
SlashCommand(
    name = "urql",
    summary = "Run a urQL SELECT against your obelisk research database",
    enabled = { ctx -> ctx.powerFeaturesEnabled && ctx.obeliskAvailable },
    run = ::runUrQL
)

private suspend fun runUrQL(args: String, ctx: SlashContext): SlashResult {
    val trimmed = args.trim()
    if (!trimmed.lowercase().startsWith("select")) {
        return SlashResult.Error("Only SELECT statements allowed via /urql.")
    }
    return when (val r = ctx.obelisk.query(trimmed)) {
        is Result.Success -> SlashResult.Rows(r.rows.take(MAX_INLINE_ROWS), truncated = r.rows.size > MAX_INLINE_ROWS)
        is Result.Failure -> SlashResult.Error(r.message)
    }
}
```

Constraints:

- **Read-only.** `/urql` accepts SELECT only. `INSERT`/`UPDATE`/`DELETE`/`CREATE`
  are rejected client-side. Mutations come from the indexer alone in
  Phase 1, so the user can't corrupt their own corpus by typing the
  wrong slash command. Power users who want raw write access can do it
  via dojo against `%obelisk` directly — that's a deliberate friction.
- **Result rendering:** an inline result bubble in the composer's send
  region. Rows render as a fixed-width text table (max 50 rows
  inline; "Show all" link copies the full result to clipboard as TSV).
- **Error rendering:** error string from obelisk verbatim. urQL
  errors are usually informative.

## Capability gating in Capabilities.kt

Per the project's cross-platform discipline, add a runtime capability —
not an `expect val` (this is per-ship, not per-platform):

```kotlin
// commonMain
class ResearchCapability(
    private val sessionId: String,
    private val obelisk: ObeliskClient
) {
    val available: StateFlow<Boolean>
    val schemaVersion: StateFlow<Int?>
    suspend fun probe(): ResearchProbeResult
}
```

Stored on the session struct alongside other per-session state (auth,
scry results, etc.).

## What stays in %settings (not obelisk)

- `AiSettings` — provider, baseUrl, apiKey, model. Reused by Phase 2's
  summarizer; one synced source of truth across devices, already works.
- All non-research preferences as-is.

What does *not* go in %settings: project marks, project names,
indexed data, fetcher budget, saved questions. All obelisk.

## Test strategy (Phase 1)

### `commonTest`

- `ResearchExtractorTest` — given a Room message with various
  story shapes, returns the right rows. Pure, no I/O.
- `IndexBatchBuilderTest` — given a list of `ExtractedRows`, produces
  the expected urQL `%tape2` script. String-shape assertion.
- `ResearchSchemaMigratorTest` — fake `ObeliskClient` returning
  scripted version reads; assert correct DDL gets sent for each
  initial-state.
- `ProjectRepoTest` — mark/unmark/delete behavior against a fake
  obelisk.

### `desktopTest`

- `ResearchIndexerHappyPathTest` — end-to-end against an in-memory
  fake obelisk; mark a project, populate Room with 1,000 messages,
  run indexer, assert all rows present in fake obelisk and watermark
  advanced.
- `ResearchCapabilityTest` — capability detection paths
  (no desk → false, schema absent → init runs → true, schema newer →
  false with reason).
- `ResearchScreenTest` — projects-overview rendering for empty,
  populated, and indexing states.

### Manual

- Smoke against a real fakezod or moon with `%obelisk` installed.
  Mark a small group, watch it backfill, run a `SELECT count(*) FROM
  post`, confirm.
- Ship-uninstall mid-session: `|uninstall %obelisk` while Talon is
  open; rail tab should disappear within a few seconds, indexer should
  halt cleanly without crashing the app.

## Cross-platform discipline

- `obeliskAvailable` is **runtime**, per-session — not a compile-time
  capability. Lives on the session, not in `Capabilities.kt`.
  `Capabilities.kt`'s top-comment registry stays unchanged.
- Indexer and obelisk client are pure commonMain — no platform leaves.
- The `+ New research project` flow reuses existing group-creation
  code; no platform-specific work.
- Rail tab change is a pure commonMain enum + filter addition.

## Files touched (Phase 1 estimate)

New (commonMain):

- `urbit/ObeliskClient.kt` (interface + Tlon impl + Noop)
- `urbit/ResearchSchema.kt` (DDL constants, migrations)
- `urbit/ResearchSchemaMigrator.kt`
- `urbit/ResearchExtractor.kt`
- `urbit/ResearchIndexer.kt`
- `urbit/IndexerOrchestrator.kt`
- `urbit/ProjectRepo.kt` (interface + impl)
- `urbit/ResearchCapability.kt`
- `ui/screens/ResearchScreen.kt`
- `ui/screens/ResearchProjectCard.kt`
- `ui/screens/MarkAsResearchAction.kt` (toggle component)

Modified (commonMain):

- `ui/RailTab.kt` — add `Research`
- `ui/RailItem.kt` — render Research
- `ui/SlashCommands.kt` — add `/urql`
- `urbit/TlonChatRepo.kt` — surface a `pokeObelisk` helper if not
  already covered by existing `pokeRaw`
- `compose/App.kt` — wire ResearchCapability + IndexerOrchestrator
  into session lifecycle
- `ui/screens/GroupHomeScreen.kt` — add the "Mark as research" menu
  entry
- `ui/screens/SettingsScreen.kt` — (optional) advanced section
  showing schema version, install_id, and a "Re-run capability probe"
  button for debugging

## Phase 1 open questions for the plan stage

- **Exact urQL syntax for batched INSERTs.** Does `%obelisk` accept
  `INSERT INTO foo VALUES (a), (b), (c)` or only single-row inserts?
  If the latter, batched `%tape2` scripts with a sequence of inserts
  works fine — atomicity is at the script level.
- **Result format from obelisk's response API.** README says copy
  `sur/ast/hoon` to deserialize. Need to write the JSON-or-jam
  bridge. Probably one afternoon of Hoon-side reading.
- **Behavior of `INSERT` on duplicate primary key.** Set semantics
  suggest "no-op", but confirm. If it's "error", we wrap with
  conditional logic (`INSERT … WHERE NOT EXISTS …` or equivalent).
- **Backfill throughput on a typical user.** Measure with a real
  group during prototyping. If a 50k-message group takes 10 minutes,
  fine. If it takes 2 hours, we need to rethink batch sizes or surface
  a "do this overnight" affordance.
- **Group flag canonicalization.** Tlon group flags are
  `~ship/term`. Does obelisk treat `~zod/foo` and `'~zod/foo'` and
  `(~zod %foo)` the same in our urQL? Pick one canonical form.

---

# Phase 2 — Intelligence (architectural sketch)

Planning happens after Phase 1 implementation lands and we know:

- urQL ergonomics for our schema in practice
- Real backfill throughput numbers
- Whether obelisk INSERT-on-duplicate works as expected

## Scope

Augment the corpus with link content and AI-generated summaries / tags.
Render that augmentation on the existing heap and diary surfaces.

## Schema additions (v1 → v2 migration)

```sql
link_content (
  url           varchar primary key,
  fetched_at    bigint,
  status        int,           -- HTTP status; 0 if non-HTTP (e.g. local)
  title         varchar,
  author        varchar,
  published_at  bigint,
  mime          varchar,
  lang          varchar,
  char_count    int,
  full_text     varchar        -- extracted plain-text; bounded length
)

link_summary (
  url          varchar,
  model        varchar,
  summary      varchar,
  generated_at bigint,
  primary key (url, model)
)

link_tag (
  url    varchar,
  tag    varchar,
  primary key (url, tag)
)
```

`link.url` is the FK target. Rows in `link_content`, `link_summary`,
and `link_tag` are global across projects — a URL summarized once is
summarized for all projects that reference it. Saves cost.

## Components

```
ContentFetcher (commonMain interface)
└─ JvmContentFetcher (desktopMain, jvm) — uses OkHttp + Readability4J
└─ Android impl — same but using OkHttp + the Android-friendly extractor
└─ Tactics:
    ├─ Honor robots.txt
    ├─ User-Agent identifies Talon
    ├─ Time-bounded (10s connect, 30s read)
    ├─ Body cap: 5MB
    ├─ Skip behind-paywall sites (detect login walls heuristically; mark status=402)
    └─ PDF support via PDFBox; skip image/audio/video for v2

LinkSummarizer (commonMain)
├─ Reuses AiSettings (provider/baseUrl/apiKey/model)
├─ One prompt template:
│   "Summarize this article in 2 sentences for a research notebook.
│    Then list 3-7 single-word topic tags. Output JSON: {summary,
│    tags[]}."
└─ Writes to link_summary + link_tag

FetchOrchestrator (commonMain, session-scoped)
├─ Listens for new rows in `link` table
├─ Daily budget enforcement (read fetcher_budget_per_day from talon_meta;
│   default 50; 0 = disabled)
├─ Queue with retries
└─ Lives next to IndexerOrchestrator
```

## UI

- Heap cards (`GalleryGridScreen` / `GalleryPostScreen`): add a 2-line
  AI summary chip below the existing card content when
  `link_summary` exists for the URL.
- Diary post headers (`NotebookListScreen` / `NotebookComposeScreen`):
  same — extracted tags appear as chips in the header.
- New settings row: "Daily link-fetch budget" with a numeric input.
- New settings row: "Fetch links automatically in research projects"
  toggle (default on; off = manual-fetch only).

## Open (deferred to Phase 2 brainstorm)

- Manual "summarize this link" affordance vs full auto-fetch as
  default
- Re-fetch policy (URL content can change)
- Embeddings — punt; tag-based for v2
- Image/audio/video handling — out of scope for v2

---

# Phase 3 — Natural Language (architectural sketch)

Planning happens after Phase 2 lands.

## Scope

Build the "Ask anything across your research" surface. Convert NL
questions to urQL via the user's configured LLM, run them, render
results. Save useful questions for re-running.

## Schema additions (v2 → v3 migration)

```sql
saved_query (
  id            varchar primary key,
  scope         varchar,          -- 'global' | 'project'
  group_flag    varchar,          -- empty when scope='global'
  name          varchar,
  nl_question   varchar,
  urql          varchar,
  created_at    bigint,
  last_run_at   bigint
)
```

## Components

```
NlToUrqlTranslator (commonMain)
├─ Reads schema introspection from obelisk (table list, columns)
├─ Builds a system prompt: grammar cheatsheet + current schema + 10
│   few-shot examples
├─ Calls LLM via AiSettings
├─ Returns: { urql, explanation }
└─ Caches schema-prompt per session

ResearchAskScreen (commonMain)
├─ Text input
├─ Project scope selector ("All projects" or specific projects)
├─ On submit: NlToUrqlTranslator → show generated urQL in editable
│   textbox → run via ObeliskClient.query → render results
├─ "Save this question" button → SavedQueryRepo

SavedQueryRepo (commonMain)
├─ ObeliskClient-backed CRUD over saved_query table
└─ Returns Flow<List<SavedQuery>> for the rail's research view
```

## UI

- "Ask anything" pinned bar at the top of the projects-overview
  screen.
- "Saved questions" section on the same screen, below the project
  cards.
- Per-project ask: when user is inside a marked group, an "Ask this
  project" affordance scopes the next question to that group_flag.

## Open (deferred to Phase 3 brainstorm)

- Prompt-engineering strategy for urQL correctness at scale.
  Initial measurement: 20 hand-written test questions with
  correct expected urQL; iterate the prompt until ≥ 90% match.
- How to surface query failures usefully to the user.
- Whether to expose the generated urQL inline or only in an "explain"
  flyout.
- Result rendering modes: rows, single-value, count, list-of-links.
  LLM emits a hint about which mode.

---

# Cross-cutting

## Privacy and user control

- Default: Talon writes nothing to obelisk. The user must explicitly
  mark a group.
- All data lives on the user's ship, queried by the user's clients,
  summarized by the user's LLM provider/key. No Talon-server intermediary.
- Link fetching reveals reading interest to publishers (at the
  network level). User-Agent is "Talon/<version> Research Indexer";
  no per-user identifiers leak.
- LLM summaries: prompt + URL contents go to whatever provider
  AiSettings names (default: user's configured cloud provider). On-device
  summarization is not in v1 of any phase.

## Multi-ship

- Each ship has its own obelisk and its own marked-projects set. No
  cross-ship queries.
- Switching the active ship in Talon flips the Research rail tab to
  reflect the new ship's state.
- A user with no obelisk on any of their ships sees no Research tab
  on any session.

## Multi-device

- Per the Phase 1 decision: idempotent indexing from any device, with
  per-device watermarks in obelisk. No coordination protocol.
- Saved questions and project marks are written to obelisk, so they
  appear identically on every device immediately.

## Obelisk uninstall mid-session

- Capability flag flips false on the next probe.
- Indexer orchestrator detects, cancels in-flight jobs, surfaces a
  one-time toast: "Research data unavailable: %obelisk has been
  uninstalled. Re-installing will restore everything."
- Rail tab disappears.
- Indexed data is preserved on the ship's filesystem; reinstalling
  obelisk restores it.

# Non-goals (YAGNI)

- Indexing chats outside marked groups.
- Encrypting research data at rest (it's on the user's ship; standard
  Urbit security model applies).
- Furum / non-Tlon source integration. Possible in a later phase.
- Smart-folder UI as a separate primitive (saved questions in
  Phase 3 covers this).
- Activity graphs / dashboards (user explicitly de-prioritized
  during brainstorming).
- Export to other tools (Obsidian, Are.na, Pinboard).
- Embeddings / vector search. Tag-based retrieval first; revisit
  only if tag retrieval misses obvious things.
- On-device LLM for summarization. Cloud only via existing
  `AiSettings`.

# Glossary

- **Research project** — a Tlon group with the "research project"
  flag set in obelisk. Indexed; queryable.
- **Indexer** — the component that copies content from Talon's local
  Room cache into obelisk for marked projects.
- **Watermark** — `(install_id, group_flag) → max indexed ts`.
  Stored in obelisk; per-device-per-group.
- **install_id** — random UUID per Talon install, persisted in local
  Room. Different from the user's @p.
- **`/urql`** — Talon slash command for hand-written read-only queries.
- **`%tape2`** — obelisk's multi-statement script-execution action.
