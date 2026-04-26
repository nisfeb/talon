# Watchwords Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add user-defined text-term watchwords to Talon — alerting on inbound messages that match, with a dedicated feed for hit history, per-chat exclusions, and opt-in cross-device sync.

**Architecture:** Approach A — UI-listener integration. Watchword evaluation hooks into the existing `app.repo.messageListener` callback in `TalonApp.kt`, sharing the lifecycle and dispatcher of the existing notification path. State lives in a new `Watchwords` facade (mirroring `AiSettings`) backed by three new Room tables and synced to the user's ship's `%settings` agent via two new buckets gated by a single user-toggleable `syncEnabled` flag. Backfill on term-add runs as a one-shot off-thread coroutine. Spec: `docs/superpowers/specs/2026-04-26-watchwords-design.md`.

**Tech Stack:** Kotlin · Room (DB + DAO) · Jetpack Compose · Material 3 · `kotlinx.coroutines` (Flow, StateFlow, withContext, async) · OkHttp via existing `UrbitChannel` · Android `NotificationCompat` · existing `SettingsSync` for `%settings` mirror.

---

## File Structure

**New files:**
- `app/src/main/java/io/nisfeb/talon/data/WatchwordEntity.kt` — three `@Entity`s (term, hit, chat-exclude).
- `app/src/main/java/io/nisfeb/talon/data/WatchwordsDao.kt` — DAO covering all CRUD + stream queries + the prune helper.
- `app/src/main/java/io/nisfeb/talon/ai/Watchwords.kt` — facade, helpers (`matchesWordBoundary`, `sanitizeTerm`), supporting types (`WatchwordChange`, `MatchedTerm`, `MAX_HITS_PER_TERM`).
- `app/src/main/java/io/nisfeb/talon/ui/screens/WatchwordsScreen.kt` — feed screen (LazyColumn of hits + filter chips).
- `app/src/main/java/io/nisfeb/talon/ui/screens/ManageTermsSheet.kt` — `ModalBottomSheet` for add / edit / delete / sync toggle.
- `app/src/test/kotlin/io/nisfeb/talon/ai/WatchwordMatcherTest.kt` — unit tests for `matchesWordBoundary`.
- `app/src/test/kotlin/io/nisfeb/talon/ai/WatchwordSanitizerTest.kt` — unit tests for `sanitizeTerm`.

**Modified files:**
- `app/src/main/java/io/nisfeb/talon/data/AppDatabase.kt` — register entities, expose DAO accessor, add `MIGRATION_25_26`, bump `version` from 25 → 26.
- `app/src/main/java/io/nisfeb/talon/data/MessageDao.kt` — new `candidatesForBackfill` query.
- `app/src/main/java/io/nisfeb/talon/data/NotifyPreferenceDao.kt` — new `mutedWhoms()` helper.
- `app/src/main/java/io/nisfeb/talon/Notifications.kt` — `CHANNEL_WATCHWORDS` registration + new `showWatchwordHit(...)` method.
- `app/src/main/java/io/nisfeb/talon/TalonApplication.kt` — instantiate `Watchwords` per ship, wire `onChange` to `SettingsSync`.
- `app/src/main/java/io/nisfeb/talon/ui/TalonApp.kt` — extend the existing `messageListener` block with watchword evaluation; add `watchwordsOpen` state + route to `WatchwordsScreen`.
- `app/src/main/java/io/nisfeb/talon/ui/screens/DmListScreen.kt` — add "Watchwords" item to home overflow menu.
- `app/src/main/java/io/nisfeb/talon/ui/screens/DmChatScreen.kt` — add "Exclude from watchwords" / "Include in watchwords" item to chat overflow menu.
- `app/src/main/java/io/nisfeb/talon/urbit/SettingsSync.kt` — bucket constants, push helpers, branches in `applyEntry` / `applyBucket` / `removeEntry` / `clearBucketLocally`.
- `app/build.gradle.kts` — bump `versionCode`/`versionName` once at the end.

---

## Task 1: Schema + migration

**Files:**
- Create: `app/src/main/java/io/nisfeb/talon/data/WatchwordEntity.kt`
- Modify: `app/src/main/java/io/nisfeb/talon/data/AppDatabase.kt`

- [ ] **Step 1: Create `WatchwordEntity.kt` with three entities.**

```kotlin
package io.nisfeb.talon.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** One user-defined watchword term. */
@Entity(
    tableName = "watchwords",
    indices = [Index(value = ["term"], unique = true)],
)
data class WatchwordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Raw user-entered text, exact case as typed. */
    val term: String,
    /** When true, a live match fires a system notification. Always logged to feed. */
    val notify: Boolean,
    val createdMs: Long,
)

/**
 * One recorded match against an incoming or backfilled message.
 *
 * PK `(term, whom, postId)` is the dedupe rule: a live match colliding
 * with a backfill match for the same row is a no-op upsert. Two
 * different terms matching the same message produce two rows — that's
 * intentional, since the per-term feed view shows each term's hits
 * independently.
 */
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
    /** Up to ~200 chars of plain text from the matching message. */
    val snippet: String,
)

/** Chat that should never produce watchword hits, regardless of which terms match. */
@Entity(tableName = "watchword_chat_excludes")
data class WatchwordChatExcludeEntity(
    @PrimaryKey val whom: String,
)
```

- [ ] **Step 2: Add `MIGRATION_25_26` to `AppDatabase.kt`.**

Open `app/src/main/java/io/nisfeb/talon/data/AppDatabase.kt`. After the existing `MIGRATION_24_25` block, add:

```kotlin
        private val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS watchwords (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        term TEXT NOT NULL,
                        notify INTEGER NOT NULL,
                        createdMs INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_watchwords_term " +
                        "ON watchwords (term)"
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS watchword_hits (
                        term TEXT NOT NULL,
                        whom TEXT NOT NULL,
                        postId TEXT NOT NULL,
                        sentMs INTEGER NOT NULL,
                        snippet TEXT NOT NULL,
                        PRIMARY KEY (term, whom, postId)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_watchword_hits_term_sentMs " +
                        "ON watchword_hits (term, sentMs)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_watchword_hits_sentMs " +
                        "ON watchword_hits (sentMs)"
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS watchword_chat_excludes (
                        whom TEXT NOT NULL PRIMARY KEY
                    )
                    """.trimIndent()
                )
            }
        }
```

- [ ] **Step 3: Register entities, bump version, register migration, expose DAO accessor.**

Still in `AppDatabase.kt`:

1. In the `entities = [...]` array of `@Database(...)`, add at the end:
   ```kotlin
   WatchwordEntity::class,
   WatchwordHitEntity::class,
   WatchwordChatExcludeEntity::class,
   ```
2. Change `version = 25` to `version = 26`.
3. Add `abstract fun watchwords(): WatchwordsDao` alongside the other DAO accessors.
4. In `addMigrations(...)`, append `MIGRATION_25_26` after `MIGRATION_24_25`.

(`WatchwordsDao` doesn't exist yet — Kotlin will mark it red. We create it in Task 2; the build won't compile until then. That's fine.)

- [ ] **Step 4: Commit.**

```bash
git add app/src/main/java/io/nisfeb/talon/data/WatchwordEntity.kt \
        app/src/main/java/io/nisfeb/talon/data/AppDatabase.kt
git commit -m "watchwords: add entities + migration v25→v26"
```

---

## Task 2: WatchwordsDao

**Files:**
- Create: `app/src/main/java/io/nisfeb/talon/data/WatchwordsDao.kt`

- [ ] **Step 1: Create the DAO file.**

```kotlin
package io.nisfeb.talon.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchwordsDao {

    // ───────── terms ─────────

    @Upsert
    suspend fun upsertTerm(term: WatchwordEntity): Long

    @Query("DELETE FROM watchwords WHERE id = :id")
    suspend fun deleteTermById(id: Long)

    @Query("SELECT * FROM watchwords ORDER BY createdMs ASC")
    fun streamTerms(): Flow<List<WatchwordEntity>>

    @Query("SELECT * FROM watchwords WHERE id = :id LIMIT 1")
    suspend fun getTerm(id: Long): WatchwordEntity?

    @Query("SELECT * FROM watchwords WHERE term = :term LIMIT 1")
    suspend fun getTermByText(term: String): WatchwordEntity?

    @Query("UPDATE watchwords SET notify = :notify WHERE id = :id")
    suspend fun setNotify(id: Long, notify: Boolean)

    // ───────── excludes ─────────

    @Upsert
    suspend fun upsertExclude(exclude: WatchwordChatExcludeEntity)

    @Query("DELETE FROM watchword_chat_excludes WHERE whom = :whom")
    suspend fun deleteExclude(whom: String)

    @Query("SELECT * FROM watchword_chat_excludes")
    fun streamExcludes(): Flow<List<WatchwordChatExcludeEntity>>

    @Query("SELECT whom FROM watchword_chat_excludes")
    suspend fun excludesAsList(): List<String>

    // ───────── hits ─────────

    @Upsert
    suspend fun upsertHit(hit: WatchwordHitEntity)

    @Upsert
    suspend fun upsertHits(hits: List<WatchwordHitEntity>)

    @Query("""
        SELECT * FROM watchword_hits
        ORDER BY sentMs DESC
        LIMIT :limit
    """)
    fun streamAllHits(limit: Int = 500): Flow<List<WatchwordHitEntity>>

    @Query("""
        SELECT * FROM watchword_hits
        WHERE term = :term
        ORDER BY sentMs DESC
        LIMIT :limit
    """)
    fun streamHitsForTerm(term: String, limit: Int = 500): Flow<List<WatchwordHitEntity>>

    @Query("SELECT COUNT(*) FROM watchword_hits WHERE term = :term")
    suspend fun countForTerm(term: String): Int

    /** Per-term hit counts for the filter chips in the feed. */
    @Query("""
        SELECT term, COUNT(*) AS cnt
        FROM watchword_hits
        GROUP BY term
    """)
    fun streamHitCountsByTerm(): Flow<List<TermHitCount>>

    @Query("DELETE FROM watchword_hits WHERE term = :term")
    suspend fun clearHitsForTerm(term: String)

    /**
     * Trim a term's hit rows down to the newest [keep] entries by sentMs.
     * Uses the (term, sentMs) index so the subquery is bounded.
     */
    @Query("""
        DELETE FROM watchword_hits
        WHERE term = :term
          AND sentMs NOT IN (
              SELECT sentMs FROM watchword_hits
              WHERE term = :term
              ORDER BY sentMs DESC
              LIMIT :keep
          )
    """)
    suspend fun pruneToNewest(term: String, keep: Int)
}

data class TermHitCount(val term: String, val cnt: Int)
```

- [ ] **Step 2: Verify the project compiles.**

Run: `cd /home/sneagan/software/personal/talon && JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 ./gradlew :app:compileDebugKotlin 2>&1 | tail -10`

Expected: `BUILD SUCCESSFUL`. KSP regenerates Room schemas so the new DAO + migration are picked up.

- [ ] **Step 3: Commit.**

```bash
git add app/src/main/java/io/nisfeb/talon/data/WatchwordsDao.kt
git commit -m "watchwords: add WatchwordsDao"
```

---

## Task 3: Word-boundary matcher (TDD)

**Files:**
- Create: `app/src/main/java/io/nisfeb/talon/ai/Watchwords.kt`
- Create: `app/src/test/kotlin/io/nisfeb/talon/ai/WatchwordMatcherTest.kt`

We're going to do a strict TDD cycle: scaffold the file with a stub, write the failing test, watch it fail, implement, watch it pass.

- [ ] **Step 1: Create `Watchwords.kt` with the helper stubs and constants only.**

```kotlin
package io.nisfeb.talon.ai

/** Hard cap on hit rows kept per term. See spec §Decisions / §Performance. */
const val MAX_HITS_PER_TERM = 1000

/**
 * Word-boundary substring match. Case-insensitive. Punctuation-tolerant.
 *
 * "Mars" matches "Mars Society" / "(Mars)" / "Mars!" but not "Marshmallow".
 * "C++" matches "I love C++" because both sides are non-letters.
 * Multi-word phrases match the literal substring; internal whitespace is
 * matched as-is (so "Mars Society" does NOT match "Mars\nSociety").
 */
internal fun matchesWordBoundary(haystack: String, needle: String): Boolean {
    return false  // STUB — implemented in step 3 after the test fails
}

/**
 * Stable, lower-cased, alphanumerics-only key for a term — used as the
 * %settings entry key when sync is on. Two terms that produce the same
 * key collide on the ship side; the manage UI surfaces a warning.
 */
internal fun sanitizeTerm(term: String): String =
    term.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
```

- [ ] **Step 2: Create the test file with the full table of cases.**

```kotlin
package io.nisfeb.talon.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchwordMatcherTest {

    @Test fun `matches simple word in middle of sentence`() {
        assertTrue(matchesWordBoundary("Hello Mars Society!", "Mars"))
    }

    @Test fun `does not match prefix collision`() {
        assertFalse(matchesWordBoundary("Marshmallow soft", "Mars"))
        assertFalse(matchesWordBoundary("Marshall plan", "Mars"))
        assertFalse(matchesWordBoundary("Marsupials are cute", "Mars"))
    }

    @Test fun `does not match suffix collision`() {
        assertFalse(matchesWordBoundary("preMars rocket", "Mars"))
    }

    @Test fun `case insensitive`() {
        assertTrue(matchesWordBoundary("hello MARS", "Mars"))
        assertTrue(matchesWordBoundary("Hello mars", "MARS"))
        assertTrue(matchesWordBoundary("MaRs", "mArS"))
    }

    @Test fun `multi-word phrase`() {
        assertTrue(matchesWordBoundary("the Mars Society announced", "Mars Society"))
    }

    @Test fun `phrase across newline does not match`() {
        assertFalse(matchesWordBoundary("Mars\nSociety announced", "Mars Society"))
    }

    @Test fun `punctuation around term`() {
        assertTrue(matchesWordBoundary("(Mars)", "Mars"))
        assertTrue(matchesWordBoundary("Mars!", "Mars"))
        assertTrue(matchesWordBoundary(",Mars,", "Mars"))
        assertTrue(matchesWordBoundary("[Mars]", "Mars"))
    }

    @Test fun `punctuation-heavy term matches when surrounded by non-letters`() {
        assertTrue(matchesWordBoundary("I love C++", "C++"))
        assertTrue(matchesWordBoundary("C++ is fun", "C++"))
        assertTrue(matchesWordBoundary("(C++)", "C++"))
    }

    @Test fun `punctuation-heavy term does not match when followed by letter or digit`() {
        assertFalse(matchesWordBoundary("C++14 standard", "C++"))
        assertFalse(matchesWordBoundary("C++abc", "C++"))
    }

    @Test fun `empty needle returns false`() {
        assertFalse(matchesWordBoundary("anything goes here", ""))
    }

    @Test fun `at start of haystack`() {
        assertTrue(matchesWordBoundary("Mars rocks", "Mars"))
    }

    @Test fun `at end of haystack`() {
        assertTrue(matchesWordBoundary("rocks Mars", "Mars"))
    }

    @Test fun `whole haystack is the term`() {
        assertTrue(matchesWordBoundary("Mars", "Mars"))
    }

    @Test fun `empty haystack`() {
        assertFalse(matchesWordBoundary("", "Mars"))
    }

    @Test fun `multiple occurrences only need first to match`() {
        assertTrue(matchesWordBoundary("Marsupials but also Mars", "Mars"))
    }
}
```

- [ ] **Step 3: Run the tests; they should fail.**

Run: `cd /home/sneagan/software/personal/talon && JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 ./gradlew :app:testDebugUnitTest --tests 'io.nisfeb.talon.ai.WatchwordMatcherTest' 2>&1 | tail -20`

Expected: 9 failures (the 9 `assertTrue` test methods fail; the 6 `assertFalse` tests trivially pass against the always-false stub).

- [ ] **Step 4: Implement `matchesWordBoundary`.**

In `app/src/main/java/io/nisfeb/talon/ai/Watchwords.kt`, replace the `return false  // STUB …` line with the real implementation:

```kotlin
internal fun matchesWordBoundary(haystack: String, needle: String): Boolean {
    if (needle.isEmpty()) return false
    val h = haystack.lowercase()
    val n = needle.lowercase()
    var i = 0
    while (true) {
        val found = h.indexOf(n, startIndex = i)
        if (found < 0) return false
        val before = if (found == 0) ' ' else h[found - 1]
        val end = found + n.length
        val after = if (end >= h.length) ' ' else h[end]
        if (!before.isLetterOrDigit() && !after.isLetterOrDigit()) return true
        i = found + 1
    }
}
```

- [ ] **Step 5: Run the tests; they should now pass.**

Run: `cd /home/sneagan/software/personal/talon && JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 ./gradlew :app:testDebugUnitTest --tests 'io.nisfeb.talon.ai.WatchwordMatcherTest' 2>&1 | tail -10`

Expected: `BUILD SUCCESSFUL`, all 14 tests pass.

- [ ] **Step 6: Commit.**

```bash
git add app/src/main/java/io/nisfeb/talon/ai/Watchwords.kt \
        app/src/test/kotlin/io/nisfeb/talon/ai/WatchwordMatcherTest.kt
git commit -m "watchwords: matchesWordBoundary helper + tests"
```

---

## Task 4: Term sanitizer tests

**Files:**
- Create: `app/src/test/kotlin/io/nisfeb/talon/ai/WatchwordSanitizerTest.kt`

`sanitizeTerm` is already implemented in Task 3. We're just covering it with tests.

- [ ] **Step 1: Create the test file.**

```kotlin
package io.nisfeb.talon.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class WatchwordSanitizerTest {

    @Test fun `lowercases`() {
        assertEquals("mars", sanitizeTerm("Mars"))
        assertEquals("rfp", sanitizeTerm("RFP"))
    }

    @Test fun `replaces single non-alphanumeric with underscore`() {
        assertEquals("mars_society", sanitizeTerm("Mars Society"))
        assertEquals("at_t", sanitizeTerm("AT&T"))
    }

    @Test fun `collapses runs of non-alphanumerics into a single underscore`() {
        assertEquals("mars_society", sanitizeTerm("Mars  Society"))
        assertEquals("mars_society", sanitizeTerm("Mars-Society"))
        assertEquals("mars_society", sanitizeTerm("Mars--..--Society"))
    }

    @Test fun `trims leading and trailing underscores`() {
        assertEquals("mars", sanitizeTerm("  Mars  "))
        assertEquals("mars", sanitizeTerm("--Mars--"))
        assertEquals("mars", sanitizeTerm("..Mars.."))
    }

    @Test fun `pure punctuation collapses to empty`() {
        assertEquals("", sanitizeTerm("---"))
        assertEquals("", sanitizeTerm("   "))
        assertEquals("", sanitizeTerm("!@#"))
    }

    @Test fun `preserves digits`() {
        assertEquals("test123", sanitizeTerm("test123"))
        assertEquals("123_abc", sanitizeTerm("123 abc"))
    }

    @Test fun `collision detection — equivalent shapes produce same key`() {
        assertEquals(
            sanitizeTerm("Mars-Society"),
            sanitizeTerm("mars  society"),
        )
        assertEquals(
            sanitizeTerm("C++"),
            sanitizeTerm("c--"),  // both reduce to "c"
        )
    }
}
```

- [ ] **Step 2: Run the tests.**

Run: `cd /home/sneagan/software/personal/talon && JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 ./gradlew :app:testDebugUnitTest --tests 'io.nisfeb.talon.ai.WatchwordSanitizerTest' 2>&1 | tail -10`

Expected: `BUILD SUCCESSFUL`, 7 tests pass.

- [ ] **Step 3: Commit.**

```bash
git add app/src/test/kotlin/io/nisfeb/talon/ai/WatchwordSanitizerTest.kt
git commit -m "watchwords: sanitizeTerm tests"
```

---

## Task 5: Watchwords facade class

**Files:**
- Modify: `app/src/main/java/io/nisfeb/talon/ai/Watchwords.kt`
- Modify: `app/src/main/java/io/nisfeb/talon/data/NotifyPreferenceDao.kt` (add `mutedWhoms()` helper)

- [ ] **Step 1: Add `mutedWhoms()` to `NotifyPreferenceDao.kt`.**

In `app/src/main/java/io/nisfeb/talon/data/NotifyPreferenceDao.kt`, add this query:

```kotlin
    /** Set of every chat the user has muted (level = "none"). Used by
     *  Watchwords to exclude muted chats from both notifications and
     *  the hits feed. */
    @Query("SELECT whom FROM notify_preferences WHERE level = 'none'")
    suspend fun mutedWhoms(): List<String>
```

- [ ] **Step 2: Extend `Watchwords.kt` with the facade class and supporting types.**

Append to `app/src/main/java/io/nisfeb/talon/ai/Watchwords.kt`:

```kotlin
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.data.WatchwordChatExcludeEntity
import io.nisfeb.talon.data.WatchwordEntity
import io.nisfeb.talon.data.WatchwordHitEntity
import io.nisfeb.talon.urbit.StoryCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** A live match against an incoming message. */
data class MatchedTerm(val term: WatchwordEntity, val snippet: String)

/** Events emitted to [Watchwords.onChange] so TalonApplication can mirror to %settings. */
sealed class WatchwordChange {
    data class Upsert(val term: WatchwordEntity) : WatchwordChange()
    data class Remove(val termText: String) : WatchwordChange()
    data class Exclude(val whom: String) : WatchwordChange()
    data class Unexclude(val whom: String) : WatchwordChange()
    /** Sync just toggled on — push the entire local state so the ship matches. */
    data object SyncToggled : WatchwordChange()
}

/**
 * App-level facade for watchwords. Mirrors [io.nisfeb.talon.ai.AiSettings]'s
 * shape — a hot StateFlow of the live state, suspend mutators, and an
 * [onChange] hook wired by TalonApplication after SettingsSync exists.
 *
 * Each ship has its own Watchwords instance because terms / excludes
 * live in the per-ship [AppDatabase].
 */
class Watchwords(
    private val db: AppDatabase,
    private val ourPatpProvider: () -> String,
    private val scope: CoroutineScope,
    /** Backed by SharedPreferences in TalonApplication; just a flag for
     *  whether to mirror to %settings. Decoupled from this class so the
     *  prefs file can stay alongside the rest of the app's settings. */
    private val syncEnabledProvider: () -> Boolean,
) {

    val terms: StateFlow<List<WatchwordEntity>> =
        db.watchwords().streamTerms()
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val excludes: StateFlow<Set<String>> =
        db.watchwords().streamExcludes()
            .map { rows -> rows.map { it.whom }.toHashSet() as Set<String> }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, emptySet())

    private val _backfilling = MutableStateFlow<Set<Long>>(emptySet())
    /** Term ids currently running a backfill scan. UI shows a spinner per id. */
    val backfilling: StateFlow<Set<Long>> = _backfilling.asStateFlow()

    /** Tracks active backfill jobs so [remove] can cancel them mid-flight. */
    private val backfillJobs = ConcurrentHashMap<Long, Job>()

    /**
     * Wired by TalonApplication once SettingsSync exists. Receives
     * [WatchwordChange] events and (if syncEnabled) mirrors them to %settings.
     * The [transitionedOffSync] flag fires a one-time `clearWatchwordsOnShip`
     * — same idiom AiSettings uses.
     */
    @Volatile var onChange: ((WatchwordChange, transitionedOffSync: Boolean) -> Unit)? = null

    suspend fun add(term: String, notify: Boolean): Long {
        val trimmed = term.trim()
        require(trimmed.isNotEmpty()) { "watchword cannot be empty" }
        val id = db.watchwords().upsertTerm(
            WatchwordEntity(
                term = trimmed,
                notify = notify,
                createdMs = System.currentTimeMillis(),
            )
        )
        val saved = db.watchwords().getTerm(id) ?: return id
        onChange?.invoke(WatchwordChange.Upsert(saved), false)
        // Kick off backfill in the background; tracked in [_backfilling].
        startBackfill(saved)
        return id
    }

    suspend fun remove(termId: Long) {
        val term = db.watchwords().getTerm(termId) ?: return
        backfillJobs.remove(termId)?.cancel()
        _backfilling.value = _backfilling.value - termId
        db.watchwords().clearHitsForTerm(term.term)
        db.watchwords().deleteTermById(termId)
        onChange?.invoke(WatchwordChange.Remove(term.term), false)
    }

    suspend fun setNotify(termId: Long, notify: Boolean) {
        db.watchwords().setNotify(termId, notify)
        val updated = db.watchwords().getTerm(termId) ?: return
        onChange?.invoke(WatchwordChange.Upsert(updated), false)
    }

    suspend fun clearHits(termId: Long) {
        val term = db.watchwords().getTerm(termId) ?: return
        db.watchwords().clearHitsForTerm(term.term)
    }

    suspend fun excludeChat(whom: String, excluded: Boolean) {
        if (excluded) {
            db.watchwords().upsertExclude(WatchwordChatExcludeEntity(whom))
            onChange?.invoke(WatchwordChange.Exclude(whom), false)
        } else {
            db.watchwords().deleteExclude(whom)
            onChange?.invoke(WatchwordChange.Unexclude(whom), false)
        }
    }

    /**
     * Per-message live evaluation. Caller (TalonApp.kt's messageListener)
     * has already filtered: not self, not muted, not in excludes.
     * This walks current terms, tests each, persists hits with the lazy
     * prune rule, and returns the matches so the caller can fire
     * notifications for the notify=ON ones.
     */
    suspend fun evaluateLive(msg: MessageEntity, plainText: String): List<MatchedTerm> {
        val current = terms.value
        if (current.isEmpty()) return emptyList()
        val out = ArrayList<MatchedTerm>(2)
        for (t in current) {
            if (matchesWordBoundary(plainText, t.term)) {
                val snippet = plainText.take(200)
                out.add(MatchedTerm(t, snippet))
                db.watchwords().upsertHit(
                    WatchwordHitEntity(
                        term = t.term,
                        whom = msg.whom,
                        postId = msg.id,
                        sentMs = msg.sentMs,
                        snippet = snippet,
                    )
                )
                pruneIfOver(t.term)
            }
        }
        return out
    }

    /**
     * Fire-and-forget backfill scan for [term]. Adds [term.id] to
     * [_backfilling] for the duration. Cancellable from [remove].
     */
    private fun startBackfill(term: WatchwordEntity) {
        val job = scope.launch(Dispatchers.Default) {
            _backfilling.value = _backfilling.value + term.id
            try {
                runBackfill(term)
            } finally {
                _backfilling.value = _backfilling.value - term.id
                backfillJobs.remove(term.id)
            }
        }
        backfillJobs[term.id] = job
    }

    private suspend fun runBackfill(term: WatchwordEntity) {
        val excludesSet = excludes.value
        val muted = db.notifyPrefs().mutedWhoms().toHashSet()
        val ourPatp = ourPatpProvider()
        val hits = ArrayList<WatchwordHitEntity>(64)
        db.messages().candidatesForBackfill(term.term, ourPatp).collect { m ->
            if (hits.size >= MAX_HITS_PER_TERM) return@collect
            if (m.whom in excludesSet) return@collect
            if (m.whom in muted) return@collect
            val plainText = StoryCache.textFor(m.id, m.contentJson)
            if (!matchesWordBoundary(plainText, term.term)) return@collect
            hits.add(
                WatchwordHitEntity(
                    term = term.term,
                    whom = m.whom,
                    postId = m.id,
                    sentMs = m.sentMs,
                    snippet = plainText.take(200),
                )
            )
        }
        if (hits.isNotEmpty()) {
            db.watchwords().upsertHits(hits)
            pruneIfOver(term.term)
        }
    }

    /**
     * Hot-path prune. Only runs the DELETE when count exceeds the cap
     * by 100, so a quiet term never pays and a noisy term pays ~1% of
     * the time (every 100 inserts). See spec §Performance.
     */
    private suspend fun pruneIfOver(term: String) {
        val count = db.watchwords().countForTerm(term)
        if (count > MAX_HITS_PER_TERM + 100) {
            db.watchwords().pruneToNewest(term, MAX_HITS_PER_TERM)
        }
    }

    /** Sync-toggle flush: caller has already flipped the sync flag in prefs. */
    fun emitSyncToggled() {
        onChange?.invoke(WatchwordChange.SyncToggled, !syncEnabledProvider())
    }
}
```

- [ ] **Step 3: Verify the project compiles.**

Run: `cd /home/sneagan/software/personal/talon && JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 ./gradlew :app:compileDebugKotlin 2>&1 | tail -10`

Expected: `BUILD SUCCESSFUL`. The `candidatesForBackfill` reference will fail until Task 6 — if it does, jump to Task 6 first then return.

(In practice: `MessageDao.candidatesForBackfill` doesn't exist yet, so this WILL fail. That's expected — proceed to Task 6.)

- [ ] **Step 4: Commit (after Task 6 makes the build green).**

We'll defer the commit to the end of Task 6 since the two tasks are coupled.

---

## Task 6: candidatesForBackfill query

**Files:**
- Modify: `app/src/main/java/io/nisfeb/talon/data/MessageDao.kt`

- [ ] **Step 1: Add the query.**

In `MessageDao.kt`, append to the abstract class (next to the existing `search(...)` query):

```kotlin
    /**
     * Backfill candidate stream for [Watchwords.runBackfill]. The LIKE
     * pre-filter on contentJson narrows candidates without parsing
     * JSON; callers verify each survivor against the rendered plain
     * text in memory. Returns a Flow so the consumer can early-break
     * once the per-term hit cap is reached.
     */
    @Query("""
        SELECT * FROM messages
        WHERE isDeleted = 0
          AND author != :exceptAuthor
          AND contentJson LIKE '%' || :term || '%' COLLATE NOCASE
        ORDER BY sentMs DESC
    """)
    abstract fun candidatesForBackfill(term: String, exceptAuthor: String): Flow<MessageEntity>
```

- [ ] **Step 2: Verify the project compiles.**

Run: `cd /home/sneagan/software/personal/talon && JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 ./gradlew :app:compileDebugKotlin 2>&1 | tail -10`

Expected: `BUILD SUCCESSFUL`. Watchwords.kt now has all its dependencies satisfied.

- [ ] **Step 3: Commit Tasks 5 and 6 together.**

```bash
git add app/src/main/java/io/nisfeb/talon/ai/Watchwords.kt \
        app/src/main/java/io/nisfeb/talon/data/MessageDao.kt \
        app/src/main/java/io/nisfeb/talon/data/NotifyPreferenceDao.kt
git commit -m "watchwords: facade class + backfill candidates query"
```

---

## Task 7: Notifications channel + showWatchwordHit

**Files:**
- Modify: `app/src/main/java/io/nisfeb/talon/Notifications.kt`

- [ ] **Step 1: Add the channel constant.**

Near the top of the `Notifications` object (with the other `CHANNEL_…` and `EXTRA_…` constants):

```kotlin
    const val CHANNEL_WATCHWORDS = "watchwords"
```

- [ ] **Step 2: Register the channel in `ensureChannel`.**

Add after the existing `CHANNEL_SYNC` block:

```kotlin
        if (mgr.getNotificationChannel(CHANNEL_WATCHWORDS) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_WATCHWORDS,
                    "Watchwords",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "Hits on user-defined watchword terms"
                    enableLights(true)
                    enableVibration(true)
                }
            )
        }
```

- [ ] **Step 3: Add the `showWatchwordHit` method.**

After `showMessage` and before `clear`, add:

```kotlin
    /**
     * Watchword-hit notification. Same tap intent shape as [showMessage]
     * but on a separate channel and tag namespace so it can be tuned
     * independently and never collides with regular chat notifications.
     */
    fun showWatchwordHit(
        context: Context,
        whom: String,
        postId: String?,
        parentId: String? = null,
        terms: List<String>,
        label: String,
        body: String,
        sentMs: Long,
    ) {
        val mgr = ContextCompat.getSystemService(context, NotificationManager::class.java)
            ?: return

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_WHOM, whom)
            if (parentId != null) {
                putExtra(EXTRA_OPEN_THREAD, parentId)
                if (postId != null) putExtra(EXTRA_THREAD_ANCHOR, postId)
            } else if (postId != null) {
                putExtra(EXTRA_SCROLL_TO_MESSAGE, postId)
            }
        }
        val pending = PendingIntent.getActivity(
            context,
            ("watchword:$whom").hashCode(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = "${terms.joinToString(", ")} in $label"

        val notification = NotificationCompat.Builder(context, CHANNEL_WATCHWORDS)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setWhen(sentMs)
            .setShowWhen(true)
            .build()

        // Tag = "watchword:<whom>" so repeated hits in the same chat
        // collapse into one row, but never collide with showMessage's
        // <whom>-tagged notification for the same chat.
        mgr.notify("watchword:$whom", NOTIFICATION_ID, notification)
    }
```

- [ ] **Step 4: Verify the project compiles.**

Run: `cd /home/sneagan/software/personal/talon && JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 ./gradlew :app:compileDebugKotlin 2>&1 | tail -10`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit.**

```bash
git add app/src/main/java/io/nisfeb/talon/Notifications.kt
git commit -m "watchwords: dedicated notification channel + showWatchwordHit"
```

---

## Task 8: Wire Watchwords into TalonApplication

**Files:**
- Modify: `app/src/main/java/io/nisfeb/talon/TalonApplication.kt`

- [ ] **Step 1: Add the `watchwords` lateinit field + `watchwordsSyncEnabled` SharedPref-backed StateFlow.**

In the per-ship-rebuilt section of `TalonApplication` (alongside `db`, `repo`, etc.), add:

```kotlin
    lateinit var watchwords: io.nisfeb.talon.ai.Watchwords
        private set
```

Add a SharedPreferences-backed flag for sync (kept across ship rebuilds, so place it with the always-on singletons):

```kotlin
    private val watchwordsPrefs by lazy {
        getSharedPreferences("talon_watchwords", MODE_PRIVATE)
    }

    private val _watchwordsSyncEnabled = MutableStateFlow(
        watchwordsPrefs.getBoolean(KEY_WATCHWORDS_SYNC, false)
    )
    val watchwordsSyncEnabled: StateFlow<Boolean> = _watchwordsSyncEnabled.asStateFlow()

    fun setWatchwordsSyncEnabled(enabled: Boolean) {
        if (_watchwordsSyncEnabled.value == enabled) return
        watchwordsPrefs.edit().putBoolean(KEY_WATCHWORDS_SYNC, enabled).apply()
        _watchwordsSyncEnabled.value = enabled
        watchwords.emitSyncToggled()
    }

    private companion object {
        private const val KEY_WATCHWORDS_SYNC = "sync_enabled"
    }
```

(Add the `import kotlinx.coroutines.flow.MutableStateFlow` etc. imports if not already present — they are.)

- [ ] **Step 2: Build the watchwords field in `buildShipScoped`.**

Inside `buildShipScoped` after `repo = TlonChatRepo(db, aiSettings)`:

```kotlin
        watchwords = io.nisfeb.talon.ai.Watchwords(
            db = db,
            ourPatpProvider = { ship.takeIf { it != "none" } ?: "" },
            scope = appScope,
            syncEnabledProvider = { _watchwordsSyncEnabled.value },
        )
```

- [ ] **Step 3: Wire `watchwords.onChange`.**

After the existing `aiSettings.onStateChange = { … }` wiring at the end of `onCreate`, add:

```kotlin
        watchwords.onChange = { evt, transitionedOffSync ->
            appScope.launch {
                runCatching {
                    when {
                        transitionedOffSync ->
                            repo.settingsSync.clearWatchwordsOnShip()
                        _watchwordsSyncEnabled.value -> when (evt) {
                            is io.nisfeb.talon.ai.WatchwordChange.Upsert ->
                                repo.settingsSync.pushWatchwordEntry(evt.term)
                            is io.nisfeb.talon.ai.WatchwordChange.Remove ->
                                repo.settingsSync.deleteWatchwordEntry(evt.termText)
                            is io.nisfeb.talon.ai.WatchwordChange.Exclude ->
                                repo.settingsSync.pushWatchwordExclude(evt.whom)
                            is io.nisfeb.talon.ai.WatchwordChange.Unexclude ->
                                repo.settingsSync.deleteWatchwordExclude(evt.whom)
                            is io.nisfeb.talon.ai.WatchwordChange.SyncToggled ->
                                repo.settingsSync.pushAllWatchwords()
                        }
                        else -> Unit
                    }
                }
            }
        }
```

(`SettingsSync` doesn't have these helpers yet — we add them in Task 14. Build will be red until then.)

- [ ] **Step 4: Verify it compiles up to here (it will fail until Task 14 — that's expected).**

Run: `cd /home/sneagan/software/personal/talon && JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 ./gradlew :app:compileDebugKotlin 2>&1 | tail -10`

Expected: failures referencing `clearWatchwordsOnShip`, `pushWatchwordEntry`, etc. We'll resolve these in Task 14.

- [ ] **Step 5: Commit (after Task 14 makes the build green).**

Defer commit until then.

---

## Task 9: Hook watchword evaluation into messageListener

**Files:**
- Modify: `app/src/main/java/io/nisfeb/talon/ui/TalonApp.kt`

- [ ] **Step 1: Replace the existing `appScope.launch { … }` block inside `messageListener`.**

Find the existing block in `TalonApp.kt` around line 297-320 (the one that reads `val level = app.db.notifyPrefs().levelFor(m.whom) …`). Replace it with:

```kotlin
                appScope.launch {
                    val level = app.db.notifyPrefs().levelFor(m.whom) ?: NotifyLevel.DEFAULT
                    val muted = level == NotifyLevel.NONE

                    // ── existing notification path ─────────────────────
                    val shouldFire = !muted && when (level) {
                        NotifyLevel.MENTIONS ->
                            replyToUs || isMentioned(m.contentJson, loggedInShip ?: "")
                        else -> true
                    }
                    if (shouldFire) {
                        val title = contactMap.conversationLabel(m.whom)
                        val authorLabel = contactMap.displayName(m.author)
                        val preview = StoryCache.textFor(m.id, m.contentJson)
                            .replace('\n', ' ')
                            .take(160)
                        Notifications.showMessage(
                            context = context,
                            whom = m.whom,
                            postId = m.id,
                            parentId = m.parentId,
                            title = title,
                            body = if (m.whom.startsWith("~")) preview else "$authorLabel: $preview",
                            sentMs = m.sentMs,
                        )
                    }

                    // ── watchword path (NEW) ───────────────────────────
                    val ourPatp = loggedInShip ?: ""
                    if (m.author == ourPatp) return@launch
                    if (muted) return@launch
                    if (m.whom in app.watchwords.excludes.value) return@launch
                    val terms = app.watchwords.terms.value
                    if (terms.isEmpty()) return@launch

                    val plainText = StoryCache.textFor(m.id, m.contentJson)
                    val matches = app.watchwords.evaluateLive(m, plainText)
                    if (matches.isEmpty()) return@launch
                    val notifiable = matches.filter { it.term.notify }
                    if (notifiable.isEmpty()) return@launch

                    val convoLabel = contactMap.conversationLabel(m.whom)
                    Notifications.showWatchwordHit(
                        context = context,
                        whom = m.whom,
                        postId = m.id,
                        parentId = m.parentId,
                        terms = notifiable.map { it.term.term },
                        label = convoLabel,
                        body = plainText.take(160).replace('\n', ' '),
                        sentMs = m.sentMs,
                    )
                }
```

- [ ] **Step 2: Verify it compiles.**

Run: `cd /home/sneagan/software/personal/talon && JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 ./gradlew :app:compileDebugKotlin 2>&1 | tail -10`

Expected: still failing on the `SettingsSync` symbols from Task 8. (`watchwords.evaluateLive` etc. resolve fine.)

- [ ] **Step 3: Commit (after Task 14).**

Defer.

---

## Task 10: WatchwordsScreen

**Files:**
- Create: `app/src/main/java/io/nisfeb/talon/ui/screens/WatchwordsScreen.kt`

- [ ] **Step 1: Create the screen file.**

```kotlin
package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.TalonApplication
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.WatchwordHitEntity
import io.nisfeb.talon.ui.ContactMap
import io.nisfeb.talon.ui.contactMapFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun WatchwordsScreen(
    db: AppDatabase,
    onBack: () -> Unit,
    onOpenConversation: (whom: String, postId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as TalonApplication

    val terms by app.watchwords.terms.collectAsState()
    val hitCounts by remember {
        db.watchwords().streamHitCountsByTerm()
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
    }.collectAsState(initial = emptyList())
    val countsByTerm = remember(hitCounts) {
        hitCounts.associate { it.term to it.cnt }
    }

    var selectedTerm by remember { mutableStateOf<String?>(null) }
    val hits by remember(selectedTerm) {
        if (selectedTerm == null)
            db.watchwords().streamAllHits()
        else
            db.watchwords().streamHitsForTerm(selectedTerm!!)
    }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .let { remember(selectedTerm) { it } }
        .collectAsState(initial = emptyList())

    val contactMap by remember {
        contactMapFlow(
            db.contacts().stream(),
            db.clubs().stream(),
            db.groups().streamGroups(),
            db.groups().streamChannelGroups(),
        )
    }.collectAsState(initial = ContactMap.EMPTY)

    var manageOpen by remember { mutableStateOf(false) }

    Column(modifier = modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                "Watchwords",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(start = 4.dp).weight(1f),
            )
            IconButton(onClick = { manageOpen = true }) {
                Icon(Icons.Filled.Edit, contentDescription = "Manage terms")
            }
        }
        HorizontalDivider()

        if (terms.isNotEmpty()) {
            FilterChips(
                terms = terms.map { it.term },
                hitCounts = countsByTerm,
                selected = selectedTerm,
                onSelect = { selectedTerm = it },
            )
            HorizontalDivider()
        }

        when {
            terms.isEmpty() -> EmptyTerms(onAdd = { manageOpen = true })
            hits.isEmpty() -> EmptyHits(termCount = terms.size)
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(
                    items = hits,
                    key = { "${it.term}|${it.whom}|${it.postId}" },
                ) { hit ->
                    HitRow(
                        hit = hit,
                        contactMap = contactMap,
                        onClick = { onOpenConversation(hit.whom, hit.postId) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (manageOpen) {
        ManageTermsSheet(onDismiss = { manageOpen = false })
    }
}

@Composable
private fun FilterChips(
    terms: List<String>,
    hitCounts: Map<String, Int>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Chip(text = "All", selected = selected == null, onClick = { onSelect(null) })
        }
        items(terms, key = { it }) { term ->
            val count = hitCounts[term] ?: 0
            Chip(
                text = if (count > 0) "$term  $count" else term,
                selected = selected == term,
                onClick = { onSelect(term) },
            )
        }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge.copy(
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        ),
        color = fg,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun HitRow(
    hit: WatchwordHitEntity,
    contactMap: ContactMap,
    onClick: () -> Unit,
) {
    val convoLabel = remember(hit.whom, contactMap) { contactMap.conversationLabel(hit.whom) }
    val timeLabel = remember(hit.sentMs) { DATE_FMT.format(Date(hit.sentMs)) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "${hit.term} · $convoLabel · $timeLabel",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            hit.snippet,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 3,
        )
    }
}

@Composable
private fun EmptyTerms(onAdd: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Add a watchword and get pinged when it's mentioned anywhere.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onAdd) { Text("Add a watchword") }
        }
    }
}

@Composable
private fun EmptyHits(termCount: Int) {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "Watching $termCount term${if (termCount == 1) "" else "s"}. No hits yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val DATE_FMT = SimpleDateFormat("MMM d HH:mm", Locale.getDefault())
```

The file references `ManageTermsSheet`, which we create next. Build will fail until then.

- [ ] **Step 2: Defer compile + commit until Task 11.**

---

## Task 11: ManageTermsSheet

**Files:**
- Create: `app/src/main/java/io/nisfeb/talon/ui/screens/ManageTermsSheet.kt`

- [ ] **Step 1: Create the sheet.**

```kotlin
package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.TalonApplication
import io.nisfeb.talon.ai.sanitizeTerm
import io.nisfeb.talon.data.WatchwordEntity
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageTermsSheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as TalonApplication
    val watchwords = app.watchwords
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()

    val terms by watchwords.terms.collectAsState()
    val backfilling by watchwords.backfilling.collectAsState()
    val hitCounts by remember {
        app.db.watchwords().streamHitCountsByTerm()
    }.collectAsState(initial = emptyList())
    val countsByTerm = remember(hitCounts) { hitCounts.associate { it.term to it.cnt } }

    val syncEnabled by app.watchwordsSyncEnabled.collectAsState()

    var draftText by remember { mutableStateOf("") }
    var draftNotify by remember { mutableStateOf(true) }
    var pendingDelete by remember { mutableStateOf<WatchwordEntity?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Watchwords",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            )

            // Add row
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draftText,
                    onValueChange = { draftText = it },
                    placeholder = { Text("Add a watchword…") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Notify", style = MaterialTheme.typography.labelSmall)
                    Switch(checked = draftNotify, onCheckedChange = { draftNotify = it })
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        val trimmed = draftText.trim()
                        if (trimmed.isNotEmpty()) {
                            scope.launch { watchwords.add(trimmed, draftNotify) }
                            draftText = ""
                        }
                    },
                    enabled = draftText.trim().isNotEmpty(),
                ) { Text("Add") }
            }

            // Collision warning when the typed term sanitizes to an existing key
            val typed = draftText.trim()
            if (syncEnabled && typed.isNotEmpty()) {
                val key = remember(typed) { sanitizeTerm(typed) }
                val collision = remember(typed, terms) {
                    terms.firstOrNull { sanitizeTerm(it.term) == key && it.term != typed }
                }
                if (collision != null) {
                    Text(
                        "This term shares a sync key with '${collision.term}'. Pick distinct words to avoid clobbering it on other devices.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (terms.isEmpty()) {
                Text(
                    "No watchwords yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                terms.forEach { term ->
                    TermRow(
                        term = term,
                        hitCount = countsByTerm[term.term] ?: 0,
                        backfilling = term.id in backfilling,
                        onNotifyChange = { on ->
                            scope.launch { watchwords.setNotify(term.id, on) }
                        },
                        onDelete = { pendingDelete = term },
                    )
                }
            }

            // Sync row
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Sync watchwords across devices",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        if (syncEnabled) "On — terms mirror to your ship's settings."
                        else "Off — terms stay on this device only.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = syncEnabled,
                    onCheckedChange = { app.setWatchwordsSyncEnabled(it) },
                )
            }

            Text(
                "Backfill scans messages cached on this device. Older history pages back-load as you scroll a chat.",
                style = MaterialTheme.typography.labelSmall.copy(fontStyle = FontStyle.Italic),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    pendingDelete?.let { term ->
        val count = countsByTerm[term.term] ?: 0
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete '${term.term}'?") },
            text = {
                Text(
                    if (count > 0) "This clears its $count hit${if (count == 1) "" else "s"}."
                    else "No hits will be lost."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { watchwords.remove(term.id) }
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun TermRow(
    term: WatchwordEntity,
    hitCount: Int,
    backfilling: Boolean,
    onNotifyChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            term.term,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (backfilling) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier.size(14.dp).padding(end = 8.dp),
            )
        } else if (hitCount > 0) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    hitCount.toString(),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = term.notify,
            onCheckedChange = onNotifyChange,
        )
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "Delete",
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}
```

- [ ] **Step 2: Verify the project compiles up to here.**

Run: `cd /home/sneagan/software/personal/talon && JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 ./gradlew :app:compileDebugKotlin 2>&1 | tail -10`

Expected: still failing on `SettingsSync.pushWatchwordEntry` etc. (Tasks 8, 9). Both UI files now compile.

- [ ] **Step 3: Defer commit until Task 14 makes the build green.**

---

## Task 12: Add "Watchwords" entry to home menu

**Files:**
- Modify: `app/src/main/java/io/nisfeb/talon/ui/screens/DmListScreen.kt`

- [ ] **Step 1: Add a new callback parameter `onOpenWatchwords` to `DmListScreen(...)`.**

In the function signature, after `onOpenActivity: () -> Unit,`, add:

```kotlin
    onOpenWatchwords: () -> Unit = {},
```

- [ ] **Step 2: Add the menu item in the existing `DropdownMenu`.**

Inside the dropdown (after the `"Activity"` item, before `"Administration"`), add:

```kotlin
                    DropdownMenuItem(
                        text = { Text("Watchwords") },
                        onClick = {
                            menuOpen = false
                            onOpenWatchwords()
                        },
                    )
```

- [ ] **Step 3: Defer compile + commit until Task 14.**

---

## Task 13: Per-chat exclude menu item in DmChatScreen

**Files:**
- Modify: `app/src/main/java/io/nisfeb/talon/ui/screens/DmChatScreen.kt`

- [ ] **Step 1: Find the existing notification-level `DropdownMenu` block.**

Locate the `DropdownMenu(expanded = notifyMenuOpen, …) { … }` block in `DmChatScreen.kt` (currently around line 632, with the three `NotifyLevel` items).

- [ ] **Step 2: Add a `Watchwords`-related state read above the menu (next to `notifyLevel`).**

Locate `val notifyLevel = notifyPref?.level ?: NotifyLevel.DEFAULT` and add right after it:

```kotlin
    val excludedFromWatchwords by talonApp.watchwords.excludes.collectAsState()
    val isExcludedFromWatchwords = whom in excludedFromWatchwords
```

(Note: `talonApp` is the existing `(LocalContext.current.applicationContext as TalonApplication)` reference further up in the function.)

- [ ] **Step 3: Add the menu item.**

Inside the same `DropdownMenu` (after the existing notify-level items, before its closing `}`), add:

```kotlin
                    androidx.compose.material3.HorizontalDivider()
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (isExcludedFromWatchwords) "Include in watchwords"
                                else "Exclude from watchwords"
                            )
                        },
                        onClick = {
                            notifyMenuOpen = false
                            scope.launch {
                                talonApp.watchwords.excludeChat(whom, !isExcludedFromWatchwords)
                            }
                        },
                    )
```

- [ ] **Step 4: Defer compile + commit until Task 14.**

---

## Task 14: SettingsSync — buckets + push helpers + apply branches

**Files:**
- Modify: `app/src/main/java/io/nisfeb/talon/urbit/SettingsSync.kt`

This task makes the build green. It's the last piece blocking Tasks 8/9/10/11/12/13's commits.

- [ ] **Step 1: Add bucket constants to the `companion object` near `BUCKET_NOTIFY_PREFS`.**

```kotlin
        const val BUCKET_WATCHWORDS = "watchwords"
        const val BUCKET_WATCHWORD_EXCLUDES = "watchword-excludes"
```

- [ ] **Step 2: Add push helpers (place them near `setNotifyLevel` for proximity to the existing per-bucket helpers).**

```kotlin
    /** Mirror one watchword term to the ship's settings. */
    suspend fun pushWatchwordEntry(term: io.nisfeb.talon.data.WatchwordEntity) {
        val ch = channel ?: return
        val key = io.nisfeb.talon.ai.sanitizeTerm(term.term)
        if (key.isEmpty()) return
        val payload = buildJsonObject {
            put("put-entry", buildJsonObject {
                put("desk", DESK)
                put("bucket-key", BUCKET_WATCHWORDS)
                put("entry-key", key)
                put("value", buildJsonObject {
                    put("term", term.term)
                    put("notify", term.notify)
                    put("createdMs", term.createdMs)
                })
            })
        }
        runCatching { ch.poke("settings", "settings-event", payload) }
            .onFailure { Log.w(TAG, "pushWatchwordEntry failed", it) }
    }

    suspend fun deleteWatchwordEntry(termText: String) {
        val ch = channel ?: return
        val key = io.nisfeb.talon.ai.sanitizeTerm(termText)
        if (key.isEmpty()) return
        val payload = buildJsonObject {
            put("del-entry", buildJsonObject {
                put("desk", DESK)
                put("bucket-key", BUCKET_WATCHWORDS)
                put("entry-key", key)
            })
        }
        runCatching { ch.poke("settings", "settings-event", payload) }
            .onFailure { Log.w(TAG, "deleteWatchwordEntry failed", it) }
    }

    suspend fun pushWatchwordExclude(whom: String) {
        val ch = channel ?: return
        val payload = buildJsonObject {
            put("put-entry", buildJsonObject {
                put("desk", DESK)
                put("bucket-key", BUCKET_WATCHWORD_EXCLUDES)
                put("entry-key", whom)
                put("value", JsonPrimitive(true))
            })
        }
        runCatching { ch.poke("settings", "settings-event", payload) }
            .onFailure { Log.w(TAG, "pushWatchwordExclude failed", it) }
    }

    suspend fun deleteWatchwordExclude(whom: String) {
        val ch = channel ?: return
        val payload = buildJsonObject {
            put("del-entry", buildJsonObject {
                put("desk", DESK)
                put("bucket-key", BUCKET_WATCHWORD_EXCLUDES)
                put("entry-key", whom)
            })
        }
        runCatching { ch.poke("settings", "settings-event", payload) }
            .onFailure { Log.w(TAG, "deleteWatchwordExclude failed", it) }
    }

    /** One-shot full flush of watchwords + excludes when sync is enabled. */
    suspend fun pushAllWatchwords() {
        db.watchwords().streamTerms().firstOrNull()?.forEach { pushWatchwordEntry(it) }
        db.watchwords().excludesAsList().forEach { pushWatchwordExclude(it) }
    }

    suspend fun clearWatchwordsOnShip() {
        val ch = channel ?: return
        runCatching {
            ch.poke("settings", "settings-event", buildJsonObject {
                put("del-bucket", buildJsonObject {
                    put("desk", DESK)
                    put("bucket-key", BUCKET_WATCHWORDS)
                })
            })
            ch.poke("settings", "settings-event", buildJsonObject {
                put("del-bucket", buildJsonObject {
                    put("desk", DESK)
                    put("bucket-key", BUCKET_WATCHWORD_EXCLUDES)
                })
            })
        }.onFailure { Log.w(TAG, "clearWatchwordsOnShip failed", it) }
    }
```

(Add `import kotlinx.coroutines.flow.firstOrNull` at the top of the file if it's not already imported.)

- [ ] **Step 3: Add branches to `applyEntry`.**

In the `when (bucket) { … }` block inside `applyEntry`, after the existing `BUCKET_NOTIFY_PREFS` branch, add:

```kotlin
            BUCKET_WATCHWORDS -> {
                val obj = value as? JsonObject ?: return
                val termText = obj["term"].asStr() ?: return
                val notify = (obj["notify"] as? JsonPrimitive)?.booleanOrNull ?: true
                val createdMs = (obj["createdMs"] as? JsonPrimitive)?.longOrNull
                    ?: System.currentTimeMillis()
                // Upsert via term-text uniqueness — preserves local id.
                val existing = db.watchwords().getTermByText(termText)
                if (existing == null) {
                    db.watchwords().upsertTerm(
                        io.nisfeb.talon.data.WatchwordEntity(
                            term = termText,
                            notify = notify,
                            createdMs = createdMs,
                        )
                    )
                    // No backfill on remote-applied terms — the originating
                    // device already populated its own hits feed; this
                    // device picks up future matches from the live listener.
                } else if (existing.notify != notify) {
                    db.watchwords().setNotify(existing.id, notify)
                }
            }
            BUCKET_WATCHWORD_EXCLUDES -> {
                db.watchwords().upsertExclude(
                    io.nisfeb.talon.data.WatchwordChatExcludeEntity(entry)
                )
            }
```

- [ ] **Step 4: Add branches to `removeEntry`.**

In the `when (bucket) { … }` inside `removeEntry`, after the existing `BUCKET_NOTIFY_PREFS` branch:

```kotlin
            BUCKET_WATCHWORDS -> {
                // entry-key is sanitized form; delete by matching sanitization.
                val terms = db.watchwords().streamTerms().firstOrNull().orEmpty()
                terms.firstOrNull {
                    io.nisfeb.talon.ai.sanitizeTerm(it.term) == entry
                }?.let { db.watchwords().deleteTermById(it.id) }
            }
            BUCKET_WATCHWORD_EXCLUDES -> {
                db.watchwords().deleteExclude(entry)
            }
```

- [ ] **Step 5: Add branches to `applyBucket`.**

In the `when (bucket) { … }` inside `applyBucket` (the replace-on-apply path), after the existing `BUCKET_NOTIFY_PREFS` branch:

```kotlin
            BUCKET_WATCHWORDS -> {
                // Apply each entry; we don't have a "deleteAllTerms" since
                // the local watchwords table also feeds the live runtime.
                // Per-entry upsert + drop-if-not-in-bucket.
                val incoming = entries?.entries?.mapNotNull { (key, value) ->
                    val obj = value as? JsonObject ?: return@mapNotNull null
                    val termText = obj["term"].asStr() ?: return@mapNotNull null
                    val notify = (obj["notify"] as? JsonPrimitive)?.booleanOrNull ?: true
                    val createdMs = (obj["createdMs"] as? JsonPrimitive)?.longOrNull
                        ?: System.currentTimeMillis()
                    Triple(key, termText, notify to createdMs)
                }.orEmpty()
                val incomingTermTexts = incoming.map { it.second }.toHashSet()
                val existing = db.watchwords().streamTerms().firstOrNull().orEmpty()
                // Drop locals that aren't present in the remote bucket
                existing.filter { it.term !in incomingTermTexts }.forEach {
                    db.watchwords().deleteTermById(it.id)
                }
                // Upsert remotes
                incoming.forEach { (_, termText, meta) ->
                    val (notify, createdMs) = meta
                    val match = db.watchwords().getTermByText(termText)
                    if (match == null) {
                        db.watchwords().upsertTerm(
                            io.nisfeb.talon.data.WatchwordEntity(
                                term = termText,
                                notify = notify,
                                createdMs = createdMs,
                            )
                        )
                    } else if (match.notify != notify) {
                        db.watchwords().setNotify(match.id, notify)
                    }
                }
            }
            BUCKET_WATCHWORD_EXCLUDES -> {
                val incomingWhoms = entries?.keys?.toHashSet().orEmpty()
                val existing = db.watchwords().excludesAsList().toHashSet()
                (existing - incomingWhoms).forEach { db.watchwords().deleteExclude(it) }
                (incomingWhoms - existing).forEach {
                    db.watchwords().upsertExclude(
                        io.nisfeb.talon.data.WatchwordChatExcludeEntity(it)
                    )
                }
            }
```

- [ ] **Step 6: Add branches to `clearBucketLocally`.**

In `clearBucketLocally`'s `when`, after the existing branches:

```kotlin
            BUCKET_WATCHWORDS -> {
                val existing = db.watchwords().streamTerms().firstOrNull().orEmpty()
                existing.forEach { db.watchwords().deleteTermById(it.id) }
            }
            BUCKET_WATCHWORD_EXCLUDES -> {
                db.watchwords().excludesAsList().forEach {
                    db.watchwords().deleteExclude(it)
                }
            }
```

- [ ] **Step 7: Verify the full project compiles.**

Run: `cd /home/sneagan/software/personal/talon && JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 ./gradlew :app:compileDebugKotlin 2>&1 | tail -10`

Expected: `BUILD SUCCESSFUL`. All previous tasks' code paths now resolve.

- [ ] **Step 8: Run all unit tests.**

Run: `cd /home/sneagan/software/personal/talon && JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 ./gradlew :app:testDebugUnitTest 2>&1 | tail -10`

Expected: all tests pass — both new tests (Tasks 3 + 4) and pre-existing tests (UrbitTime, ChatWireShapes, etc.).

- [ ] **Step 9: Big commit covering everything from Tasks 8, 9, 10, 11, 12, 13, 14.**

```bash
git add app/src/main/java/io/nisfeb/talon/TalonApplication.kt \
        app/src/main/java/io/nisfeb/talon/ui/TalonApp.kt \
        app/src/main/java/io/nisfeb/talon/ui/screens/WatchwordsScreen.kt \
        app/src/main/java/io/nisfeb/talon/ui/screens/ManageTermsSheet.kt \
        app/src/main/java/io/nisfeb/talon/ui/screens/DmListScreen.kt \
        app/src/main/java/io/nisfeb/talon/ui/screens/DmChatScreen.kt \
        app/src/main/java/io/nisfeb/talon/urbit/SettingsSync.kt
git commit -m "watchwords: live runtime + UI surfaces + settings sync"
```

---

## Task 15: TalonApp routing for WatchwordsScreen

**Files:**
- Modify: `app/src/main/java/io/nisfeb/talon/ui/TalonApp.kt`

- [ ] **Step 1: Add `watchwordsOpen` state.**

Near the other screen-state mutables (`searchOpen`, `bookmarksOpen`, etc.):

```kotlin
    var watchwordsOpen by remember { mutableStateOf(false) }
```

- [ ] **Step 2: Pass `onOpenWatchwords` into `DmListScreen`.**

In the existing `DmListScreen(...)` call, add:

```kotlin
                onOpenWatchwords = { watchwordsOpen = true },
```

(Place near the other `onOpenXxx` callbacks.)

- [ ] **Step 3: Add the route branch.**

In the `when { … }` block that decides which screen to render, add a branch — place it next to the existing `bookmarksOpen -> BookmarksScreen(...)` / `activityOpen -> ActivityFeedScreen(...)` branches:

```kotlin
            watchwordsOpen -> WatchwordsScreen(
                db = app.db,
                onBack = { watchwordsOpen = false },
                onOpenConversation = { whom, postId ->
                    openWhom = whom
                    pendingScrollMessageId = postId
                    watchwordsOpen = false
                },
                modifier = mod,
            )
```

Add the import at the top:

```kotlin
import io.nisfeb.talon.ui.screens.WatchwordsScreen
```

- [ ] **Step 4: Verify it compiles.**

Run: `cd /home/sneagan/software/personal/talon && JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 ./gradlew :app:compileDebugKotlin 2>&1 | tail -10`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit.**

```bash
git add app/src/main/java/io/nisfeb/talon/ui/TalonApp.kt
git commit -m "watchwords: route home menu to WatchwordsScreen"
```

---

## Task 16: Build, install, and manual smoke test

**Files:**
- Modify: `app/build.gradle.kts` (version bump)

- [ ] **Step 1: Bump version.**

In `app/build.gradle.kts`, change `versionCode = 14` to `versionCode = 15` and `versionName = "0.2.12"` to `versionName = "0.3.0"`. (Watchwords is a meaningful feature addition — minor-version bump is correct.)

- [ ] **Step 2: Build the release APK.**

Run: `cd /home/sneagan/software/personal/talon && JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 ./gradlew :app:assembleRelease 2>&1 | tail -10`

Expected: `BUILD SUCCESSFUL`. Output at `app/build/outputs/apk/release/app-release.apk`.

- [ ] **Step 3: Install on the phone.**

Run:
```bash
/bin/cp -f /home/sneagan/software/personal/talon/app/build/outputs/apk/release/app-release.apk /home/sneagan/talon-0.3.0.apk
adb install -r /home/sneagan/talon-0.3.0.apk
```

Expected: `Success`.

- [ ] **Step 4: Smoke test manually on the phone.**

Run through the eight-step manual integration smoke from the spec's §Testing:

1. Open the app, hamburger / overflow menu → tap **Watchwords**. Empty-state CTA appears.
2. Tap "Add a watchword", type `test`, leave Notify ON, tap Add. Term appears with a brief spinner; spinner clears. (Backfill ran.)
3. Open any chat. Send the message `this is a test message` from another device or another ship. Within ~1s a system notification appears titled `test in <chat label>`.
4. Re-open the **Watchwords** screen. The hit appears in the feed with the snippet.
5. From the term row in the manage sheet, flip Notify OFF for `test`. Send another `test` message. No notification fires; feed gains a new row.
6. Open a chat, top-bar bell icon → **Exclude from watchwords**. Send `test` in that chat from another device. No new feed entry.
7. From the home menu, mute a different chat (set NotifyLevel to None). Send `test` there. No notification, no feed entry.
8. Toggle **Sync watchwords across devices** ON in the manage sheet. From a separate machine that has CLI access to the ship, scry: `:settings|read /entries/talon/watchwords`. Verify the entry exists. Toggle OFF — the bucket disappears from the ship's settings.

If anything fails, fix in place and amend the most recent commit.

- [ ] **Step 5: Final commit (just the version bump).**

```bash
git add app/build.gradle.kts
git commit -m "watchwords: ship 0.3.0"
```

---

## Self-Review

Spec coverage check:
- §Decisions captured Q1 (feed) → Task 10 ✓
- §Decisions captured Q2 (word-boundary) → Task 3 ✓
- §Decisions captured Q3 (per-chat exclude + skip self + muted excluded entirely) → Tasks 5, 9, 13 ✓
- §Decisions captured Q4 (auto-backfill on add) → Task 5 (`startBackfill` from `add`) + Task 6 ✓
- §Decisions captured Q5 (per-term notify) → Task 1 (entity), Task 5 (setNotify), Task 11 (UI) ✓
- §Decisions captured Q6.1 (sync, default off) → Task 8 (prefs flag), Task 14 (push helpers), Task 11 (toggle UI) ✓
- §Decisions captured Q6.2 (1000-hits cap + clear button) → Task 5 (pruneIfOver), Task 11 (delete dialog) ✓
- §Decisions captured Q6.3 (manage inside feed via sheet) → Tasks 10, 11 ✓
- §Architecture (UI-listener integration) → Task 9 ✓
- §Data model — three entities, indices, hits PK, migration — Tasks 1, 2 ✓
- §Watchwords facade — Task 5 ✓
- §Runtime flow — Task 9 ✓
- §matchesWordBoundary — Task 3 ✓
- §Backfill flow — Tasks 5, 6 ✓
- §UI surfaces — Tasks 10, 11, 12, 13, 15 ✓
- §Settings sync (buckets + push helpers + apply branches) — Task 14 ✓
- §Performance (early-exit, in-memory excludes/terms StateFlows, lazy prune, bounded backfill) — Task 5 (all four) ✓
- §Edge cases (term deleted mid-backfill, applyEntry no-backfill, lazy prune buffer, etc.) — Task 5 + Task 14 ✓
- §Testing — Tasks 3, 4 (unit tests) + Task 16 (manual integration smoke) ✓

Type consistency check: `Watchwords.add` returns `Long`; called from `ManageTermsSheet` only as fire-and-forget — fine. `streamHitsForTerm`/`streamAllHits` both return `Flow<List<WatchwordHitEntity>>` — consistent in `WatchwordsScreen`. `WatchwordChange` sealed class shape matches between Task 5 (definition) and Task 8 (consumption). `pushWatchwordEntry(term: WatchwordEntity)` — Task 8 calls it with `evt.term: WatchwordEntity`; Task 14 defines it with the same signature ✓.

Placeholder scan: no TBD/TODO/"similar to Task N"/etc. found. Each step has full file paths, full code, full commands.
