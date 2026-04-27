# Daily Digest Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a daily morning brief — at the user-configured time, generate and notify the user with a curated digest of unread + watchword hits + @mentions, an optional AI summary, and today's weather.

**Architecture:** AlarmManager-driven (`setAndAllowWhileIdle`) fires a `BroadcastReceiver` daily; the receiver hands off to an Application-level `DailyDigest` facade that queries the active ship's Room DB, pulls weather from open-meteo.com, optionally calls the AI client, persists to a new `daily_digests` table, and posts a notification. A new `DailyDigestScreen` renders the result. Manifest receivers handle reboot and timezone-change re-arms.

**Tech Stack:** Kotlin, Android (minSdk 26 / target 35), Jetpack Compose, Room 2.6.1, OkHttp 4.12, kotlinx-serialization 1.7.2, kotlinx-coroutines 1.8.1, JUnit 4, AlarmManager, NotificationCompat. Existing AI infra: `AiClient`, `AiSettings`, `AiFeatures`. Existing location infra: `ui/LocationShare.kt`. Existing settings sync: `urbit/SettingsSync.kt`.

**Spec:** `docs/superpowers/specs/2026-04-26-daily-digest-design.md`

---

## File Structure

### Created files

| File | Responsibility |
|---|---|
| `app/src/main/java/io/nisfeb/talon/data/DailyDigestEntity.kt` | Room entity + `DigestItem` + `Bucket` + `WeatherToday` data classes |
| `app/src/main/java/io/nisfeb/talon/data/DailyDigestDao.kt` | DAO with upsert / get / streamLatestForShip / pruneOlderThan |
| `app/src/main/java/io/nisfeb/talon/ai/DailyDigestSchedule.kt` | Pure `nextFireMs` math |
| `app/src/main/java/io/nisfeb/talon/ai/DailyDigestMentionMatcher.kt` | Pure patp-bounded substring detection |
| `app/src/main/java/io/nisfeb/talon/ai/DailyDigestPrompt.kt` | Pure transcript formatter |
| `app/src/main/java/io/nisfeb/talon/ai/DailyDigestSelector.kt` | Bucketing + cross-bucket dedupe (suspends — calls DAO) |
| `app/src/main/java/io/nisfeb/talon/ai/DailyDigest.kt` | Application-level facade: scheduleNext, generateAndNotify, state |
| `app/src/main/java/io/nisfeb/talon/ai/DailyDigestSettings.kt` | SharedPrefs-backed enabled/time settings |
| `app/src/main/java/io/nisfeb/talon/ai/WeatherClient.kt` | open-meteo.com fetcher + JSON parser |
| `app/src/main/java/io/nisfeb/talon/DigestAlarmReceiver.kt` | BroadcastReceiver that runs the pipeline at fire time |
| `app/src/main/java/io/nisfeb/talon/BootReceiver.kt` | BroadcastReceiver for BOOT_COMPLETED + TIMEZONE_CHANGED |
| `app/src/main/java/io/nisfeb/talon/ui/screens/DailyDigestScreen.kt` | Compose screen |
| `app/src/test/kotlin/io/nisfeb/talon/ai/DailyDigestScheduleTest.kt` | unit tests |
| `app/src/test/kotlin/io/nisfeb/talon/ai/DailyDigestMentionMatcherTest.kt` | unit tests |
| `app/src/test/kotlin/io/nisfeb/talon/ai/DailyDigestPromptTest.kt` | unit tests |
| `app/src/test/kotlin/io/nisfeb/talon/ai/DailyDigestSelectorTest.kt` | unit tests |
| `app/src/test/kotlin/io/nisfeb/talon/ai/WeatherClientParseTest.kt` | unit tests |
| `app/src/test/kotlin/io/nisfeb/talon/ai/DailyDigestFuzzTest.kt` | fuzz tests |

### Modified files

| File | Change |
|---|---|
| `app/src/main/AndroidManifest.xml` | RECEIVE_BOOT_COMPLETED permission + 2 receivers |
| `app/src/main/java/io/nisfeb/talon/data/AppDatabase.kt` | Register entity, version 26→27, MIGRATION_26_27, dailyDigests() abstract |
| `app/src/main/java/io/nisfeb/talon/data/MessageDao.kt` | New query: `messagesForUnreadDigest` and `messagesForMentions` |
| `app/src/main/java/io/nisfeb/talon/Notifications.kt` | New channel + `showDailyDigest` |
| `app/src/main/java/io/nisfeb/talon/TalonApplication.kt` | Instantiate `dailyDigest`, `dailyDigestSettings`; wire onChange; call `scheduleNext()` |
| `app/src/main/java/io/nisfeb/talon/MainActivity.kt` | Handle "open-digest" intent extra |
| `app/src/main/java/io/nisfeb/talon/ui/TalonApp.kt` | Nav route to `DailyDigestScreen`; home menu entry |
| `app/src/main/java/io/nisfeb/talon/ai/AiSettings.kt` | Add `dailyDigestEnabled: Boolean = true` to Config |
| `app/src/main/java/io/nisfeb/talon/ui/screens/SettingsScreen.kt` | "Daily digest" section: enable toggle, time picker, status, "Test now" |
| `app/src/main/java/io/nisfeb/talon/urbit/SettingsSync.kt` | New `BUCKET_DAILY_DIGEST`, push/clear helpers |
| `app/build.gradle.kts` | Bump versionCode + versionName |
| `scripts/mutate/mutate.sh` | Add DailyDigest*.kt to default targets |

---

## Task 1: Data layer (entity + DAO + migration)

**Files:**
- Create: `app/src/main/java/io/nisfeb/talon/data/DailyDigestEntity.kt`
- Create: `app/src/main/java/io/nisfeb/talon/data/DailyDigestDao.kt`
- Modify: `app/src/main/java/io/nisfeb/talon/data/AppDatabase.kt`

- [ ] **Step 1: Create DailyDigestEntity.kt with the entity + serializable shapes**

```kotlin
package io.nisfeb.talon.data

import androidx.room.Entity
import kotlinx.serialization.Serializable

/**
 * Today's daily digest snapshot. Generated once per day per active
 * ship at the user-configured time. Overwritten on re-fire — see
 * spec §Persistence.
 */
@Entity(
    tableName = "daily_digests",
    primaryKeys = ["ship", "dateLocal"],
)
data class DailyDigestEntity(
    val ship: String,
    val dateLocal: String,        // "yyyy-MM-dd" in user's local TZ
    val generatedAtMs: Long,
    val summaryText: String?,     // null when AI off / failed
    val itemsJson: String,        // serialized List<DigestItem>
    val weatherJson: String?,     // serialized WeatherToday; null on failure
)

@Serializable
data class DigestItem(
    val whom: String,
    val postId: String,
    val authorPatp: String,
    val sentMs: Long,
    val bucket: Bucket,
    val snippet: String,
    /** Watchword bucket only — the term that matched. Null otherwise. */
    val matchedTerm: String? = null,
)

@Serializable
enum class Bucket { MENTION, WATCHWORD, UNREAD }

@Serializable
data class WeatherToday(
    val highC: Double,
    val lowC: Double,
    val conditionCode: Int,       // open-meteo WMO code
    val conditionLabel: String,
    val emoji: String,
)
```

- [ ] **Step 2: Create DailyDigestDao.kt**

```kotlin
package io.nisfeb.talon.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DailyDigestDao {

    @Upsert
    suspend fun upsert(d: DailyDigestEntity)

    @Query("SELECT * FROM daily_digests WHERE ship = :ship AND dateLocal = :date LIMIT 1")
    suspend fun get(ship: String, date: String): DailyDigestEntity?

    @Query("SELECT * FROM daily_digests WHERE ship = :ship ORDER BY dateLocal DESC LIMIT 1")
    fun streamLatestForShip(ship: String): Flow<DailyDigestEntity?>

    @Query("DELETE FROM daily_digests WHERE dateLocal < :keepFromDate")
    suspend fun pruneOlderThan(keepFromDate: String)
}
```

- [ ] **Step 3: Register entity, bump version, add migration in AppDatabase.kt**

In the `@Database` block, add `DailyDigestEntity::class` to the entities list. Bump `version = 26` to `version = 27`. Add `abstract fun dailyDigests(): DailyDigestDao` next to the other DAO accessors.

Add this migration above the `build` companion fun:

```kotlin
private val MIGRATION_26_27 = object : Migration(26, 27) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS daily_digests (
                ship TEXT NOT NULL,
                dateLocal TEXT NOT NULL,
                generatedAtMs INTEGER NOT NULL,
                summaryText TEXT,
                itemsJson TEXT NOT NULL,
                weatherJson TEXT,
                PRIMARY KEY (ship, dateLocal)
            )
            """.trimIndent()
        )
    }
}
```

In `addMigrations(...)`, append `MIGRATION_26_27` to the list.

- [ ] **Step 4: Build the project to confirm Room + serialization compile**

Run: `JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 ./gradlew :app:assembleDebug 2>&1 | tail -40`

Expected: BUILD SUCCESSFUL. If KSP complains about `@Serializable` on an enum, ensure `kotlinx.serialization` is on the classpath via the existing `kotlin-serialization` plugin (already enabled).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/nisfeb/talon/data/DailyDigestEntity.kt \
        app/src/main/java/io/nisfeb/talon/data/DailyDigestDao.kt \
        app/src/main/java/io/nisfeb/talon/data/AppDatabase.kt
git commit -m "daily-digest: entity + DAO + migration v26→v27"
```

---

## Task 2: DailyDigestSchedule (pure)

**Files:**
- Create: `app/src/main/java/io/nisfeb/talon/ai/DailyDigestSchedule.kt`
- Create: `app/src/test/kotlin/io/nisfeb/talon/ai/DailyDigestScheduleTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package io.nisfeb.talon.ai

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class DailyDigestScheduleTest {

    private val nyc = ZoneId.of("America/New_York")

    private fun at(zone: ZoneId, year: Int, month: Int, day: Int, h: Int, m: Int): Instant =
        ZonedDateTime.of(year, month, day, h, m, 0, 0, zone).toInstant()

    @Test fun `next is later today when target hasn't passed`() {
        val now = at(nyc, 2026, 4, 26, 5, 30)
        val next = DailyDigestSchedule.nextFireMs(now, 6, 0, nyc)
        assertEquals(at(nyc, 2026, 4, 26, 6, 0).toEpochMilli(), next)
    }

    @Test fun `next is tomorrow when target has already passed`() {
        val now = at(nyc, 2026, 4, 26, 7, 0)
        val next = DailyDigestSchedule.nextFireMs(now, 6, 0, nyc)
        assertEquals(at(nyc, 2026, 4, 27, 6, 0).toEpochMilli(), next)
    }

    @Test fun `next is tomorrow when now equals target`() {
        // Spec §Schedule: equal-to-now counts as past, fire tomorrow.
        val now = at(nyc, 2026, 4, 26, 6, 0)
        val next = DailyDigestSchedule.nextFireMs(now, 6, 0, nyc)
        assertEquals(at(nyc, 2026, 4, 27, 6, 0).toEpochMilli(), next)
    }

    @Test fun `midnight rollover`() {
        val now = at(nyc, 2026, 4, 26, 23, 30)
        val next = DailyDigestSchedule.nextFireMs(now, 0, 5, nyc)
        assertEquals(at(nyc, 2026, 4, 27, 0, 5).toEpochMilli(), next)
    }

    @Test fun `DST forward — 2am does not exist on spring-forward day`() {
        // 2026-03-08 is the US DST forward day; 2:00 EST jumps to 3:00 EDT.
        // ZonedDateTime resolves a non-existent local time to the next valid
        // instant. We just want this not to throw and to be a real instant
        // strictly after `now`.
        val now = at(nyc, 2026, 3, 7, 23, 0)
        val next = DailyDigestSchedule.nextFireMs(now, 2, 30, nyc)
        assert(next > now.toEpochMilli())
    }

    @Test fun `DST back — 1_30am happens twice on fall-back day`() {
        // 2026-11-01 is the US DST back day. 1:30 AM occurs twice (EDT then
        // EST). ZonedDateTime.of resolves to the EARLIER (EDT) by default.
        val now = at(nyc, 2026, 11, 1, 0, 30)
        val next = DailyDigestSchedule.nextFireMs(now, 1, 30, nyc)
        assert(next > now.toEpochMilli())
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 ./gradlew :app:testDebugUnitTest --tests "io.nisfeb.talon.ai.DailyDigestScheduleTest" 2>&1 | tail -20`

Expected: FAIL with "Unresolved reference: DailyDigestSchedule".

- [ ] **Step 3: Create DailyDigestSchedule.kt**

```kotlin
package io.nisfeb.talon.ai

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Pure scheduling math for the daily digest alarm. See spec §Schedule.
 *
 * No side effects — given the inputs it always returns the same fire
 * time. The receiver / TalonApplication call this whenever they need
 * to (re)arm the alarm.
 */
object DailyDigestSchedule {

    /**
     * Next epoch-ms at which the daily digest should fire.
     *
     * If today's `(hourOfDay, minuteOfDay)` in [zone] is strictly after
     * [now], that's the answer. Otherwise add a day. Equal-to-now counts
     * as past so we don't fire twice on a re-arm at exactly fire time.
     *
     * DST behavior comes from `ZonedDateTime.of`: spring-forward times
     * resolve to the next valid local instant; fall-back ambiguous
     * times resolve to the earlier offset.
     */
    fun nextFireMs(now: Instant, hourOfDay: Int, minuteOfDay: Int, zone: ZoneId): Long {
        val today = LocalDate.now(now.atZone(zone).toLocalDate().run {
            // Use the date in zone, not UTC, so configured-time-on-todays-date
            // means today in that timezone.
            zone
        })
        val nowZdt = now.atZone(zone)
        val candidate = LocalDateTime.of(
            nowZdt.toLocalDate(), java.time.LocalTime.of(hourOfDay, minuteOfDay),
        ).atZone(zone)
        val fire = if (candidate.toInstant() > now) candidate
            else candidate.plusDays(1)
        return fire.toInstant().toEpochMilli()
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 ./gradlew :app:testDebugUnitTest --tests "io.nisfeb.talon.ai.DailyDigestScheduleTest" 2>&1 | tail -10`

Expected: BUILD SUCCESSFUL with 6 tests passing.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/nisfeb/talon/ai/DailyDigestSchedule.kt \
        app/src/test/kotlin/io/nisfeb/talon/ai/DailyDigestScheduleTest.kt
git commit -m "daily-digest: nextFireMs schedule helper + tests"
```

---

## Task 3: DailyDigestMentionMatcher (pure)

**Files:**
- Create: `app/src/main/java/io/nisfeb/talon/ai/DailyDigestMentionMatcher.kt`
- Create: `app/src/test/kotlin/io/nisfeb/talon/ai/DailyDigestMentionMatcherTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package io.nisfeb.talon.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyDigestMentionMatcherTest {

    @Test fun `simple mention matches`() {
        assertTrue(DailyDigestMentionMatcher.containsMention(
            "hey ~mister-foo, check this", "mister-foo"))
    }

    @Test fun `no mention returns false`() {
        assertFalse(DailyDigestMentionMatcher.containsMention(
            "no mentions here", "mister-foo"))
    }

    @Test fun `prefix collision does NOT match`() {
        // ~mister-foo is a prefix of ~mister-foo-bar — must not match.
        assertFalse(DailyDigestMentionMatcher.containsMention(
            "ping ~mister-foo-bar please", "mister-foo"))
    }

    @Test fun `prefix collision with longer continuation does NOT match`() {
        assertFalse(DailyDigestMentionMatcher.containsMention(
            "saw ~mister-botter-dozzod-nisfeb today", "mister-botter"))
    }

    @Test fun `mention at start of haystack matches`() {
        assertTrue(DailyDigestMentionMatcher.containsMention(
            "~mister-foo: hello", "mister-foo"))
    }

    @Test fun `mention at end of haystack matches`() {
        assertTrue(DailyDigestMentionMatcher.containsMention(
            "tagging ~mister-foo", "mister-foo"))
    }

    @Test fun `multiple mentions matches once`() {
        assertTrue(DailyDigestMentionMatcher.containsMention(
            "~mister-foo and ~mister-foo again", "mister-foo"))
    }

    @Test fun `mention without leading tilde does NOT match`() {
        assertFalse(DailyDigestMentionMatcher.containsMention(
            "user mister-foo signed up", "mister-foo"))
    }

    @Test fun `case insensitive`() {
        assertTrue(DailyDigestMentionMatcher.containsMention(
            "ping ~MISTER-FOO", "mister-foo"))
    }

    @Test fun `empty patp returns false`() {
        assertFalse(DailyDigestMentionMatcher.containsMention(
            "anything ~", ""))
    }

    @Test fun `empty haystack returns false`() {
        assertFalse(DailyDigestMentionMatcher.containsMention("", "mister-foo"))
    }

    @Test fun `mention followed by punctuation matches`() {
        assertTrue(DailyDigestMentionMatcher.containsMention(
            "~mister-foo!", "mister-foo"))
        assertTrue(DailyDigestMentionMatcher.containsMention(
            "(~mister-foo)", "mister-foo"))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 ./gradlew :app:testDebugUnitTest --tests "io.nisfeb.talon.ai.DailyDigestMentionMatcherTest" 2>&1 | tail -20`

Expected: FAIL with "Unresolved reference: DailyDigestMentionMatcher".

- [ ] **Step 3: Create DailyDigestMentionMatcher.kt**

```kotlin
package io.nisfeb.talon.ai

/**
 * Patp-bounded `~ourpatp` substring detection. See spec §Generation/Mentions.
 *
 * "Patp boundary" means the character following the patp is NOT a
 * letter or `-`. So `~mister-foo` matches `hi ~mister-foo!` but NOT
 * `~mister-foo-bar` — otherwise `~mister-botter` would match every
 * one of `~mister-botter-dozzod-nisfeb`'s messages.
 */
object DailyDigestMentionMatcher {

    fun containsMention(haystack: String, patp: String): Boolean {
        if (patp.isEmpty() || haystack.isEmpty()) return false
        val needle = "~" + patp.lowercase()
        val h = haystack.lowercase()
        var i = 0
        while (true) {
            val found = h.indexOf(needle, startIndex = i)
            if (found < 0) return false
            val end = found + needle.length
            val after = if (end >= h.length) ' ' else h[end]
            if (!after.isLetter() && after != '-') return true
            i = found + 1
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 ./gradlew :app:testDebugUnitTest --tests "io.nisfeb.talon.ai.DailyDigestMentionMatcherTest" 2>&1 | tail -10`

Expected: 12 tests passing.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/nisfeb/talon/ai/DailyDigestMentionMatcher.kt \
        app/src/test/kotlin/io/nisfeb/talon/ai/DailyDigestMentionMatcherTest.kt
git commit -m "daily-digest: patp-bounded mention matcher + tests"
```

---

## Task 4: DailyDigestPrompt formatter (pure)

**Files:**
- Create: `app/src/main/java/io/nisfeb/talon/ai/DailyDigestPrompt.kt`
- Create: `app/src/test/kotlin/io/nisfeb/talon/ai/DailyDigestPromptTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package io.nisfeb.talon.ai

import io.nisfeb.talon.data.Bucket
import io.nisfeb.talon.data.DigestItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyDigestPromptTest {

    private fun item(
        bucket: Bucket,
        author: String = "~sampel",
        snippet: String = "hello",
        sentMs: Long = 1_000L,
    ) = DigestItem(
        whom = "~h/s",
        postId = "0v1.abc",
        authorPatp = author,
        sentMs = sentMs,
        bucket = bucket,
        snippet = snippet,
    )

    @Test fun `empty list returns explicit placeholder`() {
        val out = DailyDigestPrompt.format(emptyList()) { it }
        assertEquals("(no messages)", out)
    }

    @Test fun `groups by bucket in priority order`() {
        val out = DailyDigestPrompt.format(
            listOf(
                item(Bucket.UNREAD, snippet = "u1"),
                item(Bucket.MENTION, snippet = "m1"),
                item(Bucket.WATCHWORD, snippet = "w1"),
            )
        ) { it }
        // Bucket headers must appear in MENTION → WATCHWORD → UNREAD order.
        val mentionIdx = out.indexOf("[mentions]")
        val wIdx = out.indexOf("[watchwords]")
        val uIdx = out.indexOf("[unread]")
        assertTrue(mentionIdx in 0 until wIdx)
        assertTrue(wIdx < uIdx)
    }

    @Test fun `truncates long snippets to 200 chars per line`() {
        val long = "x".repeat(500)
        val out = DailyDigestPrompt.format(listOf(item(Bucket.MENTION, snippet = long))) { it }
        // No single line in the output can exceed ~250 chars (200 snippet
        // + author + bucket prefix). Use 260 as the safety bound.
        for (line in out.lines()) {
            assertTrue("line too long: ${line.length}", line.length <= 260)
        }
    }

    @Test fun `display-name resolver is applied`() {
        val out = DailyDigestPrompt.format(listOf(item(Bucket.UNREAD, author = "~sampel"))) {
            if (it == "~sampel") "Sampel" else it
        }
        assertTrue(out.contains("Sampel"))
        assertTrue(!out.contains("~sampel"))
    }

    @Test fun `omits empty bucket headers`() {
        val out = DailyDigestPrompt.format(listOf(item(Bucket.MENTION))) { it }
        assertTrue(out.contains("[mentions]"))
        assertTrue(!out.contains("[watchwords]"))
        assertTrue(!out.contains("[unread]"))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 ./gradlew :app:testDebugUnitTest --tests "io.nisfeb.talon.ai.DailyDigestPromptTest" 2>&1 | tail -20`

Expected: FAIL with "Unresolved reference: DailyDigestPrompt".

- [ ] **Step 3: Create DailyDigestPrompt.kt**

```kotlin
package io.nisfeb.talon.ai

import io.nisfeb.talon.data.Bucket
import io.nisfeb.talon.data.DigestItem

/**
 * Turn a deduped list of digest items into a transcript string for
 * the AI summary prompt. See spec §Generation/AI summary.
 *
 * Format: bucket header lines followed by `author: snippet` lines.
 * Headers are only emitted for non-empty buckets so the model isn't
 * told about empty sections.
 */
object DailyDigestPrompt {

    private const val SNIPPET_MAX = 200

    fun format(
        items: List<DigestItem>,
        displayName: (String) -> String,
    ): String {
        if (items.isEmpty()) return "(no messages)"
        val byBucket = items.groupBy { it.bucket }
        val sb = StringBuilder()
        for (bucket in listOf(Bucket.MENTION, Bucket.WATCHWORD, Bucket.UNREAD)) {
            val rows = byBucket[bucket] ?: continue
            sb.append("[")
            sb.append(bucketHeader(bucket))
            sb.append("]\n")
            for (r in rows) {
                val name = displayName(r.authorPatp)
                val snippet = r.snippet.replace('\n', ' ').take(SNIPPET_MAX)
                sb.append(name).append(": ").append(snippet).append('\n')
            }
        }
        return sb.toString().trimEnd('\n')
    }

    private fun bucketHeader(b: Bucket) = when (b) {
        Bucket.MENTION -> "mentions"
        Bucket.WATCHWORD -> "watchwords"
        Bucket.UNREAD -> "unread"
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 ./gradlew :app:testDebugUnitTest --tests "io.nisfeb.talon.ai.DailyDigestPromptTest" 2>&1 | tail -10`

Expected: 5 tests passing.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/nisfeb/talon/ai/DailyDigestPrompt.kt \
        app/src/test/kotlin/io/nisfeb/talon/ai/DailyDigestPromptTest.kt
git commit -m "daily-digest: AI transcript formatter + tests"
```

---

## Task 5: New DAO queries for selector

**Files:**
- Modify: `app/src/main/java/io/nisfeb/talon/data/MessageDao.kt`

The selector needs two new queries: one that returns recent messages for a window/author scan (for mentions), and one that returns the newest N messages for a specific `whom` within a window (for unread).

- [ ] **Step 1: Open MessageDao.kt and add new queries**

Append these methods to the `MessageDao` interface (location: alongside the existing `messages` queries):

```kotlin
/**
 * Daily digest: scan recent messages for @-mention detection. Caller
 * does the patp-boundary substring check on `contentJson`-derived
 * plaintext. Bounded by the 24h window so the scan stays small.
 */
@Query("""
    SELECT * FROM messages
    WHERE sentMs >= :windowStartMs
      AND sentMs < :windowEndMs
      AND author != :ourPatp
    ORDER BY sentMs DESC
    LIMIT :limit
""")
suspend fun candidatesForMentionScan(
    ourPatp: String,
    windowStartMs: Long,
    windowEndMs: Long,
    limit: Int,
): List<MessageEntity>

/**
 * Daily digest: newest messages from one chat in the window, excluding
 * self. Used to assemble the unread bucket — caller knows from the
 * `unreads` table how many messages this chat has unread, takes
 * min(count, returnedSize) of these.
 */
@Query("""
    SELECT * FROM messages
    WHERE whom = :whom
      AND sentMs >= :windowStartMs
      AND sentMs < :windowEndMs
      AND author != :ourPatp
    ORDER BY sentMs DESC
    LIMIT :limit
""")
suspend fun newestInChatForWindow(
    whom: String,
    ourPatp: String,
    windowStartMs: Long,
    windowEndMs: Long,
    limit: Int,
): List<MessageEntity>
```

- [ ] **Step 2: Build to confirm Room compiles the queries**

Run: `JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 ./gradlew :app:assembleDebug 2>&1 | tail -20`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/io/nisfeb/talon/data/MessageDao.kt
git commit -m "daily-digest: MessageDao queries for mention + unread buckets"
```

---

## Task 6: DailyDigestSelector

**Files:**
- Create: `app/src/main/java/io/nisfeb/talon/ai/DailyDigestSelector.kt`
- Create: `app/src/test/kotlin/io/nisfeb/talon/ai/DailyDigestSelectorTest.kt`

The selector takes already-fetched lists (it does NOT call DAO directly — that keeps it pure for unit testing). The DailyDigest facade (Task 9) will fetch from DAO and pass the lists in.

- [ ] **Step 1: Write the failing tests**

```kotlin
package io.nisfeb.talon.ai

import io.nisfeb.talon.data.Bucket
import io.nisfeb.talon.data.DigestItem
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.data.WatchwordHitEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyDigestSelectorTest {

    private fun msg(
        whom: String = "~h/s",
        id: String = "0v1.aaa",
        author: String = "~sampel",
        sentMs: Long = 1_000,
        contentJson: String = """[{"inline":[{"text":"hi"}]}]""",
    ) = MessageEntity(
        whom = whom,
        id = id,
        author = author,
        sentMs = sentMs,
        contentJson = contentJson,
        kind = "/chat",
        parentId = null,
    )

    private fun hit(
        term: String = "mars",
        whom: String = "~h/s",
        postId: String = "0v1.aaa",
        sentMs: Long = 1_000,
        snippet: String = "Mars rocks",
    ) = WatchwordHitEntity(
        term = term, whom = whom, postId = postId, sentMs = sentMs, snippet = snippet,
    )

    @Test fun `mentions bucket detected via patp-boundary matcher`() {
        val items = DailyDigestSelector.assemble(
            ourPatp = "mister-foo",
            mentionCandidates = listOf(
                msg(id = "0v1.aaa", contentJson = "hi ~mister-foo see this"),
                msg(id = "0v1.bbb", contentJson = "hi ~mister-foo-bar see this"),
            ),
            mentionPlainText = mapOf(
                "0v1.aaa" to "hi ~mister-foo see this",
                "0v1.bbb" to "hi ~mister-foo-bar see this",
            ),
            watchwordHits = emptyList(),
            unreadCandidates = emptyMap(),
            unreadCounts = emptyMap(),
        )
        assertEquals(1, items.size)
        assertEquals("0v1.aaa", items[0].postId)
        assertEquals(Bucket.MENTION, items[0].bucket)
    }

    @Test fun `watchword hits dedupe within bucket by whom postId`() {
        val items = DailyDigestSelector.assemble(
            ourPatp = "mister-foo",
            mentionCandidates = emptyList(),
            mentionPlainText = emptyMap(),
            watchwordHits = listOf(
                hit(term = "mars", whom = "~h/s", postId = "0v1.aaa"),
                hit(term = "rocket", whom = "~h/s", postId = "0v1.aaa"),
                hit(term = "mars", whom = "~h/s", postId = "0v1.bbb"),
            ),
            unreadCandidates = emptyMap(),
            unreadCounts = emptyMap(),
        )
        // Two unique (whom, postId): aaa kept once, bbb once.
        assertEquals(2, items.size)
        assertTrue(items.all { it.bucket == Bucket.WATCHWORD })
        // First hit's term wins for the de-duped entry.
        assertEquals("mars", items.first { it.postId == "0v1.aaa" }.matchedTerm)
    }

    @Test fun `mention takes precedence over watchword and unread`() {
        val msg1 = msg(id = "0v1.shared", contentJson = "hi ~mister-foo")
        val items = DailyDigestSelector.assemble(
            ourPatp = "mister-foo",
            mentionCandidates = listOf(msg1),
            mentionPlainText = mapOf("0v1.shared" to "hi ~mister-foo"),
            watchwordHits = listOf(hit(postId = "0v1.shared")),
            unreadCandidates = mapOf("~h/s" to listOf(msg1)),
            unreadCounts = mapOf("~h/s" to 1),
        )
        assertEquals(1, items.size)
        assertEquals(Bucket.MENTION, items[0].bucket)
    }

    @Test fun `watchword takes precedence over unread`() {
        val msg1 = msg(id = "0v1.shared")
        val items = DailyDigestSelector.assemble(
            ourPatp = "mister-foo",
            mentionCandidates = emptyList(),
            mentionPlainText = emptyMap(),
            watchwordHits = listOf(hit(postId = "0v1.shared")),
            unreadCandidates = mapOf("~h/s" to listOf(msg1)),
            unreadCounts = mapOf("~h/s" to 1),
        )
        assertEquals(1, items.size)
        assertEquals(Bucket.WATCHWORD, items[0].bucket)
    }

    @Test fun `unread bucket caps to min of count and returned`() {
        val msgs = (1..10).map { msg(id = "0v1.${it}aaa", sentMs = it.toLong()) }
        val items = DailyDigestSelector.assemble(
            ourPatp = "mister-foo",
            mentionCandidates = emptyList(),
            mentionPlainText = emptyMap(),
            watchwordHits = emptyList(),
            unreadCandidates = mapOf("~h/s" to msgs),
            unreadCounts = mapOf("~h/s" to 3),
        )
        // Only the newest 3 messages from the chat (count=3).
        assertEquals(3, items.size)
        assertTrue(items.all { it.bucket == Bucket.UNREAD })
    }

    @Test fun `unread cap of 100 across chats`() {
        val chats = (1..5).associate { c ->
            "~chat$c" to (1..30).map { i ->
                msg(whom = "~chat$c", id = "0v1.$c-$i", sentMs = (c * 100 + i).toLong())
            }
        }
        val counts = chats.keys.associateWith { 30 }
        val items = DailyDigestSelector.assemble(
            ourPatp = "mister-foo",
            mentionCandidates = emptyList(),
            mentionPlainText = emptyMap(),
            watchwordHits = emptyList(),
            unreadCandidates = chats,
            unreadCounts = counts,
        )
        // 5 chats × 30 unread = 150 candidates; cap is 100.
        assertEquals(100, items.size)
    }

    @Test fun `empty input returns empty list`() {
        val items = DailyDigestSelector.assemble(
            ourPatp = "mister-foo",
            mentionCandidates = emptyList(),
            mentionPlainText = emptyMap(),
            watchwordHits = emptyList(),
            unreadCandidates = emptyMap(),
            unreadCounts = emptyMap(),
        )
        assertTrue(items.isEmpty())
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 ./gradlew :app:testDebugUnitTest --tests "io.nisfeb.talon.ai.DailyDigestSelectorTest" 2>&1 | tail -20`

Expected: FAIL with "Unresolved reference: DailyDigestSelector".

- [ ] **Step 3: Create DailyDigestSelector.kt**

```kotlin
package io.nisfeb.talon.ai

import io.nisfeb.talon.data.Bucket
import io.nisfeb.talon.data.DigestItem
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.data.WatchwordHitEntity

/**
 * Pure bucket assembly + cross-bucket dedupe. The DailyDigest facade
 * fetches all the inputs from DAO + StoryCache and hands them in;
 * this function does the rest. Pure-function shape makes the logic
 * unit-testable without an in-memory database.
 *
 * See spec §Generation pipeline / steps 3 + 4.
 */
object DailyDigestSelector {

    private const val MENTION_CAP = 50
    private const val WATCHWORD_CAP = 50
    private const val UNREAD_CAP = 100
    private const val SNIPPET_MAX = 200

    /**
     * @param mentionCandidates messages already pre-filtered to "in window,
     *  not authored by us". The selector applies the patp-boundary check.
     * @param mentionPlainText map from postId → already-extracted plain
     *  text (caller uses StoryCache.textFor). Extracted at the facade
     *  level because StoryCache is not pure.
     * @param watchwordHits in-window watchword hits.
     * @param unreadCandidates per-chat newest messages in the window
     *  (already excluded muted / watchword-excluded chats by the caller).
     * @param unreadCounts per-chat unread count from `unreads` table.
     */
    fun assemble(
        ourPatp: String,
        mentionCandidates: List<MessageEntity>,
        mentionPlainText: Map<String, String>,
        watchwordHits: List<WatchwordHitEntity>,
        unreadCandidates: Map<String, List<MessageEntity>>,
        unreadCounts: Map<String, Int>,
    ): List<DigestItem> {
        val out = LinkedHashMap<Pair<String, String>, DigestItem>()

        // 1. Mentions (highest priority — go in first so dedupe keeps them)
        var mentionAdded = 0
        for (m in mentionCandidates) {
            if (mentionAdded >= MENTION_CAP) break
            val text = mentionPlainText[m.id] ?: continue
            if (!DailyDigestMentionMatcher.containsMention(text, ourPatp)) continue
            val key = m.whom to m.id
            out[key] = DigestItem(
                whom = m.whom,
                postId = m.id,
                authorPatp = m.author,
                sentMs = m.sentMs,
                bucket = Bucket.MENTION,
                snippet = text.take(SNIPPET_MAX),
            )
            mentionAdded++
        }

        // 2. Watchword hits (dedupe within bucket by whom+postId; keep first term)
        val seenHits = HashSet<Pair<String, String>>()
        var wAdded = 0
        for (h in watchwordHits) {
            if (wAdded >= WATCHWORD_CAP) break
            val key = h.whom to h.postId
            if (!seenHits.add(key)) continue        // already in this bucket
            if (out.containsKey(key)) continue       // mention beat us
            out[key] = DigestItem(
                whom = h.whom,
                postId = h.postId,
                authorPatp = "",                     // unknown from hits table
                sentMs = h.sentMs,
                bucket = Bucket.WATCHWORD,
                snippet = h.snippet,
                matchedTerm = h.term,
            )
            wAdded++
        }

        // 3. Unread (lowest priority)
        var uAdded = 0
        for ((whom, msgs) in unreadCandidates) {
            if (uAdded >= UNREAD_CAP) break
            val count = unreadCounts[whom] ?: 0
            if (count <= 0) continue
            val take = minOf(count, msgs.size)
            for (i in 0 until take) {
                if (uAdded >= UNREAD_CAP) break
                val m = msgs[i]
                val key = m.whom to m.id
                if (out.containsKey(key)) continue
                out[key] = DigestItem(
                    whom = m.whom,
                    postId = m.id,
                    authorPatp = m.author,
                    sentMs = m.sentMs,
                    bucket = Bucket.UNREAD,
                    snippet = m.contentJson.take(SNIPPET_MAX),
                )
                uAdded++
            }
        }

        return out.values.toList()
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 ./gradlew :app:testDebugUnitTest --tests "io.nisfeb.talon.ai.DailyDigestSelectorTest" 2>&1 | tail -10`

Expected: 7 tests passing.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/nisfeb/talon/ai/DailyDigestSelector.kt \
        app/src/test/kotlin/io/nisfeb/talon/ai/DailyDigestSelectorTest.kt
git commit -m "daily-digest: bucket selector + dedupe + tests"
```

---

## Task 7: WeatherClient (parser + fetcher)

**Files:**
- Create: `app/src/main/java/io/nisfeb/talon/ai/WeatherClient.kt`
- Create: `app/src/test/kotlin/io/nisfeb/talon/ai/WeatherClientParseTest.kt`

- [ ] **Step 1: Write the failing parse tests**

```kotlin
package io.nisfeb.talon.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeatherClientParseTest {

    private val SAMPLE = """
        {
          "latitude": 40.71,
          "longitude": -74.01,
          "daily": {
            "time": ["2026-04-26"],
            "temperature_2m_max": [18.3],
            "temperature_2m_min": [9.4],
            "weather_code": [3]
          }
        }
    """.trimIndent()

    @Test fun `parses open-meteo daily payload`() {
        val w = WeatherClient.parseToday(SAMPLE)!!
        assertEquals(18.3, w.highC, 0.01)
        assertEquals(9.4, w.lowC, 0.01)
        assertEquals(3, w.conditionCode)
        // WMO 3 = "Overcast" per open-meteo docs.
        assertEquals("Overcast", w.conditionLabel)
        assertEquals("☁️", w.emoji)
    }

    @Test fun `parses clear sky code 0`() {
        val payload = SAMPLE.replace("\"weather_code\": [3]", "\"weather_code\": [0]")
        val w = WeatherClient.parseToday(payload)!!
        assertEquals("Clear", w.conditionLabel)
        assertEquals("☀️", w.emoji)
    }

    @Test fun `unknown weather code falls back to neutral label`() {
        val payload = SAMPLE.replace("\"weather_code\": [3]", "\"weather_code\": [9999]")
        val w = WeatherClient.parseToday(payload)!!
        assertEquals(9999, w.conditionCode)
        assertEquals("Unknown", w.conditionLabel)
        assertEquals("🌡️", w.emoji)
    }

    @Test fun `missing daily block returns null`() {
        val payload = """{"latitude":40.71,"longitude":-74.01}"""
        assertNull(WeatherClient.parseToday(payload))
    }

    @Test fun `empty arrays return null`() {
        val payload = """
            {
              "daily": {
                "time": [],
                "temperature_2m_max": [],
                "temperature_2m_min": [],
                "weather_code": []
              }
            }
        """.trimIndent()
        assertNull(WeatherClient.parseToday(payload))
    }

    @Test fun `garbage input returns null`() {
        assertNull(WeatherClient.parseToday("not json"))
        assertNull(WeatherClient.parseToday(""))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 ./gradlew :app:testDebugUnitTest --tests "io.nisfeb.talon.ai.WeatherClientParseTest" 2>&1 | tail -20`

Expected: FAIL with "Unresolved reference: WeatherClient".

- [ ] **Step 3: Create WeatherClient.kt**

```kotlin
package io.nisfeb.talon.ai

import io.nisfeb.talon.data.WeatherToday
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * open-meteo.com weather fetcher. No API key needed. Returns null on
 * any failure — caller treats null as "skip the weather card."
 *
 * See spec §Weather + §Generation step 6.
 */
class WeatherClient(private val http: OkHttpClient) {

    /**
     * Fetch today's high/low + condition for [lat],[lon]. Returns null
     * on network error, non-2xx, parse failure, or empty data.
     */
    suspend fun fetchToday(lat: Double, lon: Double): WeatherToday? = runCatching {
        val url = "https://api.open-meteo.com/v1/forecast".toHttpUrl().newBuilder()
            .addQueryParameter("latitude", lat.toString())
            .addQueryParameter("longitude", lon.toString())
            .addQueryParameter("daily", "temperature_2m_max,temperature_2m_min,weather_code")
            .addQueryParameter("forecast_days", "1")
            .addQueryParameter("timezone", "auto")
            .build()
        val req = Request.Builder().url(url).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@runCatching null
            parseToday(resp.body?.string() ?: return@runCatching null)
        }
    }.getOrNull()

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

        fun parseToday(body: String): WeatherToday? = runCatching {
            val obj = JSON.parseToJsonElement(body).jsonObject
            val daily = obj["daily"]?.jsonObject ?: return@runCatching null
            val high = daily["temperature_2m_max"]?.jsonArray?.firstOrNull()
                ?.jsonPrimitive?.doubleOrNull ?: return@runCatching null
            val low = daily["temperature_2m_min"]?.jsonArray?.firstOrNull()
                ?.jsonPrimitive?.doubleOrNull ?: return@runCatching null
            val code = daily["weather_code"]?.jsonArray?.firstOrNull()
                ?.jsonPrimitive?.intOrNull ?: return@runCatching null
            val (label, emoji) = wmoLookup(code)
            WeatherToday(highC = high, lowC = low, conditionCode = code,
                conditionLabel = label, emoji = emoji)
        }.getOrNull()

        // WMO weather code → (label, emoji). Subset based on
        // open-meteo's published code list. Missing codes fall back
        // to the catch-all "Unknown" / 🌡️.
        // https://open-meteo.com/en/docs#weathervariables
        private fun wmoLookup(code: Int): Pair<String, String> = when (code) {
            0 -> "Clear" to "☀️"
            1 -> "Mainly clear" to "🌤️"
            2 -> "Partly cloudy" to "⛅"
            3 -> "Overcast" to "☁️"
            45, 48 -> "Fog" to "🌫️"
            51, 53, 55 -> "Drizzle" to "🌦️"
            56, 57 -> "Freezing drizzle" to "🌧️"
            61, 63, 65 -> "Rain" to "🌧️"
            66, 67 -> "Freezing rain" to "🌧️"
            71, 73, 75, 77 -> "Snow" to "🌨️"
            80, 81, 82 -> "Rain showers" to "🌦️"
            85, 86 -> "Snow showers" to "🌨️"
            95 -> "Thunderstorm" to "⛈️"
            96, 99 -> "Thunderstorm w/ hail" to "⛈️"
            else -> "Unknown" to "🌡️"
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 ./gradlew :app:testDebugUnitTest --tests "io.nisfeb.talon.ai.WeatherClientParseTest" 2>&1 | tail -10`

Expected: 6 tests passing.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/nisfeb/talon/ai/WeatherClient.kt \
        app/src/test/kotlin/io/nisfeb/talon/ai/WeatherClientParseTest.kt
git commit -m "daily-digest: WeatherClient (open-meteo) + parse tests"
```

---

## Task 8: AiSettings dailyDigestEnabled flag + DailyDigestSettings

**Files:**
- Modify: `app/src/main/java/io/nisfeb/talon/ai/AiSettings.kt`
- Create: `app/src/main/java/io/nisfeb/talon/ai/DailyDigestSettings.kt`

`dailyDigestEnabled` (in AiSettings.Config) is the AI summary opt-in — separate from `DailyDigestSettings.enabled`, which controls whether the alarm fires at all.

- [ ] **Step 1: Add dailyDigestEnabled to AiSettings.Config**

In `AiSettings.kt`'s `Config` data class, add a new field next to `catchMeUpEnabled`:

```kotlin
val dailyDigestEnabled: Boolean = true,
```

In the `Feature` enum (look for `CatchMeUp`, `EmojiReact`), add `DailyDigest("daily_digest_enabled")`. Add a branch in `setFeature(...)`'s `when` block:

```kotlin
Feature.DailyDigest -> _state.value.copy(dailyDigestEnabled = enabled)
```

In the `applyRemote` parameter list, add `dailyDigestEnabled: Boolean`. In the apply body, copy it through. In `read()`, load with `prefs.getBoolean(Feature.DailyDigest.key, true)`. In the writer (`update`/`save` — match the existing `catchMeUpEnabled` pattern), persist via `.putBoolean(Feature.DailyDigest.key, dailyDigestEnabled)`.

If unsure of the exact line numbers, mirror every change site that already handles `catchMeUpEnabled` line-for-line.

- [ ] **Step 2: Update SettingsSync's AI bucket to include the new field**

In `SettingsSync.kt`, find `applyAiSettings` and the matching push helper. Add `put("dailyDigestEnabled", cfg.dailyDigestEnabled)` to the push, and `dailyDigestEnabled = bool("dailyDigestEnabled", true)` to the apply.

- [ ] **Step 3: Create DailyDigestSettings.kt**

```kotlin
package io.nisfeb.talon.ai

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * SharedPreferences-backed knobs for the daily digest alarm.
 * Controls whether the alarm fires at all, and at what time.
 *
 * The AI summary opt-in lives in [AiSettings.Config.dailyDigestEnabled]
 * because it composes with the rest of the AI feature toggles.
 *
 * Mirrored to %settings bucket "daily-digest" (see spec §Settings sync).
 */
class DailyDigestSettings(context: Context) {

    data class State(
        val enabled: Boolean = false,
        val hourOfDay: Int = 6,
        val minuteOfDay: Int = 0,
    )

    sealed class Change {
        data object Toggled : Change()
        data object TimeChanged : Change()
        data object SyncToggledOff : Change()
    }

    @Volatile var onChange: ((Change, transitionedOffSync: Boolean) -> Unit)? = null

    private val prefs: SharedPreferences =
        context.getSharedPreferences("talon_daily_digest", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<State> = _state.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        if (_state.value.enabled == enabled) return
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        _state.value = _state.value.copy(enabled = enabled)
        onChange?.invoke(Change.Toggled, false)
    }

    fun setTime(hourOfDay: Int, minuteOfDay: Int) {
        require(hourOfDay in 0..23) { "hourOfDay must be 0..23" }
        require(minuteOfDay in 0..59) { "minuteOfDay must be 0..59" }
        if (_state.value.hourOfDay == hourOfDay && _state.value.minuteOfDay == minuteOfDay) return
        prefs.edit()
            .putInt(KEY_HOUR, hourOfDay)
            .putInt(KEY_MINUTE, minuteOfDay)
            .apply()
        _state.value = _state.value.copy(hourOfDay = hourOfDay, minuteOfDay = minuteOfDay)
        onChange?.invoke(Change.TimeChanged, false)
    }

    /**
     * Apply remote settings (incoming from `%settings` sync). Same idiom
     * AiSettings uses — bypasses the [onChange] mirror to avoid
     * loop-back to the ship.
     */
    fun applyRemote(enabled: Boolean, hourOfDay: Int, minuteOfDay: Int) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putInt(KEY_HOUR, hourOfDay)
            .putInt(KEY_MINUTE, minuteOfDay)
            .apply()
        _state.value = State(enabled = enabled, hourOfDay = hourOfDay, minuteOfDay = minuteOfDay)
    }

    fun emitSyncToggledOff() {
        onChange?.invoke(Change.SyncToggledOff, true)
    }

    private fun read(): State = State(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        hourOfDay = prefs.getInt(KEY_HOUR, 6),
        minuteOfDay = prefs.getInt(KEY_MINUTE, 0),
    )

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_HOUR = "hour_of_day"
        const val KEY_MINUTE = "minute_of_day"
    }
}
```

- [ ] **Step 4: Build to confirm**

Run: `JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 ./gradlew :app:assembleDebug 2>&1 | tail -20`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/nisfeb/talon/ai/AiSettings.kt \
        app/src/main/java/io/nisfeb/talon/ai/DailyDigestSettings.kt \
        app/src/main/java/io/nisfeb/talon/urbit/SettingsSync.kt
git commit -m "daily-digest: settings + AiSettings.dailyDigestEnabled"
```

---

## Task 9: SettingsSync "daily-digest" bucket

**Files:**
- Modify: `app/src/main/java/io/nisfeb/talon/urbit/SettingsSync.kt`

- [ ] **Step 1: Add bucket constant + push helper + clear helper**

In `SettingsSync.kt`'s companion `BUCKET_*` block, add:

```kotlin
const val BUCKET_DAILY_DIGEST = "daily-digest"
```

Add `applyBucket(BUCKET_DAILY_DIGEST, deskMap[BUCKET_DAILY_DIGEST] as? JsonObject)` to the bootstrap-apply path (alongside other `applyBucket` calls).

In the `applyBucket` `when` branch list, add:

```kotlin
BUCKET_DAILY_DIGEST -> applyDailyDigestBucket(rows)
```

Add the apply helper:

```kotlin
private suspend fun applyDailyDigestBucket(rows: JsonObject?) {
    if (rows == null) return
    val enabled = (rows["enabled"] as? JsonPrimitive)?.booleanOrNull ?: false
    val hourOfDay = (rows["hourOfDay"] as? JsonPrimitive)?.intOrNull ?: 6
    val minuteOfDay = (rows["minuteOfDay"] as? JsonPrimitive)?.intOrNull ?: 0
    settings.applyRemote(enabled, hourOfDay, minuteOfDay)
}
```

(`settings` here is the new `DailyDigestSettings` reference — pass it into the `SettingsSync` constructor; see Task 12 for the wiring change.)

Add the push helpers:

```kotlin
suspend fun pushDailyDigest(state: DailyDigestSettings.State) {
    putEntry(BUCKET_DAILY_DIGEST, "enabled", JsonPrimitive(state.enabled))
    putEntry(BUCKET_DAILY_DIGEST, "hourOfDay", JsonPrimitive(state.hourOfDay))
    putEntry(BUCKET_DAILY_DIGEST, "minuteOfDay", JsonPrimitive(state.minuteOfDay))
}

suspend fun clearDailyDigestOnShip() {
    delBucket(BUCKET_DAILY_DIGEST)
}
```

(Match the existing `pushAiSettings` / `clearAiSettingsOnShip` shape — copy the `delBucket` and `putEntry` helper invocations from the surrounding code.)

In `clearBucketLocally`'s `when` (used when sync is toggled off), add:

```kotlin
BUCKET_DAILY_DIGEST -> { /* no local clear needed; settings stay until user changes them */ }
```

- [ ] **Step 2: Update TlonChatRepo.kt or wherever SettingsSync is instantiated to pass DailyDigestSettings**

Find the `SettingsSync(...)` constructor call. Add a `dailyDigestSettings` parameter. (If the existing call site is in `TlonChatRepo`, see Task 12 — that's the wiring step where TalonApplication injects the new dep.)

- [ ] **Step 3: Build**

Run: `JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 ./gradlew :app:assembleDebug 2>&1 | tail -20`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/io/nisfeb/talon/urbit/SettingsSync.kt \
        app/src/main/java/io/nisfeb/talon/urbit/TlonChatRepo.kt
git commit -m "daily-digest: SettingsSync bucket daily-digest"
```

---

## Task 10: Notifications channel + showDailyDigest

**Files:**
- Modify: `app/src/main/java/io/nisfeb/talon/Notifications.kt`

- [ ] **Step 1: Add channel constant + ensure it in `ensureChannel`**

In `Notifications.kt`, add the constant alongside the others:

```kotlin
const val CHANNEL_DAILY_DIGEST = "daily-digest"
const val EXTRA_OPEN_DIGEST = "open_digest"
const val EXTRA_DIGEST_DATE = "digest_date"
```

In `ensureChannel`, after the `CHANNEL_WATCHWORDS` block, add:

```kotlin
if (mgr.getNotificationChannel(CHANNEL_DAILY_DIGEST) == null) {
    mgr.createNotificationChannel(
        NotificationChannel(
            CHANNEL_DAILY_DIGEST,
            "Daily digest",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Morning brief — fires once a day"
            enableLights(true)
        }
    )
}
```

- [ ] **Step 2: Add showDailyDigest helper**

After `showWatchwordHit`, add:

```kotlin
/**
 * Daily digest notification. Tap routes into MainActivity with
 * EXTRA_OPEN_DIGEST set; TalonApp picks it up and navigates to
 * DailyDigestScreen for [ship] / [dateLocal].
 *
 * Tag = "digest:<ship>:<dateLocal>" so re-firing the same day
 * replaces. The notification ID is shared with the chat-message
 * notifications because Android dedupes per (tag, id).
 */
fun showDailyDigest(
    context: Context,
    ship: String,
    dateLocal: String,
    title: String,
    body: String,
    generatedAtMs: Long,
) {
    val mgr = ContextCompat.getSystemService(context, NotificationManager::class.java)
        ?: return

    val tapIntent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(EXTRA_OPEN_DIGEST, ship)
        putExtra(EXTRA_DIGEST_DATE, dateLocal)
    }
    val pending = PendingIntent.getActivity(
        context,
        ("digest:$ship:$dateLocal").hashCode(),
        tapIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    val notification = NotificationCompat.Builder(context, CHANNEL_DAILY_DIGEST)
        .setSmallIcon(android.R.drawable.ic_dialog_email)
        .setContentTitle(title)
        .setContentText(body)
        .setStyle(NotificationCompat.BigTextStyle().bigText(body))
        .setContentIntent(pending)
        .setAutoCancel(true)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setCategory(NotificationCompat.CATEGORY_REMINDER)
        .setWhen(generatedAtMs)
        .setShowWhen(true)
        .build()

    mgr.notify("digest:$ship:$dateLocal", NOTIFICATION_ID, notification)
}
```

- [ ] **Step 3: Build**

Run: `JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 ./gradlew :app:assembleDebug 2>&1 | tail -20`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/io/nisfeb/talon/Notifications.kt
git commit -m "daily-digest: notification channel + showDailyDigest"
```

---

## Task 11: DailyDigest facade

**Files:**
- Create: `app/src/main/java/io/nisfeb/talon/ai/DailyDigest.kt`

This is the largest single file. Implements `scheduleNext()` and `generateAndNotify()`. Application-level singleton; reads `sessionStore.activeShip()` at fire time.

- [ ] **Step 1: Create DailyDigest.kt**

```kotlin
package io.nisfeb.talon.ai

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import io.nisfeb.talon.DigestAlarmReceiver
import io.nisfeb.talon.Notifications
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.Bucket
import io.nisfeb.talon.data.DailyDigestEntity
import io.nisfeb.talon.data.DigestItem
import io.nisfeb.talon.data.WeatherToday
import io.nisfeb.talon.ui.fetchLastKnownLocation
import io.nisfeb.talon.urbit.SessionStore
import io.nisfeb.talon.urbit.StoryCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Application-level facade for the daily digest. Owns scheduling and
 * the generation pipeline. See spec §Architecture / §Generation pipeline.
 *
 * Single instance per process. Uses `app.db` (the active ship's
 * database) at fire time; `sessionStore.activeShip()` snapshots which
 * ship is "active" for this fire.
 */
class DailyDigest(
    private val context: Context,
    private val sessionStore: SessionStore,
    private val activeShipFlow: kotlinx.coroutines.flow.StateFlow<String?>,
    private val getDb: () -> AppDatabase,
    private val aiSettings: AiSettings,
    private val aiClient: AiClient,
    private val settings: DailyDigestSettings,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
) {

    private val alarmManager: AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val weather = WeatherClient(http)

    /**
     * Stream the latest digest for the active ship. UI collects this
     * to render `DailyDigestScreen`. `flatMapLatest` re-keys when the
     * active ship changes.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun streamLatestForActiveShip(): Flow<DailyDigestEntity?> =
        activeShipFlow.flatMapLatest { ship ->
            if (ship == null) flowOf(null)
            else getDb().dailyDigests().streamLatestForShip(ship)
        }

    /**
     * Compute next fire time and arm the alarm. No-op when settings are
     * disabled. Cancels any existing alarm first to avoid double-arms.
     */
    fun scheduleNext() {
        val st = settings.state.value
        if (!st.enabled) {
            alarmManager.cancel(buildPendingIntent())
            return
        }
        val fireMs = DailyDigestSchedule.nextFireMs(
            now = Instant.now(),
            hourOfDay = st.hourOfDay,
            minuteOfDay = st.minuteOfDay,
            zone = ZoneId.systemDefault(),
        )
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP, fireMs, buildPendingIntent(),
        )
    }

    fun cancel() {
        alarmManager.cancel(buildPendingIntent())
    }

    /**
     * Run the full pipeline: fetch buckets, weather, AI summary; persist
     * and notify. Safe to call from receiver context (uses scope.launch
     * internally for the suspending work).
     *
     * [reason] is logged for diagnostics ("alarm" | "test-now" | "boot").
     */
    fun generateAndNotify(reason: String) {
        scope.launch(Dispatchers.IO) {
            runCatching { runPipeline(reason) }
        }
    }

    private data class Bundle(
        val mentionMsgs: List<io.nisfeb.talon.data.MessageEntity>,
        val mentionPlain: Map<String, String>,
        val hits: List<io.nisfeb.talon.data.WatchwordHitEntity>,
        val uCands: Map<String, List<io.nisfeb.talon.data.MessageEntity>>,
        val uCounts: Map<String, Int>,
    )

    private suspend fun runPipeline(@Suppress("UNUSED_PARAMETER") reason: String) {
        val zone = ZoneId.systemDefault()
        val nowMs = System.currentTimeMillis()
        val windowEnd = nowMs
        val windowStart = nowMs - 24L * 60 * 60 * 1000
        val dateLocal = LocalDate.now(zone).toString()

        val ship = sessionStore.activeShip() ?: return
        val ourPatp = ship
        val db = getDb()

        // 1. Build buckets in parallel
        val bundle: Bundle = coroutineScope {
            val mentionsAsync = async {
                val candidates = db.messages().candidatesForMentionScan(
                    ourPatp = ourPatp,
                    windowStartMs = windowStart,
                    windowEndMs = windowEnd,
                    limit = 500,
                )
                val plainText = candidates.associate { m ->
                    m.id to StoryCache.textFor(m.id, m.contentJson)
                }
                candidates to plainText
            }
            val hitsAsync = async {
                db.watchwords().hitsInWindow(windowStart, windowEnd)
            }
            val unreadAsync = async {
                val excludes = db.watchwords().excludesAsList().toHashSet()
                val muted = db.notifyPrefs().mutedWhoms().toHashSet()
                val unreads = db.unreads().getAll().filter {
                    it.count > 0 && it.whom !in excludes && it.whom !in muted
                }
                val cands = mutableMapOf<String, List<io.nisfeb.talon.data.MessageEntity>>()
                val counts = mutableMapOf<String, Int>()
                for (u in unreads) {
                    val msgs = db.messages().newestInChatForWindow(
                        whom = u.whom,
                        ourPatp = ourPatp,
                        windowStartMs = windowStart,
                        windowEndMs = windowEnd,
                        limit = u.count,
                    )
                    if (msgs.isNotEmpty()) {
                        cands[u.whom] = msgs
                        counts[u.whom] = u.count
                    }
                }
                cands to counts
            }
            val (mentionMsgs, mentionPlain) = mentionsAsync.await()
            val hits = hitsAsync.await()
            val (uCands, uCounts) = unreadAsync.await()
            Bundle(mentionMsgs, mentionPlain, hits, uCands, uCounts)
        }

        val items = DailyDigestSelector.assemble(
            ourPatp = ourPatp,
            mentionCandidates = bundle.mentionMsgs,
            mentionPlainText = bundle.mentionPlain,
            watchwordHits = bundle.hits,
            unreadCandidates = bundle.uCands,
            unreadCounts = bundle.uCounts,
        )

        // 2. Quiet day check
        if (items.isEmpty()) return

        // 3. Weather (best-effort)
        val weatherToday = withTimeoutOrNull(5_000) {
            val loc = fetchLastKnownLocation(context) ?: return@withTimeoutOrNull null
            weather.fetchToday(loc.latitude, loc.longitude)
        }

        // 4. AI summary (best-effort)
        val aiCfg = aiSettings.state.value
        val summary = if (aiCfg.dailyDigestEnabled && aiCfg.hasKey()) {
            runCatching {
                val transcript = DailyDigestPrompt.format(items) { it }
                val sys = """
                    You are writing a brief morning digest for a chat user.
                    Cover the day in 3-5 bullets or a short paragraph.
                    Group by theme, not by chat. Highlight @mentions and
                    watchword hits as the user's priorities. Do not invent
                    information. Use only the transcript provided.
                """.trimIndent()
                aiClient.complete(sys, "Today's transcript:\n\n$transcript",
                    maxOutputTokens = 512)
            }.getOrNull()
        } else null

        // 5. Persist
        val itemsJson = JSON.encodeToString(items)
        val weatherJson = weatherToday?.let { JSON.encodeToString(it) }
        db.dailyDigests().upsert(DailyDigestEntity(
            ship = ship,
            dateLocal = dateLocal,
            generatedAtMs = nowMs,
            summaryText = summary,
            itemsJson = itemsJson,
            weatherJson = weatherJson,
        ))
        val yesterday = LocalDate.parse(dateLocal).minusDays(1).toString()
        db.dailyDigests().pruneOlderThan(yesterday)

        // 6. Notify
        val title = "Today's brief: ${countLine(items)}"
        val body = summary?.take(120) ?: countLine(items)
        Notifications.showDailyDigest(
            context = context,
            ship = ship,
            dateLocal = dateLocal,
            title = title,
            body = body,
            generatedAtMs = nowMs,
        )
    }

    private fun countLine(items: List<DigestItem>): String {
        val byBucket = items.groupBy { it.bucket }
        val mentions = byBucket[Bucket.MENTION]?.size ?: 0
        val hits = byBucket[Bucket.WATCHWORD]?.size ?: 0
        val unread = byBucket[Bucket.UNREAD]?.size ?: 0
        return listOf(
            unread to "unread",
            mentions to "mentions",
            hits to "hits",
        ).filter { it.first > 0 }.joinToString(", ") { "${it.first} ${it.second}" }
    }

    private fun buildPendingIntent(): PendingIntent {
        val intent = Intent(context, DigestAlarmReceiver::class.java).apply {
            action = ACTION_DAILY_DIGEST_FIRE
        }
        return PendingIntent.getBroadcast(
            context,
            REQ_DIGEST,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Acquire a partial wake lock for [tag] with a 60s timeout. The
     * receiver hands this back to the alarm subsystem in finally.
     */
    fun acquireWakeLock(tag: String): PowerManager.WakeLock {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "talon:$tag").apply {
            setReferenceCounted(false)
            acquire(TimeUnit.SECONDS.toMillis(60))
        }
    }

    companion object {
        const val ACTION_DAILY_DIGEST_FIRE = "io.nisfeb.talon.action.DAILY_DIGEST_FIRE"
        private const val REQ_DIGEST = 7700
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
```

- [ ] **Step 2: Add WatchwordsDao.hitsInWindow query**

The pipeline uses `db.watchwords().hitsInWindow(...)`. Add to `app/src/main/java/io/nisfeb/talon/data/WatchwordsDao.kt`:

```kotlin
@Query("""
    SELECT * FROM watchword_hits
    WHERE sentMs >= :windowStartMs AND sentMs < :windowEndMs
    ORDER BY sentMs DESC
    LIMIT :limit
""")
suspend fun hitsInWindow(
    windowStartMs: Long,
    windowEndMs: Long,
    limit: Int = 500,
): List<WatchwordHitEntity>
```

- [ ] **Step 3: Add UnreadDao.getAll query**

Add to `app/src/main/java/io/nisfeb/talon/data/UnreadDao.kt`:

```kotlin
@Query("SELECT * FROM unreads")
suspend fun getAll(): List<UnreadEntity>
```

- [ ] **Step 4: Note about activeShipFlow**

`TalonApplication` already exposes `activeShipFlow: StateFlow<String?>`. The DailyDigest constructor takes that flow as a parameter (see Task 13 wiring step). No SessionStore changes needed.

- [ ] **Step 5: Build**

Run: `JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 ./gradlew :app:assembleDebug 2>&1 | tail -30`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/nisfeb/talon/ai/DailyDigest.kt \
        app/src/main/java/io/nisfeb/talon/data/WatchwordsDao.kt \
        app/src/main/java/io/nisfeb/talon/data/UnreadDao.kt
git commit -m "daily-digest: facade with scheduleNext + generation pipeline"
```

---

## Task 12: Receivers + manifest

**Files:**
- Create: `app/src/main/java/io/nisfeb/talon/DigestAlarmReceiver.kt`
- Create: `app/src/main/java/io/nisfeb/talon/BootReceiver.kt`
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: Create DigestAlarmReceiver.kt**

```kotlin
package io.nisfeb.talon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.nisfeb.talon.ai.DailyDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Wakes once a day at the user-configured time. Hands off to the
 * DailyDigest facade in `goAsync()` mode so the AI/HTTP work isn't
 * cut off by the receiver's 10s sync limit. Re-arms tomorrow's
 * alarm in `finally` regardless of pipeline outcome — quiet days,
 * AI failures, and exceptions all still re-schedule.
 *
 * See spec §Schedule + §Error handling.
 */
class DigestAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DailyDigest.ACTION_DAILY_DIGEST_FIRE) return
        val app = context.applicationContext as TalonApplication
        val pending = goAsync()
        val wakeLock = app.dailyDigest.acquireWakeLock("digest-fire")
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                app.dailyDigest.generateAndNotify("alarm")
            } finally {
                runCatching { app.dailyDigest.scheduleNext() }
                runCatching { wakeLock.release() }
                runCatching { pending.finish() }
            }
        }
    }
}
```

- [ ] **Step 2: Create BootReceiver.kt**

```kotlin
package io.nisfeb.talon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-arm the daily digest alarm on boot or timezone change. Without
 * this, AlarmManager forgets the schedule when the device reboots,
 * and the digest fires at the wrong local time after travel.
 *
 * See spec §Error handling: "Reboot → BootReceiver re-arms."
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                val app = context.applicationContext as TalonApplication
                runCatching { app.dailyDigest.scheduleNext() }
            }
        }
    }
}
```

- [ ] **Step 3: Add manifest entries**

Edit `app/src/main/AndroidManifest.xml`. Add the permission alongside existing `<uses-permission>` entries:

```xml
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
```

Inside `<application>`, after the `<service>` block, add:

```xml
<receiver
    android:name=".DigestAlarmReceiver"
    android:exported="false" />

<receiver
    android:name=".BootReceiver"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
        <action android:name="android.intent.action.TIMEZONE_CHANGED" />
    </intent-filter>
</receiver>
```

- [ ] **Step 4: Build**

Run: `JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 ./gradlew :app:assembleDebug 2>&1 | tail -20`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/nisfeb/talon/DigestAlarmReceiver.kt \
        app/src/main/java/io/nisfeb/talon/BootReceiver.kt \
        app/src/main/AndroidManifest.xml
git commit -m "daily-digest: alarm + boot receivers + manifest"
```

---

## Task 13: TalonApplication wiring

**Files:**
- Modify: `app/src/main/java/io/nisfeb/talon/TalonApplication.kt`

- [ ] **Step 1: Add the new Application-level fields**

In `TalonApplication`, alongside other always-on singletons (e.g., `aiSettings`), add:

```kotlin
lateinit var dailyDigestSettings: io.nisfeb.talon.ai.DailyDigestSettings
    private set
lateinit var dailyDigest: io.nisfeb.talon.ai.DailyDigest
    private set
```

In `onCreate()`, after `aiSettings`/`aiClient` are constructed, add (BEFORE `buildShipScoped`):

```kotlin
dailyDigestSettings = io.nisfeb.talon.ai.DailyDigestSettings(this)
dailyDigest = io.nisfeb.talon.ai.DailyDigest(
    context = this,
    sessionStore = sessionStore,
    activeShipFlow = activeShipFlow,
    getDb = { db },
    aiSettings = aiSettings,
    aiClient = aiClient,
    settings = dailyDigestSettings,
    http = http,
    scope = appScope,
)
```

After `buildShipScoped(shipForInit)` and `_activeShip.value = initialShip`, add:

```kotlin
// Arm the alarm if the user has enabled it (and re-arm on every
// app start — belt-and-suspenders against the receiver being killed
// before it finished re-arming yesterday).
runCatching { dailyDigest.scheduleNext() }
```

- [ ] **Step 2: Wire DailyDigestSettings.onChange**

Below the existing `aiSettings.onStateChange = { ... }` block, add:

```kotlin
dailyDigestSettings.onChange = { evt, transitionedOffSync ->
    appScope.launch {
        runCatching {
            when {
                transitionedOffSync -> repo.settingsSync.clearDailyDigestOnShip()
                else -> repo.settingsSync.pushDailyDigest(dailyDigestSettings.state.value)
            }
        }
        // Re-arm on toggle / time change.
        runCatching { dailyDigest.scheduleNext() }
    }
}
```

- [ ] **Step 3: Build**

Run: `JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 ./gradlew :app:assembleDebug 2>&1 | tail -20`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/io/nisfeb/talon/TalonApplication.kt
git commit -m "daily-digest: TalonApplication wiring + scheduleNext on startup"
```

---

## Task 14: Settings UI — daily digest section

**Files:**
- Modify: `app/src/main/java/io/nisfeb/talon/ui/screens/SettingsScreen.kt`

The settings screen already has a `Feature.CatchMeUp` section pattern to mirror. Add a new "Daily digest" section with toggle + time picker + status + "Test now" button.

- [ ] **Step 1: Find the AI feature toggles block and add the daily-digest section**

Open `SettingsScreen.kt`. Find where `Feature.CatchMeUp` toggle is rendered. After that block, add a new `Column` for daily digest. The exact code depends on the existing screen's component structure; the inputs are `app.dailyDigestSettings.state.collectAsState()` and `app.aiSettings.state.collectAsState()`.

Skeleton:

```kotlin
val ddState = app.dailyDigestSettings.state.collectAsState().value
Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
    Column(Modifier.padding(16.dp)) {
        Text("Daily digest", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "A morning brief at your chosen time: unread, watchword hits, and @mentions.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Enabled", Modifier.weight(1f))
            Switch(
                checked = ddState.enabled,
                onCheckedChange = { app.dailyDigestSettings.setEnabled(it) },
            )
        }
        if (ddState.enabled) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Fire time", Modifier.weight(1f))
                TextButton(onClick = { showTimePickerDialog = true }) {
                    Text("%02d:%02d".format(ddState.hourOfDay, ddState.minuteOfDay))
                }
            }
            // AI summary opt-in (only enabled if AI is configured)
            val aiCfg = app.aiSettings.state.collectAsState().value
            if (aiCfg.hasKey()) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Include AI summary", Modifier.weight(1f))
                    Switch(
                        checked = aiCfg.dailyDigestEnabled,
                        onCheckedChange = {
                            app.aiSettings.setFeature(AiSettings.Feature.DailyDigest, it)
                        },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = {
                app.dailyDigest.generateAndNotify("test-now")
            }) {
                Text("Test now")
            }
        }
    }
}

if (showTimePickerDialog) {
    val timePickerState = rememberTimePickerState(
        initialHour = ddState.hourOfDay,
        initialMinute = ddState.minuteOfDay,
        is24Hour = false,
    )
    AlertDialog(
        onDismissRequest = { showTimePickerDialog = false },
        confirmButton = {
            TextButton(onClick = {
                app.dailyDigestSettings.setTime(
                    timePickerState.hour, timePickerState.minute,
                )
                showTimePickerDialog = false
            }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = { showTimePickerDialog = false }) { Text("Cancel") }
        },
        title = { Text("Digest time") },
        text = { TimePicker(state = timePickerState) },
    )
}
```

Above the `Card`, declare `var showTimePickerDialog by remember { mutableStateOf(false) }`.

Adjust imports: `androidx.compose.material3.AlertDialog`, `Switch`, `TimePicker`, `rememberTimePickerState`, `androidx.compose.runtime.*`. Use the same pattern the existing screen uses for state hoisting.

- [ ] **Step 2: Build and visually inspect**

Run: `JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 ./gradlew :app:assembleDebug 2>&1 | tail -20`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/io/nisfeb/talon/ui/screens/SettingsScreen.kt
git commit -m "daily-digest: settings UI"
```

---

## Task 15: DailyDigestScreen

**Files:**
- Create: `app/src/main/java/io/nisfeb/talon/ui/screens/DailyDigestScreen.kt`

- [ ] **Step 1: Create DailyDigestScreen.kt**

```kotlin
package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.TalonApplication
import io.nisfeb.talon.data.Bucket
import io.nisfeb.talon.data.DailyDigestEntity
import io.nisfeb.talon.data.DigestItem
import io.nisfeb.talon.data.WeatherToday
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyDigestScreen(
    app: TalonApplication,
    onBack: () -> Unit,
    onOpenMessage: (whom: String, postId: String) -> Unit,
) {
    val digest by app.dailyDigest.streamLatestForActiveShip().collectAsState(initial = null)
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Today's brief") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            val today = digest?.takeIf { isTodayLocal(it.dateLocal) }
            if (today == null) {
                EmptyDigest(
                    onGenerateNow = { app.dailyDigest.generateAndNotify("manual-screen") },
                )
            } else {
                Body(today, onOpenMessage)
            }
        }
    }
}

@Composable
private fun Body(
    digest: DailyDigestEntity,
    onOpenMessage: (String, String) -> Unit,
) {
    val items = remember(digest.itemsJson) {
        runCatching { JSON.decodeFromString<List<DigestItem>>(digest.itemsJson) }
            .getOrDefault(emptyList())
    }
    val weather = remember(digest.weatherJson) {
        digest.weatherJson?.let {
            runCatching { JSON.decodeFromString<WeatherToday>(it) }.getOrNull()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        weather?.let { item { WeatherCard(it) } }
        digest.summaryText?.let { item { SummaryCard(it) } }
        renderBucket("Mentions", Bucket.MENTION, items, onOpenMessage)
        renderBucket("Watchword hits", Bucket.WATCHWORD, items, onOpenMessage)
        renderBucket("Unread", Bucket.UNREAD, items, onOpenMessage)
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.renderBucket(
    label: String,
    bucket: Bucket,
    items: List<DigestItem>,
    onOpenMessage: (String, String) -> Unit,
) {
    val rows = items.filter { it.bucket == bucket }
    if (rows.isEmpty()) return
    item {
        Text(
            "$label (${rows.size})",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    items(rows, key = { "${it.whom}|${it.postId}" }) { row ->
        ItemRow(row, onOpenMessage)
    }
}

@Composable
private fun WeatherCard(w: WeatherToday) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(w.emoji, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.width(12.dp))
            Text(
                "${w.conditionLabel} · ${w.highC.toInt()}° / ${w.lowC.toInt()}°",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun SummaryCard(text: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(
            text,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun ItemRow(item: DigestItem, onOpen: (String, String) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = { onOpen(item.whom, item.postId) },
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.authorPatp.ifBlank { "(unknown)" },
                    style = MaterialTheme.typography.labelLarge,
                )
                if (item.matchedTerm != null) {
                    Spacer(Modifier.width(8.dp))
                    AssistChip(
                        onClick = {},
                        label = { Text(item.matchedTerm) },
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                item.snippet,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun EmptyDigest(onGenerateNow: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Nothing to brief today.", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onGenerateNow) { Text("Generate now") }
    }
}

private val JSON = Json { ignoreUnknownKeys = true }

private fun isTodayLocal(dateLocal: String): Boolean =
    dateLocal == java.time.LocalDate.now(java.time.ZoneId.systemDefault()).toString()
```

- [ ] **Step 2: Build**

Run: `JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 ./gradlew :app:assembleDebug 2>&1 | tail -20`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/io/nisfeb/talon/ui/screens/DailyDigestScreen.kt
git commit -m "daily-digest: DailyDigestScreen"
```

---

## Task 16: Nav routing + home menu + deep link

**Files:**
- Modify: `app/src/main/java/io/nisfeb/talon/MainActivity.kt`
- Modify: `app/src/main/java/io/nisfeb/talon/ui/TalonApp.kt`

The watchwords feature added a similar route + menu entry. Mirror that pattern.

- [ ] **Step 1: MainActivity intent extras pass-through**

In `MainActivity.kt`, find where chat-deep-link extras are extracted. Mirror the pattern for `Notifications.EXTRA_OPEN_DIGEST` (and `EXTRA_DIGEST_DATE`). Surface it to the Compose tree via the same mechanism existing extras use (a remembered state or a callback).

- [ ] **Step 2: TalonApp.kt route**

In `TalonApp.kt`:
- Add `var digestOpen by remember { mutableStateOf(false) }`.
- When the deep-link extra `EXTRA_OPEN_DIGEST` is consumed, set `digestOpen = true`.
- Find the home overflow menu (next to "Watchwords"). Add a new `DropdownMenuItem` labeled "Today's brief" → `digestOpen = true`.
- Add a top-level branch:

```kotlin
if (digestOpen) {
    DailyDigestScreen(
        app = app,
        onBack = { digestOpen = false },
        onOpenMessage = { whom, postId ->
            // Reuse the existing watchword-feed deep-link plumbing.
            digestOpen = false
            // ...same routing call WatchwordsScreen uses
        },
    )
    return
}
```

The exact routing call for "open this whom + scroll to postId" is the same one `WatchwordsScreen` uses — find it in TalonApp.kt and copy.

- [ ] **Step 3: Build and run on device**

Run: `JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 ./gradlew :app:assembleDebug 2>&1 | tail -20`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/io/nisfeb/talon/MainActivity.kt \
        app/src/main/java/io/nisfeb/talon/ui/TalonApp.kt
git commit -m "daily-digest: nav route + home menu + notification deep link"
```

---

## Task 17: Fuzz tests + mutation targets + ship

**Files:**
- Create: `app/src/test/kotlin/io/nisfeb/talon/ai/DailyDigestFuzzTest.kt`
- Modify: `scripts/mutate/mutate.sh`
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Create DailyDigestFuzzTest.kt**

```kotlin
package io.nisfeb.talon.ai

import io.nisfeb.talon.urbit.Fuzz
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class DailyDigestFuzzTest {

    private val ITERATIONS = 1_000
    private val SEED = 1_000L

    // ─── DailyDigestSchedule ──────────────────────────────────

    @Test
    fun `nextFireMs never throws on arbitrary inputs`() {
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            val now = Instant.ofEpochSecond(rnd.nextLong(0L, 4_000_000_000L))
            val hour = rnd.nextInt(0, 24)
            val minute = rnd.nextInt(0, 60)
            DailyDigestSchedule.nextFireMs(now, hour, minute, ZoneId.of("UTC"))
        }
    }

    @Test
    fun `nextFireMs result is always strictly greater than now`() {
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            val now = Instant.ofEpochSecond(rnd.nextLong(0L, 4_000_000_000L))
            val hour = rnd.nextInt(0, 24)
            val minute = rnd.nextInt(0, 60)
            val next = DailyDigestSchedule.nextFireMs(now, hour, minute, ZoneId.of("UTC"))
            assertTrue("next=$next now=${now.toEpochMilli()}", next > now.toEpochMilli())
        }
    }

    @Test
    fun `nextFireMs result is at most 24h plus 1 hour from now`() {
        // 25h to allow for DST forward (which can stretch the day).
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            val now = Instant.ofEpochSecond(rnd.nextLong(0L, 4_000_000_000L))
            val hour = rnd.nextInt(0, 24)
            val minute = rnd.nextInt(0, 60)
            val next = DailyDigestSchedule.nextFireMs(now, hour, minute, ZoneId.of("UTC"))
            val maxMs = now.toEpochMilli() + 25L * 60 * 60 * 1000
            assertTrue("next=$next maxMs=$maxMs", next <= maxMs)
        }
    }

    // ─── DailyDigestMentionMatcher ────────────────────────────

    @Test
    fun `containsMention never throws`() {
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            DailyDigestMentionMatcher.containsMention(
                Fuzz.randomString(rnd, 200),
                Fuzz.randomString(rnd, 30),
            )
        }
    }

    @Test
    fun `containsMention is deterministic`() {
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            val h = Fuzz.randomString(rnd, 200)
            val p = Fuzz.randomString(rnd, 30)
            assertEquals(
                DailyDigestMentionMatcher.containsMention(h, p),
                DailyDigestMentionMatcher.containsMention(h, p),
            )
        }
    }

    @Test
    fun `containsMention case-insensitivity invariant`() {
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            val h = Fuzz.randomString(rnd, 200)
            val p = Fuzz.randomString(rnd, 30)
            val orig = DailyDigestMentionMatcher.containsMention(h, p)
            assertEquals(orig, DailyDigestMentionMatcher.containsMention(h.uppercase(), p.lowercase()))
            assertEquals(orig, DailyDigestMentionMatcher.containsMention(h.lowercase(), p.uppercase()))
        }
    }

    @Test
    fun `containsMention empty patp returns false`() {
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            assertEquals(false, DailyDigestMentionMatcher.containsMention(
                Fuzz.randomString(rnd, 200), ""))
        }
    }

    // ─── DailyDigestPrompt ────────────────────────────────────

    @Test
    fun `format never throws on empty list`() {
        DailyDigestPrompt.format(emptyList()) { it }
    }

    @Test
    fun `format output line length bounded`() {
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            val items = List(rnd.nextInt(0, 10)) {
                io.nisfeb.talon.data.DigestItem(
                    whom = "~h/s",
                    postId = "0v1.${rnd.nextInt()}",
                    authorPatp = "~sampel",
                    sentMs = rnd.nextLong(),
                    bucket = io.nisfeb.talon.data.Bucket.values().random(rnd.asJavaRandom()),
                    snippet = Fuzz.randomString(rnd, 500),
                )
            }
            val out = DailyDigestPrompt.format(items) { it }
            for (line in out.lines()) {
                assertTrue("line too long: ${line.length}", line.length <= 260)
            }
        }
    }
}
```

(Note: `Fuzz` lives in `io.nisfeb.talon.urbit` package. `Random.asJavaRandom()` extension is in `kotlin.random`.)

- [ ] **Step 2: Run the fuzz tests**

Run: `JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 ./gradlew :app:testDebugUnitTest --tests "io.nisfeb.talon.ai.DailyDigestFuzzTest" 2>&1 | tail -10`

Expected: BUILD SUCCESSFUL with 9 tests passing.

- [ ] **Step 3: Add to mutate.sh default targets**

In `scripts/mutate/mutate.sh`, append to the `DEFAULT_TARGETS` array (after the `Watchwords.kt` line):

```bash
"app/src/main/java/io/nisfeb/talon/ai/DailyDigestSchedule.kt"
"app/src/main/java/io/nisfeb/talon/ai/DailyDigestMentionMatcher.kt"
"app/src/main/java/io/nisfeb/talon/ai/DailyDigestPrompt.kt"
"app/src/main/java/io/nisfeb/talon/ai/DailyDigestSelector.kt"
```

- [ ] **Step 4: Bump version**

In `app/build.gradle.kts`, bump:
```kotlin
versionCode = 17       // was 16
versionName = "0.4.0"  // was "0.3.1"
```

- [ ] **Step 5: Run the full test suite**

Run: `JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 ./gradlew :app:testDebugUnitTest 2>&1 | tail -20`

Expected: BUILD SUCCESSFUL, all tests passing.

- [ ] **Step 6: Commit**

```bash
git add app/src/test/kotlin/io/nisfeb/talon/ai/DailyDigestFuzzTest.kt \
        scripts/mutate/mutate.sh \
        app/build.gradle.kts
git commit -m "daily-digest: fuzz tests + mutate targets + 0.4.0"
```

---

## Task 18: Manual smoke test + ship

After all preceding tasks pass `./gradlew :app:assembleDebug` and the unit suite is green, do the on-device smoke.

- [ ] **Step 1: Build a release APK and install on device**

```bash
JAVA_HOME=/home/sneagan/jdk-install/jdk-17.0.12+7 ./gradlew :app:assembleRelease 2>&1 | tail -10
adb install -r app/build/outputs/apk/release/app-release.apk
```

- [ ] **Step 2: Enable digest in settings**

- Open Talon → settings → "Daily digest" section
- Toggle "Enabled" on
- Set fire time to about 2 minutes from now
- Confirm status line shows "Next: today HH:MM"

- [ ] **Step 3: Verify "Test now" generates immediately**

- Tap "Test now" in the daily-digest section
- Wait 5-10 seconds
- Confirm a notification appears in the shade with title "Today's brief: ..." and the count line

- [ ] **Step 4: Tap notification and verify routing**

- Tap the notification
- Confirm it opens `DailyDigestScreen` with three sections rendered
- Verify weather card appears (assuming location permission granted) — emoji + condition + H/L
- Verify each item row shows author + snippet + matched term badge for watchword hits
- Tap a row → confirm it routes into the source chat at the right message

- [ ] **Step 5: Verify quiet day**

- Mark all chats read (so unread count is 0)
- Clear watchword hits + ensure no recent messages contain `~ourpatp`
- Tap "Test now"
- Confirm NO notification appears (per Q9-A)
- Open the digest screen via the home menu — confirm "Nothing to brief today." shows

- [ ] **Step 6: Verify boot survival (optional but recommended)**

- With digest enabled and configured, reboot the device
- After boot, open Talon → settings → digest section
- Confirm "Next" status line still shows the right next fire time

- [ ] **Step 7: Commit (anything pending)**

```bash
git status
# If anything uncommitted from the smoke (unlikely), commit it.
```

- [ ] **Step 8: Merge to master**

```bash
git checkout master
git merge --no-ff feat/daily-digest -m "Merge feat/daily-digest: morning brief + weather"
```

---

## Notes for the implementer

- **TDD discipline:** every pure helper (Tasks 2, 3, 4, 6, 7) follows the red-green-commit loop. Don't skip step 2 (run tests to confirm they fail) — that's how you know the test is actually testing something.
- **Code style:** match Watchwords (`app/src/main/java/io/nisfeb/talon/ai/Watchwords.kt`) for the facade shape. Match `WatchwordsScreen` for the list-of-items screen layout.
- **Existing helpers:** `StoryCache.textFor(messageId, contentJson)` is the one-liner for "give me the plain-text of this message." `fetchLastKnownLocation(ctx)` is in `ui/LocationShare.kt`.
- **JSON serialization:** use `kotlinx.serialization.json.Json` with `ignoreUnknownKeys = true`. The `@Serializable` annotation on data classes is enough.
- **Don't pre-warm the receiver:** it's instantiated by the system on demand. Don't try to put a singleton inside it; use `context.applicationContext as TalonApplication` to reach the facade.
- **Coroutine context:** all DB and HTTP work runs on `Dispatchers.IO`. The receiver uses `goAsync()` + a fresh `CoroutineScope(SupervisorJob() + Dispatchers.IO)` so the receiver can return immediately while the work continues.
- **Android version:** minSdk is 26, target 35. `setAndAllowWhileIdle` is available since 23. `EXTRA_OPEN_DIGEST` and friends are plain string keys.
