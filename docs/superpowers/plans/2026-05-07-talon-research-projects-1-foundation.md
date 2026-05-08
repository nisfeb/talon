# Research Projects — Phase 1 (Foundation) implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up the substrate for research projects: per-ship `%obelisk` capability detection, an indexed copy of marked groups' chat/diary/heap content in obelisk, a Research rail tab listing those projects with live stats, and a read-only `/urql` slash for power users to hand-query the corpus.

**Architecture:** A single `ObeliskClient` interface mediates all desk traffic. Stateless components (`ResearchExtractor`, `IndexBatchBuilder`) handle pure data transformation. Stateful components (`ProjectRepo`, `WatermarkRepo`, `ResearchIndexer`, `ResearchCapability`) work against the client. An `IndexerOrchestrator` owns lifecycle: probe capability on session connect, spawn an indexer per marked project, listen to ProjectRepo flips, listen to new-message signals from sync, tear down on capability loss.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform 1.7.3, kotlinx.coroutines.flow, kotlinx.serialization.json, Room 2.7 KMP (for install_id local persistence), JUnit + kotlin.test, existing `TlonChatRepo.pokeRaw` for poking `%obelisk` with `%tape2` / `%commands` actions.

**Spec:** [`docs/superpowers/specs/2026-05-07-talon-research-projects-design.md`](../specs/2026-05-07-talon-research-projects-design.md)

---

## File structure

**Create:**

| Path | Responsibility |
|---|---|
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/research/ObeliskClient.kt` | Interface + `NoopObeliskClient` |
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/research/ObeliskRow.kt` | Result row / value types + JSON parser |
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/research/UrQL.kt` | Tiny urQL string-builder helpers (escape, batch INSERT) |
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/research/ResearchSchema.kt` | DDL constants, `CURRENT_VERSION`, init / migration scripts |
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/research/ResearchSchemaMigrator.kt` | Reads `talon_meta.schema_version`, applies init or migrations |
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/research/ResearchExtractor.kt` | Pure: `MessageEntity` → `ExtractedRows` (post / link / mention / attachment) |
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/research/IndexBatchBuilder.kt` | Pure: list of `ExtractedRows` → `%tape2` urQL script string |
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/research/ProjectRepo.kt` | Interface + `ObeliskProjectRepo` (mark/unmark/observe/deleteData) |
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/research/WatermarkRepo.kt` | Read/write `index_state` rows per (install_id, group_flag) |
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/research/ResearchIndexer.kt` | Orchestrates one project's backfill / live-index loop |
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/research/IndexProgress.kt` | Progress event sealed type |
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/research/IndexerOrchestrator.kt` | Lifecycle: spawns / cancels indexers as marks change |
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/research/ResearchCapability.kt` | Per-session probe + StateFlow |
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/research/ResearchProgressRepo.kt` | In-memory aggregate of per-project progress for the UI |
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/research/InstallId.kt` | Reads/persists the per-install UUID |
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/research/TlonObeliskClient.kt` | Real impl: pokes `%obelisk %tape2` / `%commands` via `pokeRaw` |
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/data/InstallIdEntity.kt` | Room entity (single-row table) holding the local UUID |
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/data/InstallIdDao.kt` | Read / upsert |
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/ResearchScreen.kt` | Projects-overview rail surface |
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/ResearchProjectCard.kt` | Project card composable |
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/MarkAsResearchAction.kt` | Toggle component for the group menu |
| `composeApp/src/commonTest/kotlin/io/nisfeb/talon/research/UrQLTest.kt` | Escape / batch INSERT |
| `composeApp/src/commonTest/kotlin/io/nisfeb/talon/research/ObeliskRowTest.kt` | JSON → `ObeliskRow` round-trip |
| `composeApp/src/commonTest/kotlin/io/nisfeb/talon/research/ResearchExtractorTest.kt` | Per-channel-kind extraction |
| `composeApp/src/commonTest/kotlin/io/nisfeb/talon/research/IndexBatchBuilderTest.kt` | Script shape |
| `composeApp/src/commonTest/kotlin/io/nisfeb/talon/research/ResearchSchemaMigratorTest.kt` | Init / migrate / refuse-newer paths |
| `composeApp/src/commonTest/kotlin/io/nisfeb/talon/research/FakeObeliskClient.kt` | In-memory fake used across tests |
| `composeApp/src/desktopTest/kotlin/io/nisfeb/talon/research/ResearchIndexerTest.kt` | End-to-end against fake obelisk |
| `composeApp/src/desktopTest/kotlin/io/nisfeb/talon/research/ProjectRepoTest.kt` | Mark / unmark / observe / deleteData |
| `composeApp/src/desktopTest/kotlin/io/nisfeb/talon/research/ResearchCapabilityTest.kt` | Probe state machine |

**Modify:**

| Path | What |
|---|---|
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/RailTab.kt` | Add `Research` entry |
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/RailItem.kt` | Add `Research(true)` entry |
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/RailItem.kt` | Update doc comment to mention Research is capability-gated |
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/SlashCommands.kt` | Add `/urql` SlashCommandSpec + `runUrql` dispatcher |
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/data/AppDatabase.kt` | Add `InstallIdEntity`, bump version 31→32, add `installIds()` accessor |
| `composeApp/src/androidMain/kotlin/io/nisfeb/talon/data/AppDatabase.android.kt` | Mirror entity list + version, add MIGRATION_31_32, register accessor |
| `composeApp/src/desktopMain/kotlin/io/nisfeb/talon/data/AppDatabase.desktop.kt` | Mirror accessor |
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/compose/App.kt` | Wire `ResearchCapability` + `IndexerOrchestrator` + Research surface route |
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/GroupHomeScreen.kt` | Add "Mark as research project" toggle to group menu |
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/DesktopShell.kt` | Render `ResearchScreen` when `RailTab.Research` is active |
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/SettingsScreen.kt` | Optional advanced row: "Re-run obelisk capability probe" debug button |

---

## Conventions used in this plan

- Tests in `commonTest` use `kotlin.test` (`@Test`, `assertEquals`, etc.). Tests in `desktopTest` use JUnit 4 (`@Before`, `@After`, `@Test`).
- All gradle commands assume Java 17. Use whatever JDK the dev shell has wired (`/usr/lib/jvm/java-17-openjdk` or `~/jdk-install/jdk-17.0.12+7` are both known to work). Prefix with `JAVA_HOME=...` when invoking from a tool that doesn't pick the right one automatically.
- Commit after each task using the project's `Co-Authored-By` footer style. Preferred format: `feat(research): <thing>` for new behavior, `test(research): <thing>` for test-only commits, `refactor(research): <thing>` for shape changes.
- Branch off `master` into `feature/research-foundation`. Do **not** push tags during this branch — Phase 1 is not ship-eligible on its own (we ship at the end of Phase 3, per the spec).

---

## Pre-work

### Task 0: Branch + dependencies

**Files:** none

- [ ] **Step 1:** Create the feature branch:

```bash
git checkout master
git pull --ff-only
git checkout -b feature/research-foundation
```

- [ ] **Step 2:** Confirm the spec is committed and discoverable (sanity check):

```bash
git log --oneline -- docs/superpowers/specs/2026-05-07-talon-research-projects-design.md | head -3
```

Expected: at least one commit with title `docs: research projects design spec`.

- [ ] **Step 3:** Verify the desktop tests are green on the branch starting point:

```bash
./gradlew :composeApp:desktopTest --console=plain
```

Expected: BUILD SUCCESSFUL. If anything is red on master, stop and fix before continuing.

---

## Phase A — Pure components (commonTest)

These components have no I/O and no platform dependencies. Build them first; their tests run fast and require no fakes beyond simple in-memory data.

### Task 1.1: `ObeliskClient` interface + `NoopObeliskClient`

**Files:**
- Create: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/research/ObeliskClient.kt`

- [ ] **Step 1: Write the file**

```kotlin
package io.nisfeb.talon.research

/**
 * Single chokepoint for everything Talon does with the user's
 * %obelisk desk on this ship. All research-domain reads and writes
 * go through this interface so tests can swap in a fake and the
 * real impl can centralize poke / scry plumbing.
 *
 * Two operation kinds:
 *   - [query]: SELECT-shaped; returns rows. Single statement.
 *   - [script]: %tape2 multi-statement DDL/DML. Atomic per obelisk
 *     semantics — pass/fail. No row return.
 *
 * Errors travel as [Result.failure] with an [ObeliskException]
 * carrying the urQL error message verbatim. Connection / transport
 * failures surface as [ObeliskException] too — callers don't need
 * to disambiguate by source for Phase 1.
 */
interface ObeliskClient {
    suspend fun query(urql: String): Result<List<ObeliskRow>>
    suspend fun script(urql: String): Result<Unit>
}

class ObeliskException(message: String) : RuntimeException(message)

/**
 * Used when the active session has no obelisk capability. Every
 * call fails with the same explanatory message. Lets callers wire
 * a non-null client into their constructors and gate behavior on
 * the [ResearchCapability] flag instead of nullable plumbing.
 */
object NoopObeliskClient : ObeliskClient {
    private val noCap = Result.failure<Nothing>(
        ObeliskException("obelisk not available on this session"),
    )
    @Suppress("UNCHECKED_CAST")
    override suspend fun query(urql: String): Result<List<ObeliskRow>> =
        noCap as Result<List<ObeliskRow>>
    @Suppress("UNCHECKED_CAST")
    override suspend fun script(urql: String): Result<Unit> =
        noCap as Result<Unit>
}
```

- [ ] **Step 2: Compile**

```bash
./gradlew :composeApp:compileKotlinDesktop
```

Expected: BUILD SUCCESSFUL. (`ObeliskRow` doesn't exist yet — this will fail to compile. Carry on; the next task adds it.)

- [ ] **Step 3: Defer compilation success to Task 1.2**

The compile failure on `ObeliskRow` is expected; we'll resolve it after Task 1.2 lands. No commit yet.

---

### Task 1.2: `ObeliskRow` types + JSON parser

**Files:**
- Create: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/research/ObeliskRow.kt`
- Create: `composeApp/src/commonTest/kotlin/io/nisfeb/talon/research/ObeliskRowTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// composeApp/src/commonTest/kotlin/io/nisfeb/talon/research/ObeliskRowTest.kt
package io.nisfeb.talon.research

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class ObeliskRowTest {

    @Test
    fun `parses single-row int + string result`() {
        val json = """
            {
              "columns": ["id", "name"],
              "rows": [[1, "alpha"]]
            }
        """.trimIndent()
        val rows = parseObeliskRows(json).getOrThrow()
        assertEquals(1, rows.size)
        assertEquals(2, rows[0].size)
        assertEquals(1L, rows[0].long("id"))
        assertEquals("alpha", rows[0].string("name"))
    }

    @Test
    fun `empty rows array yields empty list`() {
        val json = """{"columns":["id"],"rows":[]}"""
        val rows = parseObeliskRows(json).getOrThrow()
        assertTrue(rows.isEmpty())
    }

    @Test
    fun `unknown column lookup returns null`() {
        val json = """{"columns":["id"],"rows":[[1]]}"""
        val rows = parseObeliskRows(json).getOrThrow()
        assertEquals(null, rows[0].stringOrNull("missing"))
    }

    @Test
    fun `malformed JSON surfaces a failure`() {
        val r = parseObeliskRows("{not valid")
        assertTrue(r.isFailure)
    }

    @Test
    fun `boolean column round-trips`() {
        val json = """{"columns":["ok"],"rows":[[true],[false]]}"""
        val rows = parseObeliskRows(json).getOrThrow()
        assertEquals(2, rows.size)
        assertEquals(true, rows[0].bool("ok"))
        assertEquals(false, rows[1].bool("ok"))
    }
}
```

- [ ] **Step 2: Run the test, verify it fails**

```bash
./gradlew :composeApp:desktopTest --tests 'io.nisfeb.talon.research.ObeliskRowTest' --console=plain
```

Expected: FAIL with "unresolved reference: parseObeliskRows" or similar.

- [ ] **Step 3: Write the implementation**

```kotlin
// composeApp/src/commonMain/kotlin/io/nisfeb/talon/research/ObeliskRow.kt
package io.nisfeb.talon.research

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull

/**
 * One row of an obelisk query result. Keys are the column names;
 * values are the raw JSON cells. Use the typed accessors below to
 * pull out values; they normalize obelisk's primitive shapes
 * (numbers, strings, booleans) and return null for missing
 * columns or null cells.
 *
 * obelisk has no NULLs (per spec) — but a missing-column lookup is
 * a programming bug and we want it to surface as a `null` rather
 * than throw, so test code can assert on a single result column
 * without crashing on others.
 */
class ObeliskRow internal constructor(
    private val columns: List<String>,
    private val cells: List<JsonElement>,
) {
    val size: Int get() = cells.size

    fun stringOrNull(column: String): String? =
        cellOrNull(column)?.jsonPrimitive?.contentOrNull

    fun string(column: String): String =
        stringOrNull(column) ?: error("column '$column' missing or not a string")

    fun longOrNull(column: String): Long? =
        cellOrNull(column)?.jsonPrimitive?.longOrNull

    fun long(column: String): Long =
        longOrNull(column) ?: error("column '$column' missing or not a number")

    fun boolOrNull(column: String): Boolean? =
        cellOrNull(column)?.jsonPrimitive?.booleanOrNull

    fun bool(column: String): Boolean =
        boolOrNull(column) ?: error("column '$column' missing or not a boolean")

    private fun cellOrNull(column: String): JsonElement? {
        val idx = columns.indexOf(column)
        if (idx < 0) return null
        return cells.getOrNull(idx)
    }

    override fun toString(): String =
        columns.zip(cells).joinToString(prefix = "{", postfix = "}") { (c, v) -> "$c=$v" }
}

private val OBELISK_JSON = Json { ignoreUnknownKeys = true }

/**
 * Parse the JSON shape Talon expects from a successful obelisk
 * query response:
 *
 * ```
 * { "columns": ["id", "name"], "rows": [[1, "alpha"], [2, "beta"]] }
 * ```
 *
 * Real obelisk responses come from `sur/ast/hoon` deserialization
 * (see spec's "Open questions for the plan stage"). Talon's
 * adapter normalizes them into this JSON shape before calling
 * here, so parsing logic stays decoupled from the wire format.
 */
fun parseObeliskRows(json: String): Result<List<ObeliskRow>> = runCatching {
    val root = OBELISK_JSON.parseToJsonElement(json).jsonObject
    val columns: List<String> = root["columns"]
        ?.jsonArray
        ?.map { it.jsonPrimitive.content }
        ?: error("missing 'columns'")
    val rows: JsonArray = root["rows"]?.jsonArray ?: error("missing 'rows'")
    rows.map { row ->
        val cells = row.jsonArray.toList()
        ObeliskRow(columns = columns, cells = cells)
    }
}
```

- [ ] **Step 4: Run the tests, verify pass**

```bash
./gradlew :composeApp:desktopTest --tests 'io.nisfeb.talon.research.ObeliskRowTest' --console=plain
```

Expected: 5 passed.

- [ ] **Step 5: Compile the broader project to confirm Task 1.1 also compiles now**

```bash
./gradlew :composeApp:compileKotlinDesktop
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/research/ObeliskClient.kt \
        composeApp/src/commonMain/kotlin/io/nisfeb/talon/research/ObeliskRow.kt \
        composeApp/src/commonTest/kotlin/io/nisfeb/talon/research/ObeliskRowTest.kt
git commit -m "$(cat <<'EOF'
feat(research): ObeliskClient interface + ObeliskRow

Single chokepoint for talking to %obelisk; pure data types and a
NoopObeliskClient for capability-off sessions.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 1.3: `FakeObeliskClient` — in-memory test fake

**Files:**
- Create: `composeApp/src/commonTest/kotlin/io/nisfeb/talon/research/FakeObeliskClient.kt`

This is a test helper, not production code. It's used by every later test that needs a working `ObeliskClient` without a real ship.

- [ ] **Step 1: Write the file**

```kotlin
package io.nisfeb.talon.research

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * In-memory fake. Knows just enough urQL to support the tests:
 *
 *   - CREATE TABLE / DROP TABLE: tracks table names, no-op on cells.
 *   - INSERT INTO <table> VALUES (...): appends rows in declaration
 *     order (caller is responsible for matching column order).
 *   - SELECT … FROM <table> WHERE …: a few named query shapes the
 *     repo / migrator code actually issues. Anything else returns
 *     an empty result.
 *
 * This is deliberately not a urQL interpreter. It's a fixture that
 * answers the specific questions Talon code asks and would fail
 * loudly if asked something unexpected — keeping tests honest about
 * the queries they exercise.
 */
class FakeObeliskClient(
    private val canned: MutableMap<String, List<ObeliskRow>> = mutableMapOf(),
    val scripts: MutableList<String> = mutableListOf(),
) : ObeliskClient {

    var failNextScript: String? = null
    var failNextQuery: String? = null

    override suspend fun query(urql: String): Result<List<ObeliskRow>> {
        failNextQuery?.let {
            failNextQuery = null
            return Result.failure(ObeliskException(it))
        }
        // Match canned queries by exact key first, then by prefix.
        canned[urql]?.let { return Result.success(it) }
        val prefix = canned.keys.firstOrNull { urql.startsWith(it) }
        return Result.success(prefix?.let { canned.getValue(it) } ?: emptyList())
    }

    override suspend fun script(urql: String): Result<Unit> {
        failNextScript?.let {
            failNextScript = null
            return Result.failure(ObeliskException(it))
        }
        scripts += urql
        return Result.success(Unit)
    }

    fun stub(urql: String, rows: List<ObeliskRow>) {
        canned[urql] = rows
    }

    fun stubVersion(version: Int?) {
        val key = "SELECT value FROM talon_meta WHERE key='schema_version'"
        canned[key] = if (version == null) emptyList() else listOf(
            buildRow("value" to JsonPrimitive(version.toString())),
        )
    }
}

internal fun buildRow(vararg pairs: Pair<String, JsonElement>): ObeliskRow {
    val (cols, cells) = pairs.unzip()
    val cls = ObeliskRow::class.java
    val ctor = cls.declaredConstructors.first { it.parameterCount == 2 }
    ctor.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    return ctor.newInstance(cols.toList(), cells.toList()) as ObeliskRow
}
```

The `buildRow` helper uses reflection because `ObeliskRow`'s primary constructor is `internal`. We don't want to widen its visibility just for tests — the reflection cost is paid once per test row and these tests don't run hot.

- [ ] **Step 2: Verify it compiles in the test source set**

```bash
./gradlew :composeApp:compileTestKotlinDesktop
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/commonTest/kotlin/io/nisfeb/talon/research/FakeObeliskClient.kt
git commit -m "$(cat <<'EOF'
test(research): FakeObeliskClient for in-memory urQL fakes

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 1.4: `UrQL` string builders

**Files:**
- Create: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/research/UrQL.kt`
- Create: `composeApp/src/commonTest/kotlin/io/nisfeb/talon/research/UrQLTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package io.nisfeb.talon.research

import kotlin.test.Test
import kotlin.test.assertEquals

class UrQLTest {

    @Test
    fun `escapeString doubles single quotes`() {
        assertEquals("'hello'", UrQL.escapeString("hello"))
        assertEquals("'it''s ok'", UrQL.escapeString("it's ok"))
    }

    @Test
    fun `escapeString preserves empty string as ''`() {
        assertEquals("''", UrQL.escapeString(""))
    }

    @Test
    fun `escapeString collapses backslashes per urQL convention`() {
        // Single quotes are the only escape. Backslashes are literal.
        assertEquals("'C:\\path'", UrQL.escapeString("C:\\path"))
    }

    @Test
    fun `insertBatch emits one INSERT per row in order`() {
        val rows = listOf(
            mapOf("id" to "1", "name" to UrQL.escapeString("a")),
            mapOf("id" to "2", "name" to UrQL.escapeString("b")),
        )
        val script = UrQL.insertBatch("post", listOf("id", "name"), rows)
        val expected = """
            INSERT INTO post (id, name) VALUES (1, 'a');
            INSERT INTO post (id, name) VALUES (2, 'b');
        """.trimIndent()
        assertEquals(expected, script.trim())
    }

    @Test
    fun `insertBatch empty list yields empty string`() {
        val script = UrQL.insertBatch("post", listOf("id"), emptyList())
        assertEquals("", script)
    }
}
```

- [ ] **Step 2: Run, verify fail**

```bash
./gradlew :composeApp:desktopTest --tests 'io.nisfeb.talon.research.UrQLTest' --console=plain
```

Expected: FAIL with "unresolved reference: UrQL".

- [ ] **Step 3: Write the impl**

```kotlin
package io.nisfeb.talon.research

/**
 * Tiny string-builder helpers for the urQL we generate from
 * Talon's indexer. Kept narrow on purpose: any change to the
 * syntax shape lives here and gets snapshot-tested by
 * [UrQLTest]. We do **not** maintain a urQL grammar in code;
 * the queries we emit are mechanical and small.
 *
 * Single-quote-doubling is the only string escape obelisk
 * documents. Keep behavior the same as that for SQL standard
 * single-quoted literals.
 */
object UrQL {
    fun escapeString(s: String): String =
        "'" + s.replace("'", "''") + "'"

    /**
     * Build a sequence of single-row INSERTs for [table], one
     * statement per row, semicolon-terminated, newline-separated.
     * Returns "" for an empty input list (callers can include
     * the result directly in a script).
     *
     * `rows` is a list of pre-formatted column-value maps:
     * values must already be urQL-shaped (numbers as bare
     * digits, strings as `escapeString` output, booleans as
     * 'true'/'false'). The builder doesn't second-guess types.
     */
    fun insertBatch(
        table: String,
        columns: List<String>,
        rows: List<Map<String, String>>,
    ): String {
        if (rows.isEmpty()) return ""
        val cols = columns.joinToString(", ")
        return rows.joinToString(separator = "\n") { row ->
            val values = columns.joinToString(", ") { c -> row[c] ?: error("missing column $c") }
            "INSERT INTO $table ($cols) VALUES ($values);"
        }
    }
}
```

- [ ] **Step 4: Run, verify pass**

```bash
./gradlew :composeApp:desktopTest --tests 'io.nisfeb.talon.research.UrQLTest' --console=plain
```

Expected: 5 passed.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/research/UrQL.kt \
        composeApp/src/commonTest/kotlin/io/nisfeb/talon/research/UrQLTest.kt
git commit -m "feat(research): UrQL string-builder helpers

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 1.5: `ResearchSchema` — DDL constants

**Files:**
- Create: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/research/ResearchSchema.kt`

This is a constants file — no tests of its own; the migrator tests in Task 2.1 exercise it.

- [ ] **Step 1: Write the file**

```kotlin
package io.nisfeb.talon.research

/**
 * Versioned obelisk schema for the research feature. The
 * migrator (see [ResearchSchemaMigrator]) reads the current
 * version from `talon_meta.schema_version` and applies the
 * scripts in [migrations] in order to bring it up to
 * [CURRENT_VERSION].
 *
 * Conventions:
 *   - All tables follow obelisk's no-NULL rule. Columns that
 *     don't apply for a given row use empty string or 0.
 *   - Primary keys are explicit and chosen so concurrent
 *     [ResearchIndexer] runs from multiple devices can re-INSERT
 *     the same row safely (set semantics).
 *   - Add a new schema version by:
 *       1. bumping CURRENT_VERSION
 *       2. adding an entry to migrations
 *       3. updating the comment block on this object
 *       4. adding a test in ResearchSchemaMigratorTest
 *
 * Version 1 (current): adds talon_meta, project, post, link,
 * mention, attachment, index_state.
 */
object ResearchSchema {

    const val CURRENT_VERSION: Int = 1

    /**
     * Init script: applied when there is no existing
     * `talon_meta.schema_version` row at all. Atomic per
     * obelisk's script semantics — either the whole schema
     * lands or nothing changes.
     */
    val initScript: String = """
        CREATE TABLE talon_meta (
          key varchar PRIMARY KEY,
          value varchar
        );
        CREATE TABLE project (
          group_flag varchar PRIMARY KEY,
          marked_at bigint,
          name varchar
        );
        CREATE TABLE post (
          group_flag varchar,
          channel varchar,
          channel_kind varchar,
          msg_id varchar,
          ts bigint,
          ship varchar,
          title varchar,
          text varchar,
          char_count int,
          PRIMARY KEY (group_flag, msg_id)
        );
        CREATE TABLE link (
          group_flag varchar,
          msg_id varchar,
          ts bigint,
          ship varchar,
          url varchar,
          host varchar,
          scheme varchar,
          PRIMARY KEY (group_flag, msg_id, url)
        );
        CREATE TABLE mention (
          group_flag varchar,
          msg_id varchar,
          ts bigint,
          from_ship varchar,
          to_ship varchar,
          PRIMARY KEY (group_flag, msg_id, to_ship)
        );
        CREATE TABLE attachment (
          group_flag varchar,
          msg_id varchar,
          ts bigint,
          ship varchar,
          mime varchar,
          kind varchar,
          PRIMARY KEY (group_flag, msg_id, mime)
        );
        CREATE TABLE index_state (
          install_id varchar,
          group_flag varchar,
          last_ts bigint,
          PRIMARY KEY (install_id, group_flag)
        );
        INSERT INTO talon_meta (key, value) VALUES ('schema_version', '1');
    """.trimIndent()

    /**
     * Empty for v1 — there are no prior versions to migrate from.
     * Future entries: `1 to "ALTER TABLE … ; UPDATE talon_meta SET value='2' WHERE key='schema_version';"`.
     */
    val migrations: Map<Int, String> = emptyMap()
}
```

- [ ] **Step 2: Compile**

```bash
./gradlew :composeApp:compileKotlinDesktop
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/research/ResearchSchema.kt
git commit -m "feat(research): obelisk schema v1 (DDL constants)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 1.6: `ResearchExtractor` — pure MessageEntity → ExtractedRows

**Files:**
- Create: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/research/ResearchExtractor.kt`
- Create: `composeApp/src/commonTest/kotlin/io/nisfeb/talon/research/ResearchExtractorTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package io.nisfeb.talon.research

import io.nisfeb.talon.data.MessageEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResearchExtractorTest {

    private fun chatMsg(
        id: String = "~zod/1.000",
        author: String = "~zod",
        sentMs: Long = 0L,
        contentJson: String = """[{"inline":["hello"]}]""",
    ): MessageEntity = MessageEntity(
        whom = "chat/~zod/general",
        id = id, author = author, sentMs = sentMs,
        contentJson = contentJson, kind = "/chat",
    )

    @Test
    fun `chat message yields one post row, no links`() {
        val rows = ResearchExtractor.extract(chatMsg(), groupFlag = "~zod/foo")
        assertEquals(1, rows.posts.size)
        assertTrue(rows.links.isEmpty())
        assertEquals("chat", rows.posts[0].channelKind)
        assertEquals("hello", rows.posts[0].text)
    }

    @Test
    fun `inline link gets a link row`() {
        val msg = chatMsg(
            contentJson = """[{"inline":[{"link":{"href":"https://example.com/a","content":"a"}}]}]""",
        )
        val rows = ResearchExtractor.extract(msg, groupFlag = "~zod/foo")
        assertEquals(1, rows.links.size)
        assertEquals("https://example.com/a", rows.links[0].url)
        assertEquals("example.com", rows.links[0].host)
        assertEquals("https", rows.links[0].scheme)
    }

    @Test
    fun `mention turns into a mention row`() {
        val msg = chatMsg(
            contentJson = """[{"inline":[{"ship":"~bus"}]}]""",
        )
        val rows = ResearchExtractor.extract(msg, groupFlag = "~zod/foo")
        assertEquals(1, rows.mentions.size)
        assertEquals("~bus", rows.mentions[0].toShip)
        assertEquals("~zod", rows.mentions[0].fromShip)
    }

    @Test
    fun `image attachment turns into an attachment row`() {
        val msg = chatMsg(
            contentJson = """[{"block":{"image":{"src":"https://x.com/a.jpg","alt":"","width":0,"height":0}}}]""",
        )
        val rows = ResearchExtractor.extract(msg, groupFlag = "~zod/foo")
        assertEquals(1, rows.attachments.size)
        assertEquals("image", rows.attachments[0].kind)
    }

    @Test
    fun `diary post pulls title separately from body`() {
        val diary = MessageEntity(
            whom = "diary/~zod/journal",
            id = "~zod/2.000", author = "~zod", sentMs = 100L,
            contentJson = """[{"inline":["body"]}]""",
            kind = "/diary",
            title = "About geckos",
        )
        val rows = ResearchExtractor.extract(diary, groupFlag = "~zod/foo")
        assertEquals(1, rows.posts.size)
        assertEquals("diary", rows.posts[0].channelKind)
        assertEquals("About geckos", rows.posts[0].title)
        assertEquals("body", rows.posts[0].text)
    }

    @Test
    fun `deleted message produces no rows`() {
        val deleted = chatMsg().copy(isDeleted = true)
        val rows = ResearchExtractor.extract(deleted, groupFlag = "~zod/foo")
        assertTrue(rows.posts.isEmpty())
        assertTrue(rows.links.isEmpty())
    }
}
```

- [ ] **Step 2: Run, verify fail**

```bash
./gradlew :composeApp:desktopTest --tests 'io.nisfeb.talon.research.ResearchExtractorTest' --console=plain
```

Expected: FAIL on unresolved `ResearchExtractor` / `ExtractedRows`.

- [ ] **Step 3: Write the impl**

```kotlin
package io.nisfeb.talon.research

import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.urbit.ChannelType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pure transformation from a single Room [MessageEntity] into the
 * obelisk-shaped row bundle the indexer will write. No I/O, no
 * platform calls — testable as a function-of-its-input.
 *
 * One [MessageEntity] always becomes 0 or 1 [PostRow] (deleted
 * messages produce 0). The same message may shed multiple
 * [LinkRow] / [MentionRow] / [AttachmentRow] entries depending
 * on its body.
 */
data class ExtractedRows(
    val posts: List<PostRow>,
    val links: List<LinkRow>,
    val mentions: List<MentionRow>,
    val attachments: List<AttachmentRow>,
)

data class PostRow(
    val groupFlag: String,
    val channel: String,
    val channelKind: String,    // "chat" | "diary" | "heap"
    val msgId: String,
    val ts: Long,
    val ship: String,
    val title: String,
    val text: String,
    val charCount: Int,
)

data class LinkRow(
    val groupFlag: String,
    val msgId: String,
    val ts: Long,
    val ship: String,
    val url: String,
    val host: String,
    val scheme: String,
)

data class MentionRow(
    val groupFlag: String,
    val msgId: String,
    val ts: Long,
    val fromShip: String,
    val toShip: String,
)

data class AttachmentRow(
    val groupFlag: String,
    val msgId: String,
    val ts: Long,
    val ship: String,
    val mime: String,
    val kind: String,           // "image" | "audio" | "video" | "file"
)

object ResearchExtractor {

    private val JSON = Json { ignoreUnknownKeys = true }

    fun extract(message: MessageEntity, groupFlag: String): ExtractedRows {
        if (message.isDeleted) return EMPTY
        val channelKind = when (ChannelType.fromWhom(message.whom)) {
            ChannelType.Chat -> "chat"
            ChannelType.Notebook -> "diary"
            ChannelType.Gallery -> "heap"
        }
        val channel = message.whom
        val text = extractPlainText(message.contentJson)
        val posts = listOf(
            PostRow(
                groupFlag = groupFlag,
                channel = channel,
                channelKind = channelKind,
                msgId = message.id,
                ts = message.sentMs,
                ship = message.author,
                title = message.title.orEmpty(),
                text = text,
                charCount = text.length,
            ),
        )
        val verses = parseVerses(message.contentJson)
        val links = collectLinks(verses).map {
            LinkRow(
                groupFlag = groupFlag,
                msgId = message.id, ts = message.sentMs, ship = message.author,
                url = it,
                host = parseHost(it),
                scheme = parseScheme(it),
            )
        }
        val mentions = collectMentions(verses).map {
            MentionRow(
                groupFlag = groupFlag,
                msgId = message.id, ts = message.sentMs,
                fromShip = message.author, toShip = it,
            )
        }
        val attachments = collectAttachments(verses).map { (kind, mime) ->
            AttachmentRow(
                groupFlag = groupFlag,
                msgId = message.id, ts = message.sentMs, ship = message.author,
                mime = mime, kind = kind,
            )
        }
        return ExtractedRows(posts, links, mentions, attachments)
    }

    private val EMPTY = ExtractedRows(emptyList(), emptyList(), emptyList(), emptyList())

    private fun parseVerses(json: String): List<JsonElement> = runCatching {
        JSON.parseToJsonElement(json).jsonArray.toList()
    }.getOrElse { emptyList() }

    /**
     * Walk every inline element across all verses and concatenate
     * any plain-string children. This is the simplest text
     * extraction; bold/italic/strike inlines all carry their text
     * as the same primitive shape.
     */
    private fun extractPlainText(json: String): String {
        val verses = parseVerses(json)
        val sb = StringBuilder()
        for (v in verses) {
            val obj = v as? JsonObject ?: continue
            val inlines = obj["inline"]?.jsonArray ?: continue
            for (el in inlines) {
                when {
                    el is JsonPrimitive -> sb.append(el.content)
                    el is JsonObject -> {
                        // Common nested shapes: {bold:[…]}, {italic:[…]}, {link:{content:…}}, etc.
                        for ((_, child) in el) {
                            when {
                                child is JsonPrimitive -> sb.append(child.contentOrNull.orEmpty())
                                child is JsonObject -> child["content"]
                                    ?.jsonPrimitive?.contentOrNull?.let(sb::append)
                            }
                        }
                    }
                    else -> {}
                }
            }
        }
        return sb.toString()
    }

    private fun collectLinks(verses: List<JsonElement>): List<String> {
        val out = mutableListOf<String>()
        for (v in verses) {
            val obj = v as? JsonObject ?: continue
            val inlines = obj["inline"]?.jsonArray ?: continue
            for (el in inlines) {
                if (el is JsonObject) {
                    el["link"]?.jsonObject?.get("href")?.jsonPrimitive?.contentOrNull?.let(out::add)
                }
            }
        }
        return out
    }

    private fun collectMentions(verses: List<JsonElement>): List<String> {
        val out = mutableListOf<String>()
        for (v in verses) {
            val obj = v as? JsonObject ?: continue
            val inlines = obj["inline"]?.jsonArray ?: continue
            for (el in inlines) {
                if (el is JsonObject) {
                    el["ship"]?.jsonPrimitive?.contentOrNull?.let(out::add)
                }
            }
        }
        return out
    }

    /**
     * Walk verse-level `block` entries and extract image/audio/video
     * attachments. We don't try to detect arbitrary file uploads
     * here; if obelisk-side analysis later wants more granular
     * "kind" values, we'll extend ChannelType-style helpers. Mime
     * defaults to image/audio/video when block type is recognized
     * but specific mime is unknown — sufficient for v1 stats.
     */
    private fun collectAttachments(verses: List<JsonElement>): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for (v in verses) {
            val obj = v as? JsonObject ?: continue
            val block = obj["block"] as? JsonObject ?: continue
            when {
                "image" in block -> out += "image" to "image/*"
                // Audio / video block shapes may land later; if they do,
                // add cases here. Heap channels with arbitrary file
                // attachments would surface as block.cite or block.code,
                // not block.image — those are handled in Phase 2.
            }
        }
        return out
    }

    private fun parseHost(url: String): String =
        runCatching {
            val noScheme = url.substringAfter("://", url)
            val authority = noScheme.substringBefore('/').substringBefore('?')
            authority.substringAfter('@').substringBefore(':')
        }.getOrDefault("")

    private fun parseScheme(url: String): String {
        val idx = url.indexOf("://")
        return if (idx > 0) url.substring(0, idx) else ""
    }
}
```

- [ ] **Step 4: Run, verify pass**

```bash
./gradlew :composeApp:desktopTest --tests 'io.nisfeb.talon.research.ResearchExtractorTest' --console=plain
```

Expected: 6 passed.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/research/ResearchExtractor.kt \
        composeApp/src/commonTest/kotlin/io/nisfeb/talon/research/ResearchExtractorTest.kt
git commit -m "feat(research): pure MessageEntity → ExtractedRows extractor

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 1.7: `IndexBatchBuilder` — ExtractedRows → urQL script

**Files:**
- Create: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/research/IndexBatchBuilder.kt`
- Create: `composeApp/src/commonTest/kotlin/io/nisfeb/talon/research/IndexBatchBuilderTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package io.nisfeb.talon.research

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IndexBatchBuilderTest {

    private val groupFlag = "~zod/foo"

    private fun simplePost(id: String, ts: Long) = ExtractedRows(
        posts = listOf(
            PostRow(groupFlag, "chat/~zod/general", "chat", id, ts, "~zod", "", "hi", 2),
        ),
        links = emptyList(),
        mentions = emptyList(),
        attachments = emptyList(),
    )

    @Test
    fun `empty input yields empty script`() {
        val script = IndexBatchBuilder.build(emptyList())
        assertEquals("", script)
    }

    @Test
    fun `one post yields a single INSERT INTO post`() {
        val script = IndexBatchBuilder.build(listOf(simplePost("~zod/1", 100L)))
        assertTrue(script.contains("INSERT INTO post"), "missing post insert: $script")
        assertTrue(script.contains("'~zod/1'"))
        assertTrue(script.contains("100"))
    }

    @Test
    fun `mixed rows produce inserts grouped by table in stable order`() {
        val rows = ExtractedRows(
            posts = listOf(
                PostRow(groupFlag, "chat/~zod/g", "chat", "~zod/1", 1L, "~zod", "", "x", 1),
            ),
            links = listOf(
                LinkRow(groupFlag, "~zod/1", 1L, "~zod", "https://e.com", "e.com", "https"),
            ),
            mentions = listOf(
                MentionRow(groupFlag, "~zod/1", 1L, "~zod", "~bus"),
            ),
            attachments = emptyList(),
        )
        val script = IndexBatchBuilder.build(listOf(rows))
        val postIdx = script.indexOf("INSERT INTO post")
        val linkIdx = script.indexOf("INSERT INTO link")
        val mentionIdx = script.indexOf("INSERT INTO mention")
        assertTrue(postIdx >= 0 && linkIdx > postIdx && mentionIdx > linkIdx,
            "tables not in stable order: $script")
    }

    @Test
    fun `single quotes in text are escaped`() {
        val rows = ExtractedRows(
            posts = listOf(
                PostRow(groupFlag, "chat/~zod/g", "chat", "~zod/1", 1L, "~zod", "", "it's fine", 9),
            ),
            links = emptyList(), mentions = emptyList(), attachments = emptyList(),
        )
        val script = IndexBatchBuilder.build(listOf(rows))
        assertTrue(script.contains("'it''s fine'"), "single quote not escaped: $script")
    }
}
```

- [ ] **Step 2: Run, verify fail**

```bash
./gradlew :composeApp:desktopTest --tests 'io.nisfeb.talon.research.IndexBatchBuilderTest' --console=plain
```

Expected: FAIL on unresolved `IndexBatchBuilder`.

- [ ] **Step 3: Write the impl**

```kotlin
package io.nisfeb.talon.research

/**
 * Build a `%tape2`-shaped urQL script from a list of
 * [ExtractedRows] bundles. Output is one INSERT per row, grouped
 * by table (post → link → mention → attachment) so a reader of
 * the script can scan a coherent block per table.
 *
 * Returns "" on empty input. Caller is responsible for not
 * sending empty scripts to the desk; passing an empty string is
 * a no-op only because [ObeliskClient.script] tolerates it.
 *
 * Numeric values are emitted bare (no quotes). String values go
 * through [UrQL.escapeString].
 */
object IndexBatchBuilder {

    fun build(bundles: List<ExtractedRows>): String {
        if (bundles.isEmpty()) return ""

        val posts = bundles.flatMap { it.posts }
        val links = bundles.flatMap { it.links }
        val mentions = bundles.flatMap { it.mentions }
        val attachments = bundles.flatMap { it.attachments }

        val sections = listOf(
            section("post",
                listOf("group_flag", "channel", "channel_kind", "msg_id", "ts",
                       "ship", "title", "text", "char_count"),
                posts.map(::postValues)),
            section("link",
                listOf("group_flag", "msg_id", "ts", "ship", "url", "host", "scheme"),
                links.map(::linkValues)),
            section("mention",
                listOf("group_flag", "msg_id", "ts", "from_ship", "to_ship"),
                mentions.map(::mentionValues)),
            section("attachment",
                listOf("group_flag", "msg_id", "ts", "ship", "mime", "kind"),
                attachments.map(::attachmentValues)),
        ).filter { it.isNotEmpty() }

        return sections.joinToString(separator = "\n\n")
    }

    private fun section(table: String, columns: List<String>, rows: List<Map<String, String>>): String =
        UrQL.insertBatch(table, columns, rows)

    private fun postValues(p: PostRow): Map<String, String> = mapOf(
        "group_flag" to UrQL.escapeString(p.groupFlag),
        "channel" to UrQL.escapeString(p.channel),
        "channel_kind" to UrQL.escapeString(p.channelKind),
        "msg_id" to UrQL.escapeString(p.msgId),
        "ts" to p.ts.toString(),
        "ship" to UrQL.escapeString(p.ship),
        "title" to UrQL.escapeString(p.title),
        "text" to UrQL.escapeString(p.text),
        "char_count" to p.charCount.toString(),
    )

    private fun linkValues(l: LinkRow): Map<String, String> = mapOf(
        "group_flag" to UrQL.escapeString(l.groupFlag),
        "msg_id" to UrQL.escapeString(l.msgId),
        "ts" to l.ts.toString(),
        "ship" to UrQL.escapeString(l.ship),
        "url" to UrQL.escapeString(l.url),
        "host" to UrQL.escapeString(l.host),
        "scheme" to UrQL.escapeString(l.scheme),
    )

    private fun mentionValues(m: MentionRow): Map<String, String> = mapOf(
        "group_flag" to UrQL.escapeString(m.groupFlag),
        "msg_id" to UrQL.escapeString(m.msgId),
        "ts" to m.ts.toString(),
        "from_ship" to UrQL.escapeString(m.fromShip),
        "to_ship" to UrQL.escapeString(m.toShip),
    )

    private fun attachmentValues(a: AttachmentRow): Map<String, String> = mapOf(
        "group_flag" to UrQL.escapeString(a.groupFlag),
        "msg_id" to UrQL.escapeString(a.msgId),
        "ts" to a.ts.toString(),
        "ship" to UrQL.escapeString(a.ship),
        "mime" to UrQL.escapeString(a.mime),
        "kind" to UrQL.escapeString(a.kind),
    )
}
```

- [ ] **Step 4: Run, verify pass**

```bash
./gradlew :composeApp:desktopTest --tests 'io.nisfeb.talon.research.IndexBatchBuilderTest' --console=plain
```

Expected: 4 passed.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/research/IndexBatchBuilder.kt \
        composeApp/src/commonTest/kotlin/io/nisfeb/talon/research/IndexBatchBuilderTest.kt
git commit -m "feat(research): IndexBatchBuilder turns ExtractedRows into urQL

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Phase B — Stateful components against fake obelisk

### Task 2.1: `ResearchSchemaMigrator`

**Files:**
- Create: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/research/ResearchSchemaMigrator.kt`
- Create: `composeApp/src/commonTest/kotlin/io/nisfeb/talon/research/ResearchSchemaMigratorTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package io.nisfeb.talon.research

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResearchSchemaMigratorTest {

    @Test
    fun `init runs initScript when no version row exists`() = runTest {
        val client = FakeObeliskClient()
        client.stubVersion(null)
        val migrator = ResearchSchemaMigrator(client)

        val result = migrator.ensureCurrent()
        assertEquals(ResearchSchema.CURRENT_VERSION, result.getOrThrow())
        assertEquals(1, client.scripts.size)
        assertTrue(client.scripts[0].startsWith("CREATE TABLE talon_meta"))
    }

    @Test
    fun `current version is a no-op`() = runTest {
        val client = FakeObeliskClient()
        client.stubVersion(ResearchSchema.CURRENT_VERSION)
        val migrator = ResearchSchemaMigrator(client)

        val result = migrator.ensureCurrent()
        assertEquals(ResearchSchema.CURRENT_VERSION, result.getOrThrow())
        assertEquals(0, client.scripts.size)
    }

    @Test
    fun `newer version on the ship surfaces a NewerSchemaPresent failure`() = runTest {
        val client = FakeObeliskClient()
        client.stubVersion(ResearchSchema.CURRENT_VERSION + 1)
        val migrator = ResearchSchemaMigrator(client)

        val result = migrator.ensureCurrent()
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is NewerSchemaPresent)
        assertEquals(0, client.scripts.size)
    }

    @Test
    fun `init failure bubbles as Result_failure and writes nothing further`() = runTest {
        val client = FakeObeliskClient()
        client.stubVersion(null)
        client.failNextScript = "obelisk script failed"

        val migrator = ResearchSchemaMigrator(client)
        val result = migrator.ensureCurrent()
        assertTrue(result.isFailure)
        assertEquals(0, client.scripts.size, "failed script must not be recorded")
    }
}
```

- [ ] **Step 2: Run, verify fail**

```bash
./gradlew :composeApp:desktopTest --tests 'io.nisfeb.talon.research.ResearchSchemaMigratorTest' --console=plain
```

Expected: FAIL on unresolved `ResearchSchemaMigrator` and `NewerSchemaPresent`.

- [ ] **Step 3: Write the impl**

```kotlin
package io.nisfeb.talon.research

class NewerSchemaPresent(val foundVersion: Int) :
    RuntimeException("obelisk schema v$foundVersion is newer than this Talon (${ResearchSchema.CURRENT_VERSION}); upgrade Talon")

/**
 * Brings an obelisk's research schema to [ResearchSchema.CURRENT_VERSION].
 *
 * Algorithm:
 *   1. Read `SELECT value FROM talon_meta WHERE key='schema_version'`.
 *      Empty result ⇒ no schema yet ⇒ run [ResearchSchema.initScript].
 *   2. Equal to current ⇒ no-op, success.
 *   3. Greater than current ⇒ [NewerSchemaPresent] failure (Talon is
 *      stale; refusing to write protects forward-compat).
 *   4. Less than current ⇒ apply each migration script in order, each
 *      atomic per obelisk's script semantics.
 *
 * Returns [Result.success] with the resulting version on success.
 */
class ResearchSchemaMigrator(private val obelisk: ObeliskClient) {

    suspend fun ensureCurrent(): Result<Int> {
        val current = readVersion().getOrElse { return Result.failure(it) }
        return when {
            current == null -> {
                obelisk.script(ResearchSchema.initScript)
                    .map { ResearchSchema.CURRENT_VERSION }
            }
            current == ResearchSchema.CURRENT_VERSION -> Result.success(current)
            current > ResearchSchema.CURRENT_VERSION ->
                Result.failure(NewerSchemaPresent(current))
            else -> migrate(from = current)
        }
    }

    private suspend fun readVersion(): Result<Int?> = runCatching {
        val rows = obelisk.query(QUERY_VERSION).getOrThrow()
        rows.firstOrNull()?.string("value")?.toIntOrNull()
    }

    private suspend fun migrate(from: Int): Result<Int> {
        var v = from
        while (v < ResearchSchema.CURRENT_VERSION) {
            val script = ResearchSchema.migrations[v]
                ?: return Result.failure(
                    IllegalStateException("no migration registered from v$v"),
                )
            val r = obelisk.script(script)
            if (r.isFailure) return Result.failure(r.exceptionOrNull()!!)
            v += 1
        }
        return Result.success(v)
    }

    companion object {
        const val QUERY_VERSION = "SELECT value FROM talon_meta WHERE key='schema_version'"
    }
}
```

- [ ] **Step 4: Run, verify pass**

```bash
./gradlew :composeApp:desktopTest --tests 'io.nisfeb.talon.research.ResearchSchemaMigratorTest' --console=plain
```

Expected: 4 passed.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/research/ResearchSchemaMigrator.kt \
        composeApp/src/commonTest/kotlin/io/nisfeb/talon/research/ResearchSchemaMigratorTest.kt
git commit -m "feat(research): schema migrator (init / migrate / refuse-newer)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 2.2: install_id local persistence (Room entity + DAO + repo)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/data/InstallIdEntity.kt`
- Create: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/data/InstallIdDao.kt`
- Create: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/research/InstallId.kt`
- Modify: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/data/AppDatabase.kt`
- Modify: `composeApp/src/androidMain/kotlin/io/nisfeb/talon/data/AppDatabase.android.kt`
- Modify: `composeApp/src/desktopMain/kotlin/io/nisfeb/talon/data/AppDatabase.desktop.kt`

- [ ] **Step 1: Write the entity**

```kotlin
// composeApp/src/commonMain/kotlin/io/nisfeb/talon/data/InstallIdEntity.kt
package io.nisfeb.talon.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Single-row table holding this Talon installation's persistent
 * UUID. Used by the research feature's per-device watermark
 * tracking — different installations on the same ship coexist
 * because each tracks its own (install_id, group_flag) row in
 * obelisk's `index_state`.
 *
 * The table holds at most one row; we use a constant key so we
 * can upsert without scanning.
 */
@Entity(tableName = "install_id")
data class InstallIdEntity(
    @PrimaryKey val key: String = "self",
    val uuid: String,
)
```

- [ ] **Step 2: Write the DAO**

```kotlin
// composeApp/src/commonMain/kotlin/io/nisfeb/talon/data/InstallIdDao.kt
package io.nisfeb.talon.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface InstallIdDao {
    @Query("SELECT * FROM install_id WHERE key = 'self' LIMIT 1")
    suspend fun getSelf(): InstallIdEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: InstallIdEntity)
}
```

- [ ] **Step 3: Wire into AppDatabase (common)**

```kotlin
// composeApp/src/commonMain/kotlin/io/nisfeb/talon/data/AppDatabase.kt
// Add InstallIdEntity to the @Database entities list, bump version 31 → 32,
// and add an abstract accessor:
abstract fun installIds(): InstallIdDao
```

The existing `AppDatabase.kt` already enumerates entities; mirror that pattern. Use `version = 32` (assuming master is on 31; verify with `grep "version = " composeApp/src/commonMain/kotlin/io/nisfeb/talon/data/AppDatabase.kt`).

- [ ] **Step 4: Wire into Android — add MIGRATION_31_32**

```kotlin
// composeApp/src/androidMain/kotlin/io/nisfeb/talon/data/AppDatabase.android.kt
val MIGRATION_31_32 = object : Migration(31, 32) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS install_id " +
            "(key TEXT NOT NULL PRIMARY KEY, uuid TEXT NOT NULL)"
        )
    }
}
```

Register `.addMigrations(MIGRATION_31_32)` on the builder.

- [ ] **Step 5: Wire into Desktop**

`composeApp/src/desktopMain/kotlin/io/nisfeb/talon/data/AppDatabase.desktop.kt` uses `fallbackToDestructiveMigration()`. Just register the new accessor; no migration script needed.

- [ ] **Step 6: Write the InstallId repo**

```kotlin
// composeApp/src/commonMain/kotlin/io/nisfeb/talon/research/InstallId.kt
package io.nisfeb.talon.research

import io.nisfeb.talon.data.InstallIdDao
import io.nisfeb.talon.data.InstallIdEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Persistent per-installation UUID. First call mints a value
 * and writes it; subsequent calls return the existing one.
 *
 * Thread-safe: a mutex serializes concurrent first-call paths
 * to avoid two callers minting different UUIDs racing. The
 * second arrival sees the freshly written row.
 */
class InstallId(private val dao: InstallIdDao) {
    private val mutex = Mutex()
    @Volatile private var cached: String? = null

    suspend fun get(): String {
        cached?.let { return it }
        return mutex.withLock {
            cached?.let { return@withLock it }
            val existing = dao.getSelf()
            val uuid = existing?.uuid ?: newUuid().also { dao.upsert(InstallIdEntity(uuid = it)) }
            cached = uuid
            uuid
        }
    }

    private fun newUuid(): String = java.util.UUID.randomUUID().toString()
}
```

- [ ] **Step 7: Compile**

```bash
./gradlew :composeApp:compileKotlinDesktop
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/data/InstallIdEntity.kt \
        composeApp/src/commonMain/kotlin/io/nisfeb/talon/data/InstallIdDao.kt \
        composeApp/src/commonMain/kotlin/io/nisfeb/talon/research/InstallId.kt \
        composeApp/src/commonMain/kotlin/io/nisfeb/talon/data/AppDatabase.kt \
        composeApp/src/androidMain/kotlin/io/nisfeb/talon/data/AppDatabase.android.kt \
        composeApp/src/desktopMain/kotlin/io/nisfeb/talon/data/AppDatabase.desktop.kt
git commit -m "feat(research): persistent install_id for per-device watermarks

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 2.3: `ProjectRepo` interface + `ObeliskProjectRepo`

**Files:**
- Create: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/research/ProjectRepo.kt`
- Create: `composeApp/src/desktopTest/kotlin/io/nisfeb/talon/research/ProjectRepoTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// composeApp/src/desktopTest/kotlin/io/nisfeb/talon/research/ProjectRepoTest.kt
package io.nisfeb.talon.research

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectRepoTest {

    @Test
    fun `mark inserts a project row`() = runTest {
        val client = FakeObeliskClient()
        val repo = ObeliskProjectRepo(client)

        repo.mark(groupFlag = "~zod/foo", name = "Foo").getOrThrow()
        assertEquals(1, client.scripts.size)
        assertTrue(client.scripts[0].contains("INSERT INTO project"))
        assertTrue(client.scripts[0].contains("'~zod/foo'"))
        assertTrue(client.scripts[0].contains("'Foo'"))
    }

    @Test
    fun `unmark deletes the project row but not its data`() = runTest {
        val client = FakeObeliskClient()
        val repo = ObeliskProjectRepo(client)

        repo.unmark("~zod/foo").getOrThrow()
        assertEquals(1, client.scripts.size)
        val s = client.scripts[0]
        assertTrue(s.contains("DELETE FROM project"))
        assertTrue(!s.contains("DELETE FROM post"), "unmark must not touch post rows")
    }

    @Test
    fun `deleteData purges all per-project rows`() = runTest {
        val client = FakeObeliskClient()
        val repo = ObeliskProjectRepo(client)

        repo.deleteData("~zod/foo").getOrThrow()
        val s = client.scripts.single()
        for (table in listOf("post", "link", "mention", "attachment", "index_state")) {
            assertTrue(s.contains("DELETE FROM $table"), "missing DELETE for $table")
        }
        assertTrue(s.contains("DELETE FROM project"))
    }

    @Test
    fun `observe emits rows from a SELECT * FROM project`() = runTest {
        val client = FakeObeliskClient()
        client.stub(
            "SELECT group_flag, marked_at, name FROM project",
            listOf(
                buildRow(
                    "group_flag" to JsonPrimitive("~zod/a"),
                    "marked_at" to JsonPrimitive(1L),
                    "name" to JsonPrimitive("A"),
                ),
                buildRow(
                    "group_flag" to JsonPrimitive("~zod/b"),
                    "marked_at" to JsonPrimitive(2L),
                    "name" to JsonPrimitive("B"),
                ),
            ),
        )
        val repo = ObeliskProjectRepo(client)
        val list = repo.observeProjects().first()
        assertEquals(2, list.size)
        assertEquals("~zod/a", list[0].groupFlag)
        assertEquals("B", list[1].name)
    }
}
```

- [ ] **Step 2: Run, verify fail**

```bash
./gradlew :composeApp:desktopTest --tests 'io.nisfeb.talon.research.ProjectRepoTest' --console=plain
```

Expected: FAIL on unresolved `ObeliskProjectRepo`, `Project`, etc.

- [ ] **Step 3: Write the impl**

```kotlin
// composeApp/src/commonMain/kotlin/io/nisfeb/talon/research/ProjectRepo.kt
package io.nisfeb.talon.research

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * One marked project. [name] is a snapshot of the group title at
 * mark-time; the user can rename later (post-Phase-1; out of
 * scope here, but the schema column already supports it).
 */
data class Project(
    val groupFlag: String,
    val markedAt: Long,
    val name: String,
)

interface ProjectRepo {
    fun observeProjects(): Flow<List<Project>>

    /** Insert (or replace) the [Project] row. Atomic. */
    suspend fun mark(groupFlag: String, name: String): Result<Unit>

    /**
     * Remove the project row. Indexed data is left in place
     * (per spec: data is precious by default).
     */
    suspend fun unmark(groupFlag: String): Result<Unit>

    /**
     * Destructive purge: drop the project row AND all per-project
     * indexed data. Wrapped in a single atomic %tape2 script.
     */
    suspend fun deleteData(groupFlag: String): Result<Unit>
}

class ObeliskProjectRepo(private val obelisk: ObeliskClient) : ProjectRepo {

    private val refresh = MutableSharedFlow<Unit>(replay = 1).also {
        // Trigger initial emit so observers see the current state
        // without waiting for the first mark/unmark.
        it.tryEmit(Unit)
    }

    override fun observeProjects(): Flow<List<Project>> = flow {
        refresh.collect {
            val rows = obelisk.query(SELECT_PROJECTS).getOrElse { emptyList() }
            emit(rows.map { row ->
                Project(
                    groupFlag = row.string("group_flag"),
                    markedAt = row.long("marked_at"),
                    name = row.string("name"),
                )
            })
        }
    }

    override suspend fun mark(groupFlag: String, name: String): Result<Unit> {
        val script = """
            INSERT INTO project (group_flag, marked_at, name) VALUES
            (${UrQL.escapeString(groupFlag)}, ${System.currentTimeMillis()}, ${UrQL.escapeString(name)});
        """.trimIndent()
        return obelisk.script(script).also { if (it.isSuccess) refresh.tryEmit(Unit) }
    }

    override suspend fun unmark(groupFlag: String): Result<Unit> {
        val script = "DELETE FROM project WHERE group_flag = ${UrQL.escapeString(groupFlag)};"
        return obelisk.script(script).also { if (it.isSuccess) refresh.tryEmit(Unit) }
    }

    override suspend fun deleteData(groupFlag: String): Result<Unit> {
        val gf = UrQL.escapeString(groupFlag)
        val script = """
            DELETE FROM post WHERE group_flag = $gf;
            DELETE FROM link WHERE group_flag = $gf;
            DELETE FROM mention WHERE group_flag = $gf;
            DELETE FROM attachment WHERE group_flag = $gf;
            DELETE FROM index_state WHERE group_flag = $gf;
            DELETE FROM project WHERE group_flag = $gf;
        """.trimIndent()
        return obelisk.script(script).also { if (it.isSuccess) refresh.tryEmit(Unit) }
    }

    companion object {
        const val SELECT_PROJECTS = "SELECT group_flag, marked_at, name FROM project"
    }
}
```

- [ ] **Step 4: Run, verify pass**

```bash
./gradlew :composeApp:desktopTest --tests 'io.nisfeb.talon.research.ProjectRepoTest' --console=plain
```

Expected: 4 passed.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/research/ProjectRepo.kt \
        composeApp/src/desktopTest/kotlin/io/nisfeb/talon/research/ProjectRepoTest.kt
git commit -m "feat(research): ProjectRepo (mark / unmark / observe / deleteData)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 2.4: `WatermarkRepo` — index_state read/write

**Files:**
- Create: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/research/WatermarkRepo.kt`
- Create: `composeApp/src/commonTest/kotlin/io/nisfeb/talon/research/WatermarkRepoTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package io.nisfeb.talon.research

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WatermarkRepoTest {

    @Test
    fun `read returns 0 when no row exists`() = runTest {
        val client = FakeObeliskClient()
        val repo = WatermarkRepo(client)
        val ts = repo.read("install-1", "~zod/foo").getOrThrow()
        assertEquals(0L, ts)
    }

    @Test
    fun `read returns the stored value`() = runTest {
        val client = FakeObeliskClient()
        client.stub(
            "SELECT last_ts FROM index_state WHERE install_id = 'install-1' AND group_flag = '~zod/foo'",
            listOf(buildRow("last_ts" to JsonPrimitive(1234L))),
        )
        val repo = WatermarkRepo(client)
        val ts = repo.read("install-1", "~zod/foo").getOrThrow()
        assertEquals(1234L, ts)
    }

    @Test
    fun `write upserts the row`() = runTest {
        val client = FakeObeliskClient()
        val repo = WatermarkRepo(client)
        repo.write("install-1", "~zod/foo", 5000L).getOrThrow()
        val s = client.scripts.single()
        assertTrue(s.contains("DELETE FROM index_state WHERE install_id = 'install-1' AND group_flag = '~zod/foo'"))
        assertTrue(s.contains("INSERT INTO index_state"))
        assertTrue(s.contains("5000"))
    }
}
```

- [ ] **Step 2: Run, verify fail**

```bash
./gradlew :composeApp:desktopTest --tests 'io.nisfeb.talon.research.WatermarkRepoTest' --console=plain
```

Expected: FAIL on unresolved `WatermarkRepo`.

- [ ] **Step 3: Write the impl**

```kotlin
package io.nisfeb.talon.research

/**
 * Per-(install_id, group_flag) progress watermark stored in
 * obelisk. The indexer reads this on startup of a project's
 * indexing pass to know the smallest ts it still needs to copy
 * forward, and writes the post-batch maximum ts on success.
 *
 * Multi-device safety: each device writes only to its own
 * (install_id, group_flag) row, so two devices running indexers
 * concurrently won't trample each other's watermarks. They will
 * insert overlapping rows into post/link/mention/attachment
 * tables — that's fine, the primary keys make duplicate rows
 * coalesce by obelisk's set semantics.
 */
class WatermarkRepo(private val obelisk: ObeliskClient) {

    suspend fun read(installId: String, groupFlag: String): Result<Long> = runCatching {
        val q = "SELECT last_ts FROM index_state WHERE install_id = ${UrQL.escapeString(installId)} " +
            "AND group_flag = ${UrQL.escapeString(groupFlag)}"
        val rows = obelisk.query(q).getOrThrow()
        rows.firstOrNull()?.long("last_ts") ?: 0L
    }

    /**
     * Upsert: emulate "delete-then-insert" in one atomic script
     * because the schema doesn't model UPSERT and we don't want
     * to depend on obelisk-specific syntax for it.
     */
    suspend fun write(installId: String, groupFlag: String, lastTs: Long): Result<Unit> {
        val script = """
            DELETE FROM index_state WHERE install_id = ${UrQL.escapeString(installId)} AND group_flag = ${UrQL.escapeString(groupFlag)};
            INSERT INTO index_state (install_id, group_flag, last_ts) VALUES (${UrQL.escapeString(installId)}, ${UrQL.escapeString(groupFlag)}, $lastTs);
        """.trimIndent()
        return obelisk.script(script)
    }
}
```

- [ ] **Step 4: Run, verify pass**

```bash
./gradlew :composeApp:desktopTest --tests 'io.nisfeb.talon.research.WatermarkRepoTest' --console=plain
```

Expected: 3 passed.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/research/WatermarkRepo.kt \
        composeApp/src/commonTest/kotlin/io/nisfeb/talon/research/WatermarkRepoTest.kt
git commit -m "feat(research): WatermarkRepo (per-install_id, per-project)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 2.5: `ResearchIndexer` — backfill + live-index for one project

**Files:**
- Create: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/research/IndexProgress.kt`
- Create: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/research/ResearchIndexer.kt`
- Create: `composeApp/src/desktopTest/kotlin/io/nisfeb/talon/research/ResearchIndexerTest.kt`

- [ ] **Step 1: Write the IndexProgress sealed type**

```kotlin
// composeApp/src/commonMain/kotlin/io/nisfeb/talon/research/IndexProgress.kt
package io.nisfeb.talon.research

sealed interface IndexProgress {
    val groupFlag: String

    data class Idle(override val groupFlag: String) : IndexProgress
    data class Backfilling(
        override val groupFlag: String,
        val processed: Int,
        val total: Int,
    ) : IndexProgress
    data class Failed(
        override val groupFlag: String,
        val message: String,
    ) : IndexProgress
}
```

- [ ] **Step 2: Write the failing test**

```kotlin
// composeApp/src/desktopTest/kotlin/io/nisfeb/talon/research/ResearchIndexerTest.kt
package io.nisfeb.talon.research

import io.nisfeb.talon.data.MessageEntity
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResearchIndexerTest {

    private fun fakeMsgs(n: Int, groupFlag: String = "~zod/foo"): List<MessageEntity> =
        (1..n).map { i ->
            MessageEntity(
                whom = "chat/~zod/general",
                id = "~zod/$i.000",
                author = "~zod",
                sentMs = i.toLong(),
                contentJson = """[{"inline":["m$i"]}]""",
                kind = "/chat",
            )
        }

    @Test
    fun `backfill writes all messages above the watermark, batched`() = runTest {
        val client = FakeObeliskClient()
        val msgs = fakeMsgs(750)
        val source = MessageSource { _, after -> msgs.filter { it.sentMs > after } }

        val indexer = ResearchIndexer(
            installId = "install-1",
            obelisk = client,
            watermarks = WatermarkRepo(client),
            messageSource = source,
            batchSize = 500,
        )

        val progress = indexer.indexProject("~zod/foo").toList()
        // First emission should be Backfilling, last should be Idle (or Failed on error).
        assertTrue(progress.first() is IndexProgress.Backfilling)
        assertTrue(progress.last() is IndexProgress.Idle)
        // Two scripts: 500 + 250 rows.
        assertEquals(2, client.scripts.size)
    }

    @Test
    fun `backfill above watermark only`() = runTest {
        val client = FakeObeliskClient()
        client.stub(
            "SELECT last_ts FROM index_state WHERE install_id = 'install-1' AND group_flag = '~zod/foo'",
            listOf(buildRow("last_ts" to kotlinx.serialization.json.JsonPrimitive(500L))),
        )
        val msgs = fakeMsgs(750) // ts 1..750
        val source = MessageSource { _, after -> msgs.filter { it.sentMs > after } }

        val indexer = ResearchIndexer(
            installId = "install-1",
            obelisk = client, watermarks = WatermarkRepo(client),
            messageSource = source, batchSize = 500,
        )
        indexer.indexProject("~zod/foo").toList()
        // Only msgs 501..750 should be written = 250 rows = 1 script.
        assertEquals(1, client.scripts.size)
    }

    @Test
    fun `script failure surfaces as Failed and watermark does NOT advance`() = runTest {
        val client = FakeObeliskClient()
        client.failNextScript = "boom"
        val source = MessageSource { _, _ -> fakeMsgs(10) }
        val indexer = ResearchIndexer(
            installId = "install-1",
            obelisk = client, watermarks = WatermarkRepo(client),
            messageSource = source, batchSize = 500,
        )
        val progress = indexer.indexProject("~zod/foo").toList()
        assertTrue(progress.last() is IndexProgress.Failed)
        // Watermark write would itself be a script; on failure, no extra writes.
        assertEquals(0, client.scripts.size)
    }
}
```

- [ ] **Step 3: Run, verify fail**

```bash
./gradlew :composeApp:desktopTest --tests 'io.nisfeb.talon.research.ResearchIndexerTest' --console=plain
```

Expected: FAIL on unresolved `ResearchIndexer`, `MessageSource`.

- [ ] **Step 4: Write the impl**

```kotlin
// composeApp/src/commonMain/kotlin/io/nisfeb/talon/research/ResearchIndexer.kt
package io.nisfeb.talon.research

import io.nisfeb.talon.data.MessageEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Pulls messages from the local Room cache for a given group_flag,
 * filtered to those above a watermark.
 *
 * The interface is fun-shaped so tests can pass a lambda; the
 * production wiring binds it to MessageDao.
 */
fun interface MessageSource {
    suspend fun read(groupFlag: String, afterTs: Long): List<MessageEntity>
}

/**
 * Single-project indexing engine.
 *
 * Algorithm:
 *   1. Read watermark for (install_id, group_flag).
 *   2. messageSource.read(group_flag, after = watermark).
 *   3. For each batchSize-sized chunk, build the urQL script and
 *      send via [obelisk.script]. On success advance watermark to
 *      the chunk's max ts. On failure, emit Failed and stop.
 *   4. Emit Idle when complete.
 */
class ResearchIndexer(
    private val installId: String,
    private val obelisk: ObeliskClient,
    private val watermarks: WatermarkRepo,
    private val messageSource: MessageSource,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
) {
    fun indexProject(groupFlag: String): Flow<IndexProgress> = flow {
        val watermark = watermarks.read(installId, groupFlag).getOrElse {
            emit(IndexProgress.Failed(groupFlag, it.message ?: "watermark read failed"))
            return@flow
        }
        val messages = messageSource.read(groupFlag, afterTs = watermark)
        if (messages.isEmpty()) {
            emit(IndexProgress.Idle(groupFlag))
            return@flow
        }
        val total = messages.size
        var processed = 0
        for (chunk in messages.chunked(batchSize)) {
            emit(IndexProgress.Backfilling(groupFlag, processed = processed, total = total))
            val bundles = chunk.map { ResearchExtractor.extract(it, groupFlag) }
            val script = IndexBatchBuilder.build(bundles)
            if (script.isEmpty()) {
                processed += chunk.size
                continue
            }
            obelisk.script(script).onFailure {
                emit(IndexProgress.Failed(groupFlag, it.message ?: "obelisk script failed"))
                return@flow
            }
            val maxTs = chunk.maxOf { it.sentMs }
            watermarks.write(installId, groupFlag, maxTs).onFailure {
                emit(IndexProgress.Failed(groupFlag, "watermark write failed: ${it.message}"))
                return@flow
            }
            processed += chunk.size
        }
        emit(IndexProgress.Idle(groupFlag))
    }

    companion object {
        const val DEFAULT_BATCH_SIZE = 500
    }
}
```

- [ ] **Step 5: Run, verify pass**

```bash
./gradlew :composeApp:desktopTest --tests 'io.nisfeb.talon.research.ResearchIndexerTest' --console=plain
```

Expected: 3 passed.

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/research/IndexProgress.kt \
        composeApp/src/commonMain/kotlin/io/nisfeb/talon/research/ResearchIndexer.kt \
        composeApp/src/desktopTest/kotlin/io/nisfeb/talon/research/ResearchIndexerTest.kt
git commit -m "feat(research): ResearchIndexer (batched backfill + watermark)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 2.6: `ResearchCapability` — per-session probe

**Files:**
- Create: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/research/ResearchCapability.kt`
- Create: `composeApp/src/desktopTest/kotlin/io/nisfeb/talon/research/ResearchCapabilityTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package io.nisfeb.talon.research

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResearchCapabilityTest {

    @Test
    fun `available is false when desk probe returns false`() = runTest {
        val cap = ResearchCapability(
            obelisk = FakeObeliskClient(),
            deskPresent = { false },
            migrator = ResearchSchemaMigrator(FakeObeliskClient()),
        )
        cap.probe()
        assertFalse(cap.available.first())
    }

    @Test
    fun `available is true after a successful probe + migrator`() = runTest {
        val client = FakeObeliskClient()
        client.stubVersion(ResearchSchema.CURRENT_VERSION)
        val cap = ResearchCapability(
            obelisk = client,
            deskPresent = { true },
            migrator = ResearchSchemaMigrator(client),
        )
        cap.probe()
        assertTrue(cap.available.first())
        assertEquals(ResearchSchema.CURRENT_VERSION, cap.schemaVersion.first())
    }

    @Test
    fun `newer schema on the ship blocks the capability`() = runTest {
        val client = FakeObeliskClient()
        client.stubVersion(ResearchSchema.CURRENT_VERSION + 1)
        val cap = ResearchCapability(
            obelisk = client,
            deskPresent = { true },
            migrator = ResearchSchemaMigrator(client),
        )
        cap.probe()
        assertFalse(cap.available.first())
    }
}
```

- [ ] **Step 2: Run, verify fail**

```bash
./gradlew :composeApp:desktopTest --tests 'io.nisfeb.talon.research.ResearchCapabilityTest' --console=plain
```

Expected: FAIL on unresolved `ResearchCapability`.

- [ ] **Step 3: Write the impl**

```kotlin
package io.nisfeb.talon.research

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Per-session capability for the research feature. Holds
 * StateFlows that the rail tab and the IndexerOrchestrator
 * subscribe to; the underlying probe runs on session connect
 * and any time the orchestrator detects the underlying desk
 * has come / gone.
 *
 * `deskPresent` is a suspendable thunk so the implementation
 * can scry the ship and return present/absent per-session
 * without coupling this class to the scry mechanics.
 */
class ResearchCapability(
    private val obelisk: ObeliskClient,
    private val deskPresent: suspend () -> Boolean,
    private val migrator: ResearchSchemaMigrator,
) {
    private val _available = MutableStateFlow(false)
    val available: StateFlow<Boolean> = _available.asStateFlow()

    private val _schemaVersion = MutableStateFlow<Int?>(null)
    val schemaVersion: StateFlow<Int?> = _schemaVersion.asStateFlow()

    suspend fun probe(): Result<Boolean> {
        if (!deskPresent()) {
            _available.value = false
            _schemaVersion.value = null
            return Result.success(false)
        }
        val ensured = migrator.ensureCurrent()
        return ensured.fold(
            onSuccess = { v ->
                _schemaVersion.value = v
                _available.value = true
                Result.success(true)
            },
            onFailure = {
                _schemaVersion.value = null
                _available.value = false
                Result.success(false) // expected outcome paths; not an error to surface
            },
        )
    }
}
```

- [ ] **Step 4: Run, verify pass**

```bash
./gradlew :composeApp:desktopTest --tests 'io.nisfeb.talon.research.ResearchCapabilityTest' --console=plain
```

Expected: 3 passed.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/research/ResearchCapability.kt \
        composeApp/src/desktopTest/kotlin/io/nisfeb/talon/research/ResearchCapabilityTest.kt
git commit -m "feat(research): ResearchCapability (per-session probe + flags)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 2.7: `ResearchProgressRepo` — in-memory aggregate

**Files:**
- Create: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/research/ResearchProgressRepo.kt`

- [ ] **Step 1: Write the file (no test — pure delegation)**

```kotlin
package io.nisfeb.talon.research

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Session-scoped aggregate of [IndexProgress] across all
 * active indexer jobs, keyed by group_flag. The Research
 * UI subscribes to [byGroup] to render per-card status.
 *
 * State updates are write-replace per group_flag — the
 * orchestrator is responsible for setting Idle when an
 * indexer's flow completes successfully.
 */
class ResearchProgressRepo {
    private val _byGroup = MutableStateFlow<Map<String, IndexProgress>>(emptyMap())
    val byGroup: StateFlow<Map<String, IndexProgress>> = _byGroup.asStateFlow()

    fun update(progress: IndexProgress) {
        _byGroup.value = _byGroup.value + (progress.groupFlag to progress)
    }

    fun forget(groupFlag: String) {
        _byGroup.value = _byGroup.value - groupFlag
    }
}
```

- [ ] **Step 2: Compile**

```bash
./gradlew :composeApp:compileKotlinDesktop
```

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/research/ResearchProgressRepo.kt
git commit -m "feat(research): in-memory ResearchProgressRepo aggregator

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 2.8: `IndexerOrchestrator` — lifecycle manager

**Files:**
- Create: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/research/IndexerOrchestrator.kt`
- Create: `composeApp/src/desktopTest/kotlin/io/nisfeb/talon/research/IndexerOrchestratorTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package io.nisfeb.talon.research

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

class IndexerOrchestratorTest {

    @Test
    fun `marking a project spawns one indexer; unmarking cancels it`() = runTest {
        val projects = MutableStateFlow<List<Project>>(emptyList())
        val started = mutableListOf<String>()
        val cancelled = mutableListOf<String>()

        val orchestrator = IndexerOrchestrator(
            scope = TestScope(this.coroutineContext),
            projects = projects.asStateFlow(),
            startIndexer = { gf -> started += gf; Job() },
            onCancel = { gf -> cancelled += gf },
        )
        orchestrator.start()

        projects.value = listOf(Project("~zod/a", 1L, "A"))
        runCurrent()
        assertEquals(listOf("~zod/a"), started)

        projects.value = emptyList()
        runCurrent()
        assertEquals(listOf("~zod/a"), cancelled)
    }
}
```

- [ ] **Step 2: Run, verify fail**

```bash
./gradlew :composeApp:desktopTest --tests 'io.nisfeb.talon.research.IndexerOrchestratorTest' --console=plain
```

Expected: FAIL on unresolved `IndexerOrchestrator`.

- [ ] **Step 3: Write the impl**

```kotlin
package io.nisfeb.talon.research

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Watches the set of marked projects and ensures exactly one
 * [ResearchIndexer] job is running per marked group_flag. On
 * mark removal, cancels the corresponding job. On the orchestrator
 * being asked to stop, cancels everything.
 *
 * Indexers are spawned lazily via [startIndexer] — the caller
 * provides the lambda so [IndexerOrchestrator] doesn't need to
 * know how to construct them. Same for cancellation: callers
 * receive [onCancel] to do any cleanup beyond Job.cancel.
 */
class IndexerOrchestrator(
    private val scope: CoroutineScope,
    private val projects: StateFlow<List<Project>>,
    private val startIndexer: (groupFlag: String) -> Job,
    private val onCancel: (groupFlag: String) -> Unit = {},
) {
    private val jobs = mutableMapOf<String, Job>()

    fun start() {
        scope.launch {
            projects
                .map { it.map(Project::groupFlag).toSet() }
                .distinctUntilChanged()
                .collect { wanted ->
                    val current = jobs.keys.toSet()
                    for (gf in wanted - current) {
                        jobs[gf] = startIndexer(gf)
                    }
                    for (gf in current - wanted) {
                        jobs.remove(gf)?.cancel()
                        onCancel(gf)
                    }
                }
        }
    }

    fun stop() {
        for ((_, job) in jobs) job.cancel()
        jobs.clear()
    }
}
```

- [ ] **Step 4: Run, verify pass**

```bash
./gradlew :composeApp:desktopTest --tests 'io.nisfeb.talon.research.IndexerOrchestratorTest' --console=plain
```

Expected: 1 passed.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/research/IndexerOrchestrator.kt \
        composeApp/src/desktopTest/kotlin/io/nisfeb/talon/research/IndexerOrchestratorTest.kt
git commit -m "feat(research): IndexerOrchestrator lifecycle manager

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Phase C — Real obelisk integration

### Task 3.1: `TlonObeliskClient` — real impl using pokeRaw + scry

**Files:**
- Create: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/research/TlonObeliskClient.kt`
- Modify: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/urbit/TlonChatRepo.kt` (add `scryArch` if not present)

This task is the bridge between Talon and the actual `%obelisk` agent. We don't have a fakezod handy in CI, so testing is manual against a real ship. Tests for this file are minimal and exercise message construction, not the round-trip.

- [ ] **Step 1: Confirm `scryArch` exists in TlonChatRepo, or add it**

```bash
rg "fun scryArch|scryArch\(" composeApp/src/commonMain/kotlin/io/nisfeb/talon/urbit/TlonChatRepo.kt
```

If it's absent, add a thin wrapper that does `.^(arch %cy /=$desk=/)` via the existing scry plumbing. (The exact existing scry helper in `TlonChatRepo.kt` will be similar — pattern-match on it.)

- [ ] **Step 2: Write the impl**

```kotlin
// composeApp/src/commonMain/kotlin/io/nisfeb/talon/research/TlonObeliskClient.kt
package io.nisfeb.talon.research

import io.nisfeb.talon.urbit.TlonChatRepo
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Real [ObeliskClient]. Uses TlonChatRepo's existing pokeRaw
 * channel to send `%obelisk` actions and (eventually) read
 * responses through the same channel's subscriber.
 *
 * Notes for the implementer:
 *   - The exact mark/payload shapes need to be cross-checked
 *     against the live obelisk version when wiring this up. The
 *     spec's open-questions list flagged this. Two known-good
 *     patterns from the obelisk README:
 *       %tape2 — multi-statement script execution
 *       %commands — typed command API (sur/ast/hoon)
 *   - Query results don't currently come back through %obelisk
 *     scries (per the README, "There are no scries"). The
 *     response is delivered via the channel's subscription
 *     model. The recommended approach is to subscribe to a
 *     deterministic response path (e.g.
 *     /response/<request-id>) when issuing the poke and resolve
 *     the suspending call on the matching event.
 *   - Until that wiring lands, implementations may temporarily
 *     return [Result.failure] from query() — Talon callers will
 *     simply behave as if the corpus is empty until then. The
 *     indexer's [script] path (writes only) will work first.
 */
class TlonObeliskClient(private val repo: TlonChatRepo) : ObeliskClient {

    override suspend fun query(urql: String): Result<List<ObeliskRow>> = runCatching {
        // Subscribe to the response path, send the poke, await one
        // event, return its rows. Implementation detail bound to
        // %obelisk's actual reply mechanics — see the spec's
        // "Open questions for the plan stage" entry on result format.
        // For Phase 1 we ship with this stubbed and rely on the
        // indexer's script path while the read mechanism is finalized.
        error("TlonObeliskClient.query: response wiring not yet implemented; see TlonObeliskClient.kt")
    }

    override suspend fun script(urql: String): Result<Unit> = runCatching {
        val payload: JsonObject = buildJsonObject {
            put("tape2", JsonPrimitive(urql))
        }
        repo.pokeRaw(app = "obelisk", mark = "obelisk-action", payload = payload)
    }
}
```

- [ ] **Step 3: Note the followup explicitly**

This task ships the write path, which is enough for the indexer to function end-to-end against a real ship. The read path (queries) is needed by `ProjectRepo.observeProjects()`, `WatermarkRepo.read`, and the Research screen's stats. **All of these have `FakeObeliskClient`-backed tests — they pass — but on a real ship they'll return empty / fail until the response wiring lands.**

Add a follow-up issue:

```bash
gh issue create -t "TlonObeliskClient.query: implement response-channel wiring" \
  -b "Phase 1 ships with TlonObeliskClient.script working but query() unimplemented. Need to wire up the obelisk-response channel subscription so query() can return rows. See TlonObeliskClient.kt header comment for context."
```

- [ ] **Step 4: Compile**

```bash
./gradlew :composeApp:compileKotlinDesktop
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/research/TlonObeliskClient.kt
git commit -m "feat(research): TlonObeliskClient (write path; read TBD)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Phase D — UI surfaces

### Task 4.1: Add `Research` to `RailTab` + `RailItem`

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/RailTab.kt`
- Modify: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/RailItem.kt`

- [ ] **Step 1: Update `RailTab.kt`**

```kotlin
// before:
enum class RailTab { Chats, Statuses, Bookmarks, Activity }
// after:
enum class RailTab { Chats, Research, Statuses, Bookmarks, Activity }
```

- [ ] **Step 2: Update `RailItem.kt`**

```kotlin
// Add Research between Chats and Statuses:
enum class RailItem(val isPaneTab: Boolean) {
    Chats(true),
    Research(true),  // NEW: capability-gated; rendering filter omits it when obelisk is unavailable on the active session
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
    fun toRailTab(): RailTab? = if (isPaneTab) RailTab.valueOf(name) else null
}
```

- [ ] **Step 3: Compile**

```bash
./gradlew :composeApp:compileKotlinDesktop
```

Expected: SUCCESS. Existing `RailItemTest` will fail unless updated — new entries should be reflected. Run the test next.

- [ ] **Step 4: Run RailItem-related tests**

```bash
./gradlew :composeApp:desktopTest --tests 'io.nisfeb.talon.ui.RailItemTest' --console=plain
```

If a snapshot/round-trip test on `RailItem.entries` exists, it will likely need its expected list updated to include `Research`.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/RailTab.kt \
        composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/RailItem.kt
git commit -m "feat(research): add Research to RailTab + RailItem

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 4.2: `ResearchProjectCard` composable

**Files:**
- Create: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/ResearchProjectCard.kt`

- [ ] **Step 1: Write the file**

```kotlin
package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.research.IndexProgress
import io.nisfeb.talon.research.Project

/**
 * Project card for the Research rail. Renders a stats-rich row
 * when the indexer is idle, and a progress bar when it's
 * backfilling. Click → open the underlying group.
 *
 * [stats] is computed on the consumer side from obelisk
 * aggregates so this composable stays presentation-only.
 */
data class ProjectStats(
    val members: Int,
    val lastActivityMs: Long,
    val messages: Int,
    val links: Int,
    val diaryEntries: Int,
    val topContributors: List<String>,
)

@Composable
fun ResearchProjectCard(
    project: Project,
    stats: ProjectStats,
    progress: IndexProgress?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(8.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = project.name, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            Text(
                text = "${project.groupFlag} · ${stats.members} members",
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            )
            when (progress) {
                is IndexProgress.Backfilling -> {
                    val frac = if (progress.total == 0) 0f else progress.processed.toFloat() / progress.total
                    LinearProgressIndicator(
                        progress = { frac },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    Text(
                        text = "Indexing ${progress.processed} / ${progress.total}…",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    )
                }
                is IndexProgress.Failed -> {
                    Text(
                        text = "Index error: ${progress.message}",
                        color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                    )
                }
                else -> {
                    Text(
                        text = "${stats.messages} messages · ${stats.links} links · ${stats.diaryEntries} entries",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    )
                    if (stats.topContributors.isNotEmpty()) {
                        Text(
                            text = "Top contributors: ${stats.topContributors.joinToString(", ")}",
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 2: Compile**

```bash
./gradlew :composeApp:compileKotlinDesktop
```

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/ResearchProjectCard.kt
git commit -m "feat(research): ResearchProjectCard composable (stats + progress)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 4.3: `ResearchScreen` composable

**Files:**
- Create: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/ResearchScreen.kt`

- [ ] **Step 1: Write the file**

```kotlin
package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.research.IndexProgress
import io.nisfeb.talon.research.Project
import io.nisfeb.talon.research.ProjectRepo
import io.nisfeb.talon.research.ResearchProgressRepo
import kotlinx.coroutines.flow.StateFlow

/**
 * Top-level Research rail screen. Lists all marked projects, with
 * each card showing live indexer state from [ResearchProgressRepo].
 *
 * [statsFor] is a side-effecting suspend lambda the consumer
 * provides — it reads obelisk aggregates for one project. The
 * screen calls it lazily per visible card and caches the result.
 *
 * Empty state suggests "Mark a group as research" via a button
 * that the consumer wires into the group picker.
 */
@Composable
fun ResearchScreen(
    projectRepo: ProjectRepo,
    progressRepo: ResearchProgressRepo,
    statsByGroup: StateFlow<Map<String, ProjectStats>>,
    onProjectClick: (Project) -> Unit,
    onCreateNew: () -> Unit,
    onPickToMark: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val projects by remember(projectRepo) { projectRepo.observeProjects() }
        .collectAsState(initial = emptyList())
    val progress by progressRepo.byGroup.collectAsState()
    val stats by statsByGroup.collectAsState()

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = "Research",
            style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
        )
        if (projects.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Mark a group as a research project to start indexing it.",
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = onPickToMark, modifier = Modifier.padding(top = 12.dp)) {
                        Text("Mark a group")
                    }
                    Button(onClick = onCreateNew, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Create new research project")
                    }
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(projects, key = { it.groupFlag }) { project ->
                    ResearchProjectCard(
                        project = project,
                        stats = stats[project.groupFlag] ?: ProjectStats(0, 0, 0, 0, 0, emptyList()),
                        progress = progress[project.groupFlag],
                        onClick = { onProjectClick(project) },
                    )
                }
            }
        }
    }
}

// Imports for `remember` are in androidx.compose.runtime; included via
// the project's standard import block. If the compiler complains about
// unresolved `remember`, add: import androidx.compose.runtime.remember
private inline fun <T> remember(key: Any?, calculation: () -> T): T =
    androidx.compose.runtime.remember(key, calculation)
```

- [ ] **Step 2: Compile**

```bash
./gradlew :composeApp:compileKotlinDesktop
```

Fix any unresolved-reference issues that pop up — the `remember` shim above is precisely to avoid an import shadow problem; if your codebase already imports `remember` cleanly (it does in other screens — see DmListScreen for a reference), you may delete the shim.

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/ResearchScreen.kt
git commit -m "feat(research): ResearchScreen rail surface

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 4.4: `MarkAsResearchAction` — group menu entry

**Files:**
- Create: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/MarkAsResearchAction.kt`
- Modify: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/GroupHomeScreen.kt`

- [ ] **Step 1: Write the action component**

```kotlin
// composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/MarkAsResearchAction.kt
package io.nisfeb.talon.ui

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.nisfeb.talon.research.ProjectRepo
import kotlinx.coroutines.flow.first

/**
 * Group menu entry that toggles "Mark as research project".
 * Renders nothing when [enabled] is false (capability gating).
 *
 * Behavior:
 *   - On flip-on: calls [projectRepo.mark]; on success the
 *     IndexerOrchestrator picks up the new mark and starts
 *     indexing.
 *   - On flip-off: calls [projectRepo.unmark]. Indexed data
 *     stays. Surfaces the destructive "Delete project data"
 *     entry separately (not handled here — see the spec).
 */
@Composable
fun MarkAsResearchMenuItem(
    enabled: Boolean,
    groupFlag: String,
    groupName: String,
    projectRepo: ProjectRepo,
    onToggled: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!enabled) return
    var marked by remember { mutableStateOf(false) }
    LaunchedEffect(groupFlag) {
        marked = projectRepo.observeProjects().first().any { it.groupFlag == groupFlag }
    }
    DropdownMenuItem(
        text = { Text(if (marked) "Unmark research project" else "Mark as research project") },
        trailingIcon = { Switch(checked = marked, onCheckedChange = null) },
        onClick = {
            // Coroutine launch happens at the call site; we expose
            // the change via [onToggled] so the host owns scope.
            onToggled(!marked)
            onDismiss()
        },
    )
}
```

- [ ] **Step 2: Wire it into `GroupHomeScreen.kt`**

Find the existing group dropdown menu and add the new entry. Pass:

- `enabled = researchCapability.available.collectAsState().value`
- `groupFlag = currentGroup.flag`
- `groupName = currentGroup.title`
- `projectRepo = appDeps.projectRepo`
- `onToggled = { newState -> scope.launch { if (newState) projectRepo.mark(...) else projectRepo.unmark(...) } }`
- `onDismiss = { menuExpanded = false }`

The exact call-site shape depends on `GroupHomeScreen.kt`'s existing menu — match its style.

- [ ] **Step 3: Compile**

```bash
./gradlew :composeApp:compileKotlinDesktop
```

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/MarkAsResearchAction.kt \
        composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/GroupHomeScreen.kt
git commit -m "feat(research): mark-as-research menu entry on GroupHomeScreen

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 4.5: `/urql` slash command

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/SlashCommands.kt`
- Create: `composeApp/src/commonTest/kotlin/io/nisfeb/talon/ui/SlashUrqlTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package io.nisfeb.talon.ui

import io.nisfeb.talon.research.FakeObeliskClient
import io.nisfeb.talon.research.NoopObeliskClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SlashUrqlTest {

    @Test
    fun `urql is rejected when power features are off`() = runTest {
        val client = FakeObeliskClient()
        val r = runUrql(
            args = listOf("SELECT", "*", "FROM", "project"),
            obelisk = client,
            powerFeaturesEnabled = false,
            obeliskAvailable = true,
        )
        assertTrue(r is CommandResult.Error)
    }

    @Test
    fun `urql is rejected when obelisk is unavailable`() = runTest {
        val r = runUrql(
            args = listOf("SELECT", "1"),
            obelisk = NoopObeliskClient,
            powerFeaturesEnabled = true,
            obeliskAvailable = false,
        )
        assertTrue(r is CommandResult.Error)
    }

    @Test
    fun `urql rejects non-SELECT statements`() = runTest {
        val client = FakeObeliskClient()
        val r = runUrql(
            args = listOf("DROP", "TABLE", "post"),
            obelisk = client,
            powerFeaturesEnabled = true,
            obeliskAvailable = true,
        )
        assertTrue(r is CommandResult.Error)
        assertEquals(0, client.scripts.size, "must not have hit obelisk")
    }
}
```

- [ ] **Step 2: Run, verify fail**

```bash
./gradlew :composeApp:desktopTest --tests 'io.nisfeb.talon.ui.SlashUrqlTest' --console=plain
```

Expected: FAIL on unresolved `runUrql`.

- [ ] **Step 3: Update `SlashCommands.kt`**

Add to `SLASH_COMMANDS`:

```kotlin
SlashCommandSpec(
    name = "urql",
    synopsis = "/urql <SELECT statement>",
    description = "Power user: hand-run a urQL SELECT against your obelisk research database. Read-only.",
),
```

Add to `runCommand` dispatch:

```kotlin
"urql" -> runUrql(
    args = parsed.args,
    obelisk = ctx.obelisk,
    powerFeaturesEnabled = powerFeaturesEnabled,
    obeliskAvailable = ctx.obeliskAvailable,
)
```

Where `ctx` is a small bundle the caller passes through (since `runCommand` already takes a few extra params, add `obelisk: ObeliskClient = NoopObeliskClient` and `obeliskAvailable: Boolean = false` directly to `runCommand`'s signature, defaulting to noop/false so existing callers don't break).

Add the dispatcher:

```kotlin
suspend fun runUrql(
    args: List<String>,
    obelisk: io.nisfeb.talon.research.ObeliskClient,
    powerFeaturesEnabled: Boolean,
    obeliskAvailable: Boolean,
): CommandResult {
    if (!powerFeaturesEnabled) {
        return CommandResult.Error(
            "/urql is off — enable Settings → Power features.",
        )
    }
    if (!obeliskAvailable) {
        return CommandResult.Error(
            "/urql needs %obelisk on this ship — install it from your tile dock.",
        )
    }
    val statement = args.joinToString(" ").trim()
    if (statement.isEmpty()) {
        return CommandResult.Error("/urql usage: /urql <SELECT statement>")
    }
    if (!statement.lowercase().startsWith("select")) {
        return CommandResult.Error("/urql: only SELECT statements are accepted (read-only).")
    }
    return obelisk.query(statement).fold(
        onSuccess = { rows ->
            val body = formatRowsAsTable(rows, max = 50)
            CommandResult.Send(body)
        },
        onFailure = { err ->
            CommandResult.Error("/urql: ${err.message ?: "obelisk failure"}")
        },
    )
}

private fun formatRowsAsTable(
    rows: List<io.nisfeb.talon.research.ObeliskRow>,
    max: Int,
): String {
    if (rows.isEmpty()) return "_no rows_"
    val truncated = rows.size > max
    val shown = rows.take(max)
    val lines = shown.joinToString("\n") { it.toString() }
    return if (truncated) "$lines\n_… ${rows.size - max} more rows_" else lines
}
```

The `ObeliskClient`-typed param means `SlashCommands.kt` now imports from `io.nisfeb.talon.research`. That's intentional: slash dispatch is the boundary where research surfaces meet command parsing.

- [ ] **Step 4: Run, verify pass**

```bash
./gradlew :composeApp:desktopTest --tests 'io.nisfeb.talon.ui.SlashUrqlTest' --console=plain
```

Expected: 3 passed.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/SlashCommands.kt \
        composeApp/src/commonTest/kotlin/io/nisfeb/talon/ui/SlashUrqlTest.kt
git commit -m "feat(research): /urql read-only slash command

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Phase E — Wire-up + smoke

### Task 5.1: Wire research stack into `App.kt` session lifecycle

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/compose/App.kt`
- Modify: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/DesktopShell.kt`

This is the integration point. Each session gets:

1. An `ObeliskClient` (real `TlonObeliskClient` when capability says yes; `NoopObeliskClient` otherwise).
2. A `ResearchCapability` whose `probe()` runs once on session connect and again on `forceReconnect`.
3. A `ProjectRepo` and `WatermarkRepo` backed by the same client.
4. A `ResearchIndexer` per project, spawned by `IndexerOrchestrator`.
5. A `ResearchProgressRepo` to aggregate per-project status.

- [ ] **Step 1: Construct the stack at session connect**

Pseudocode for the wire-up. The exact place is wherever `App.kt` currently assembles `appDeps` for the session — search for `TlonChatRepo` construction.

```kotlin
// after a successful session connect:
val obeliskClient: ObeliskClient = TlonObeliskClient(repo)
val migrator = ResearchSchemaMigrator(obeliskClient)
val deskPresent: suspend () -> Boolean = {
    repo.scryArch("obelisk").isSuccess
}
val capability = ResearchCapability(obeliskClient, deskPresent, migrator)
scope.launch { capability.probe() }

val projectRepo = ObeliskProjectRepo(obeliskClient)
val watermarkRepo = WatermarkRepo(obeliskClient)
val installId = InstallId(db.installIds())
val progressRepo = ResearchProgressRepo()
val orchestrator = IndexerOrchestrator(
    scope = scope,
    projects = projectRepo.observeProjects()
        .stateIn(scope, SharingStarted.Eagerly, emptyList()),
    startIndexer = { gf ->
        scope.launch {
            val indexer = ResearchIndexer(
                installId = installId.get(),
                obelisk = obeliskClient,
                watermarks = watermarkRepo,
                messageSource = MessageSource { groupFlag, after ->
                    // Talon's MessageDao surface — find the right query and
                    // adapt; the indexer wants ts-ascending rows above 'after'.
                    db.messages().messagesForGroupAfter(groupFlag, after)
                },
            )
            indexer.indexProject(gf).collect(progressRepo::update)
        }
    },
    onCancel = progressRepo::forget,
)
orchestrator.start()
```

- [ ] **Step 2: Render `ResearchScreen` from `DesktopShell.kt`**

The shell already routes `RailTab` values to screens. Add a case:

```kotlin
RailTab.Research -> {
    if (capability.available.collectAsState().value) {
        ResearchScreen(
            projectRepo = projectRepo,
            progressRepo = progressRepo,
            statsByGroup = statsByGroup, // computed from a small aggregator that reads obelisk on demand; see ResearchScreen
            onProjectClick = { p -> navigateTo(GroupRoute(p.groupFlag)) },
            onCreateNew = { /* open existing group-create flow */ },
            onPickToMark = { /* open a group picker that lets the user mark an existing group */ },
        )
    }
}
```

If `RailItem.Research` is selected but capability flips to false, the rail filter should already hide it; defensive `if` here covers the in-flight case where the flag flips after click but before render.

- [ ] **Step 3: Filter the rail by capability**

Wherever the rail items are filtered today (likely in `DesktopShell.kt` or `App.kt`), add: omit `RailItem.Research` when `capability.available` is false.

- [ ] **Step 4: Compile end-to-end**

```bash
./gradlew :composeApp:compileKotlinDesktop
./gradlew :composeApp:compileKotlinAndroid
```

Expected: BUILD SUCCESSFUL on both. Fix any unresolved imports.

- [ ] **Step 5: Run the full test suite**

```bash
./gradlew :composeApp:desktopTest --console=plain
```

Expected: all tests pass, including the new ones from Phases A / B and the existing Talon regression suite.

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/compose/App.kt \
        composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/DesktopShell.kt
git commit -m "feat(research): wire research stack into session lifecycle

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 5.2: Manual smoke against fakezod (or moon)

**Files:** none

This is the gate that proves the wiring actually works against a real ship. Run on a fakezod with `%obelisk` installed.

- [ ] **Step 1: Stand up a fakezod with obelisk**

```bash
# In your urbit dev environment:
./urbit -F zod
# in dojo:
|install ~dister-nomryg-nilref %obelisk
```

- [ ] **Step 2: Build a desktop AppImage and connect to fakezod**

```bash
./scripts/build-appimage.sh
./dist/Talon-x86_64.AppImage
```

Connect to `http://localhost:8080`.

- [ ] **Step 3: Verify the Research rail tab appears**

Open the rail. Confirm the Research entry is between Chats and Statuses. If absent, capability detection failed — open Settings → "Re-run obelisk capability probe" and check logs.

- [ ] **Step 4: Mark a group as research**

In any group's overflow menu, toggle "Mark as research project". A project card should appear under Research with progress indicators.

- [ ] **Step 5: Verify rows actually landed in obelisk**

In the dojo (or via the %hawk UI):

```
:obelisk &obelisk-action [%tape2 %default 'SELECT count(*) FROM post']
```

Expected: a non-zero count matching the marked group's local Room cache.

- [ ] **Step 6: Try `/urql` from a chat composer**

In any chat (with Power Features enabled in Settings), type:

```
/urql SELECT count(*) FROM project
```

Expected: a result row with `1`. If `runUrql.query` is still wired to the unimplemented `TlonObeliskClient.query`, this will surface an error — that's a known Phase 1 followup (see Task 3.1).

- [ ] **Step 7: Stop the indexer**

`|uninstall %obelisk` in dojo. Within ~10 seconds the Research rail tab should disappear and a Logcat / desktop log line should record capability-loss.

- [ ] **Step 8: Reinstall and reconnect**

`|install ~dister-nomryg-nilref %obelisk` again. Probe should fire on the next reconnect or after Settings → Re-run probe. Indexed data persists.

- [ ] **Step 9: If smoke passes, write a brief note**

Append to `RELEASE.md` under a new "Phase 1 — Research foundation smoke checklist" heading, listing the steps above. This becomes the manual gate for the Phase 3 release later.

---

## Self-review checklist

After all tasks land:

- [ ] All commits compile and `./gradlew :composeApp:desktopTest` is green.
- [ ] No `actual val isResearchSupported` exists. Capability is runtime-per-session, not compile-time-per-platform — per the spec and CLAUDE.md.
- [ ] The Research rail tab is filtered out when `capability.available` is false. Confirmed by manual switch-to-no-obelisk-ship.
- [ ] `/urql` is gated by **both** `powerFeaturesEnabled` and `obeliskAvailable`. Both errors are user-facing.
- [ ] `ProjectRepo.deleteData` is the only path that destroys indexed data. Toggling unmark leaves data intact.
- [ ] `IndexerOrchestrator` cancels jobs on unmark, and on full app teardown.
- [ ] No mutations are reachable from `/urql`. Tested by `SlashUrqlTest::urql rejects non-SELECT statements`.
- [ ] All new tests in `commonTest` are `class … { @Test fun … }` shapes (no JUnit-only deps); `desktopTest` may use JUnit-only.
- [ ] No new `expect val` lines in `Capabilities.kt`. The cross-platform discipline in CLAUDE.md is preserved.
- [ ] Spec items covered: capability detection ✅; obelisk schema v1 ✅; backfill via batched %tape2 ✅; multi-device idempotent indexing ✅; mark/unmark/delete-data ✅; orphan-group policy (no automatic action on group-leave) ✅; Research rail tab + projects-overview screen ✅; `/urql` read-only ✅; install_id local persistence ✅. Gaps to track as follow-ups: real-obelisk query path in `TlonObeliskClient`, project-stats aggregator wiring on the Research screen.

## Phase 1 → Phase 2 handoff

Phase 1 lands the substrate. Phase 2's brainstorming should happen with hands-on knowledge of:

- Real obelisk throughput numbers (how long a 50k-message backfill actually takes on a real ship).
- Whether `INSERT` on duplicate primary key behaves as set-semantics-no-op or as an error (informs how aggressive the indexer can be on retries).
- How obelisk responses surface for query() — informs whether Phase 2's "AI summary chip" data path can read directly or needs a cache layer.
- Whether the `%tape2` script size limit forces smaller batches.

Capture these in a follow-up brainstorming pass before Phase 2's writing-plans run.
