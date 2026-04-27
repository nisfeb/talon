# Daily Digest Design

_Written: 2026-04-26_

## Goal

A daily morning brief surfaced at a user-configured time. Combines unread chat activity, watchword hits, @mentions of the user, an AI-written summary, and today's weather into a single screen and a single notification.

## Decisions (from brainstorming)

- **Purpose:** morning ritual — a brief that combines unread + watchword hits + @mentions, fired once a day.
- **Format:** AI summary at top, then a curated list grouped by bucket.
- **Schedule:** user-configured time, default 6:00 local. AlarmManager-driven, fires whether the app is foreground or not.
- **Multi-ship:** active ship at fire time only. Switching ships during the day doesn't change tomorrow's coverage; the alarm reads `sessionStore.activeShip()` when it fires.
- **Buckets:** unread + watchword hits + @mentions of the user. Mentions take precedence in dedupe.
- **AI off / no key:** digest still fires; summary section is omitted.
- **Window:** fixed last 24h ending at fire time.
- **Persistence:** today's digest only, overwrites on re-fire. No history view in v1.
- **Quiet days:** zero unread + zero hits + zero mentions → no notification, no row stored.
- **Weather:** today's high/low + condition emoji from open-meteo.com using last-known device GPS. Best-effort; null on failure.

## Architecture

A small set of focused units, mirroring the Watchwords feature.

### Units

- **`DailyDigestEntity` (data layer)** — Room entity, primary key `(ship, dateLocal)`.
- **`DailyDigestDao` (data layer)** — read/upsert today's row, prune older rows.
- **`DailyDigest` (facade)** — owns the schedule and generation pipeline. **Application-level**, not ship-scoped, so a single alarm covers all ships. Generation reads `sessionStore.activeShip()` at fire time and uses the active ship's database via `app.db`. The `StateFlow<DailyDigestEntity?>` for the UI is keyed on the active ship and re-keys when the ship switches.
- **`DailyDigestSettings` (settings)** — SharedPreferences-backed flags (enabled, hourOfDay, minuteOfDay). Mirrored to `%settings` bucket `"daily-digest"`.
- **`DigestAlarmReceiver` (manifest receiver)** — wakes on the daily alarm, hands off to `DailyDigest.generateAndNotify`.
- **`BootReceiver` (manifest receiver)** — listens for `BOOT_COMPLETED` and `ACTION_TIMEZONE_CHANGED`; re-arms the alarm.
- **`WeatherClient` (urbit-package neighbor)** — small OkHttp wrapper around open-meteo.com.
- **`DailyDigestSelector` (pure)** — bucketing and dedupe logic. Pure function over message + watchword data; trivially unit-testable.
- **`DailyDigestPrompt` (pure)** — formatter that turns a list of items into the AI transcript string.
- **`DailyDigestSchedule` (pure)** — `nextFireMs(now, hour, minute, zone)` math.
- **`DailyDigestScreen` (UI)** — Compose screen rendering today's digest.
- **`Notifications.showDailyDigest(...)`** — new notification helper, deep links to the digest screen.

### Manifest additions

- `<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />`
- `<receiver android:name=".DigestAlarmReceiver" android:exported="false" />`
- `<receiver android:name=".BootReceiver" android:exported="true">` listening for `BOOT_COMPLETED` + `ACTION_TIMEZONE_CHANGED`.
- No `SCHEDULE_EXACT_ALARM` (we use `setAndAllowWhileIdle`, which is inexact and permissionless).

## Data model

### Room entity

```kotlin
@Entity(
    tableName = "daily_digests",
    primaryKeys = ["ship", "dateLocal"],
)
data class DailyDigestEntity(
    val ship: String,                 // patp without ~
    val dateLocal: String,            // "yyyy-MM-dd" in user's local TZ
    val generatedAtMs: Long,          // millis when we ran
    val summaryText: String?,         // AI-written; null when AI off / failed
    val itemsJson: String,            // serialized List<DigestItem>
    val weatherJson: String?,         // serialized WeatherToday; null on failure
)
```

### Item shape (serialized JSON inside `itemsJson`)

```kotlin
@Serializable
data class DigestItem(
    val whom: String,
    val postId: String,
    val authorPatp: String,
    val sentMs: Long,
    val bucket: Bucket,               // MENTION | WATCHWORD | UNREAD
    val snippet: String,              // first 200 chars of plain text
)

enum class Bucket { MENTION, WATCHWORD, UNREAD }
```

### Weather shape

```kotlin
@Serializable
data class WeatherToday(
    val highC: Double,
    val lowC: Double,
    val conditionCode: Int,           // open-meteo WMO code
    val conditionLabel: String,       // human-readable, e.g. "Cloudy"
    val emoji: String,                // matched from a small lookup table
)
```

### DAO

```kotlin
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

### Migration

`AppDatabase` v26 → v27 adds the `daily_digests` table and registers the entity.

## Schedule

### Wiring

- `DailyDigest.scheduleNext()` is the single arming point. Called from:
  - `TalonApplication.onCreate()` (after `buildShipScoped`)
  - `DailyDigestSettings.setEnabled(true)`
  - `DailyDigestSettings.setTime(...)`
  - `DigestAlarmReceiver` in a `finally` block after `generateAndNotify` returns (or throws) — re-arms tomorrow regardless of outcome
  - `BootReceiver` on `BOOT_COMPLETED` and `ACTION_TIMEZONE_CHANGED`

- `scheduleNext` builds a `PendingIntent.getBroadcast(ctx, REQ_DIGEST, intent, FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE)` for `DigestAlarmReceiver` with action `io.nisfeb.talon.action.DAILY_DIGEST_FIRE`.
- Computes `fireMs` via `DailyDigestSchedule.nextFireMs(now, hourOfDay, minuteOfDay, ZoneId.systemDefault())`.
- Calls `alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireMs, pi)`.

### Toggling

- `setEnabled(true)`: `scheduleNext()`, push to `%settings`.
- `setEnabled(false)`: `alarmManager.cancel(pendingIntent)`, clear bucket on the ship.
- Time change while enabled: cancel + reschedule.

### `DailyDigestSchedule.nextFireMs`

Pure function. Returns the next epoch-ms at which the alarm should fire, given:

- `now: Instant`
- `hourOfDay: Int` (0..23)
- `minuteOfDay: Int` (0..59)
- `zone: ZoneId`

Logic: build a `ZonedDateTime` for today at `(hour, minute)` in `zone`. If that's already in the past or equal to now, add one day. Convert to epoch-ms.

Handles DST forward (skipped hour: `ZonedDateTime` resolves to the next valid instant) and DST back (ambiguous: picks the earlier of the two) via `ZonedDateTime.of` rules.

## Generation pipeline

`DailyDigest.generateAndNotify(reason: String)` runs on `Dispatchers.IO`. Steps:

### 1. Window math
`windowEnd = now`; `windowStart = windowEnd - 24h`. `dateLocal = LocalDate.now(zone).toString()`.

### 2. Active ship snapshot
`val ship = sessionStore.activeShip() ?: return`. Bail silently if signed out.

### 3. Build the three buckets (parallel)

```kotlin
coroutineScope {
    val mentions = async { mentionsBucket(ship, ourPatp, windowStart, windowEnd) }
    val hits = async { hitsBucket(ship, windowStart, windowEnd) }
    val unread = async { unreadBucket(ship, ourPatp, windowStart, windowEnd) }
    awaitAll(mentions, hits, unread)
    ...
}
```

- **Mentions:** rows in `messages` where `sentMs ∈ window`, `author != ourPatp`, and the plaintext (from `StoryCache.textFor`) contains `~ourPatp` as a patp-bounded substring. Patp boundary means the character following the patp must NOT be a letter or `-` — otherwise `~mister-botter` would falsely match `~mister-botter-dozzod-nisfeb`. The `DailyDigestMentionMatcher` helper handles this. Cap at 50.
- **Watchword hits:** rows in `watchword_hits` where `sentMs ∈ window`. The hits PK is `(term, whom, postId)` so the same message can appear multiple times under different terms. Dedupe at the digest level by `(whom, postId)`, keeping the first matched term for the row's badge. Cap at 50 deduped items.
- **Unread:** read counts live per-chat in `unreads` (`whom`, `count`, `recencyMs`). For each row in `unreads` where `count > 0` and `whom` is not in `notifyPrefs().mutedWhoms()` and not in `watchword_chat_excludes`, take the newest `min(count, inWindowCount)` messages from that chat where `sentMs ∈ window` and `author != ourPatp`. Cap the union at 100, ordered by `sentMs DESC`.

There is no per-message "read" flag in the DB. The unread bucket is approximated by "newest N messages from chats with unread count N, intersected with the 24h window." This can occasionally include a message you've actually read if your client and the ship's `%activity` count have drifted; the cost is one extra row in the digest, which is acceptable.

### 4. Dedupe across buckets
A row is identified by `(whom, postId)`. Mention takes precedence over watchword over unread; if a row appears in two or three buckets, keep only the highest-priority bucket. Implementation: `LinkedHashMap<Pair<String,String>, DigestItem>` written in priority order so insertion of a lower-priority bucket is a no-op when the key already exists.

Note: this dedupe is across buckets. Within-bucket dedupe (multiple watchword hits on the same message under different terms) happens during step 3's hits query.

### 5. Quiet-day check
If after dedupe all three buckets are empty, return early without storing or notifying. Per Q9-A. Re-arming for tomorrow is the receiver's responsibility (see below) so the early return doesn't lose the schedule.

### 6. Weather (best-effort)
```kotlin
val weather = withTimeoutOrNull(5_000) {
    val loc = fetchLastKnownLocation(ctx) ?: return@withTimeoutOrNull null
    weatherClient.fetchToday(loc.latitude, loc.longitude)
}
```
On any failure (no permission, null fix, network error, timeout, parse failure) → `weather = null`.

### 7. AI summary (best-effort)
If `aiSettings.dailyDigestEnabled && aiClient.hasKey`:
- Build a transcript via `DailyDigestPrompt.format(items, displayName)`.
- System prompt:
  > You are writing a brief morning digest for a chat user. Cover the day in 3-5 bullets or a short paragraph. Group by theme, not by chat. Highlight @mentions and watchword hits as the user's priorities. Do not invent information. Use only the transcript provided.
- Call `aiClient.complete(sys, transcript, maxOutputTokens = 512)`.
- On any exception (network, auth, content filter): log and proceed with `summaryText = null`.

### 8. Persist
```kotlin
dao.upsert(DailyDigestEntity(
    ship = ship,
    dateLocal = dateLocal,
    generatedAtMs = nowMs,
    summaryText = aiSummary,
    itemsJson = Json.encodeToString(items),
    weatherJson = weather?.let { Json.encodeToString(it) },
))
val yesterday = LocalDate.parse(dateLocal).minusDays(1).toString()
dao.pruneOlderThan(yesterday)
```

### 9. Notify
- Title: `"Today's brief: ${countLine(items)}"` where `countLine` returns e.g. `"17 unread, 3 mentions, 2 hits"`.
- Body: first ~120 chars of `summaryText`, or the full count line if no summary.
- Tap intent: `MainActivity` with extras `{action: "open-digest", ship, date: dateLocal}`. Existing deep-link routing handles this.
- Channel: new `CHANNEL_DAILY_DIGEST`, importance `IMPORTANCE_DEFAULT`.
- Tag: `"digest:$ship:$dateLocal"`.

### 10. (Re-arm happens in the receiver, not here)

The receiver calls `scheduleNext()` in a `finally` block AFTER `generateAndNotify` returns (or throws), regardless of whether the pipeline did any work. This way quiet days, AI failures, and exceptions all still re-arm tomorrow.

## UI

### Settings entry

New section in the AI settings screen:
- "Daily digest" group header.
- Enable toggle.
- Time picker (`TimePickerDialog`).
- Status line: `"Next: tomorrow 6:00 AM"` or `"Off"`.
- "Test now" button — calls `generateAndNotify("test-now")` immediately. Lets the user verify the pipeline without waiting.

### `DailyDigestScreen`

Accessed two ways:
1. Home overflow menu → "Today's brief" (next to "Watchwords").
2. Notification tap.

Layout, top to bottom:

1. **Weather card** (if `weatherJson` not null): emoji + condition word + `H°/L°`. Single row.
2. **AI summary card** (if `summaryText` not null): the text in a Material card. No chrome.
3. **Three sections**, in priority order:
   - `Mentions (${count})`
   - `Watchword hits (${count})`
   - `Unread (${count})`
   Each section hidden when its bucket is empty.
4. Each item row: avatar + display name + bucket-specific badge (e.g. matched watchword term) + 2-line snippet + timestamp. Tap routes to the source message in its chat (reuses watchword-feed deep-link plumbing).
5. **Empty state** (only reachable from menu, since notifications skip on quiet days):
   - If today's row is missing AND fire time has not passed: "Today's brief is scheduled for 6:00 AM." + a "Generate now" button.
   - If today's row is missing AND fire time has passed: "Nothing to brief today."
   - If today's row exists but is empty: same as above.

No pull-to-refresh. The digest is a fixed snapshot at fire time. The "Generate now" button covers manual re-runs.

## Settings sync

A new `%settings` bucket `"daily-digest"` mirrors the local prefs. Keys:

- `enabled: Boolean`
- `hourOfDay: Int`
- `minuteOfDay: Int`

Wired through the existing `SettingsSync` machinery same as the watchwords sync. The `transitionedOffSync` idiom applies: turning sync off pushes a `clear-bucket` for `"daily-digest"` so the ship doesn't retain state we no longer mirror.

The local digest **content** (`daily_digests` table) is NOT synced. Each device generates its own digest from its own data.

## Error handling

| Scenario | Behavior |
|---|---|
| Receiver runtime > 10s | `goAsync()` + run on `appScope`; `PendingResult.finish()` in finally |
| Process killed mid-generation | Partial digest not saved; tomorrow tries again |
| No active ship | Bail without storing or notifying; re-arm anyway |
| No AI key / AI off | `summaryText = null`, digest still fires |
| AI call fails | Log, proceed with `summaryText = null` |
| No location permission / no fix | `weatherJson = null`, digest still fires |
| Open-meteo down / parse fails | `weatherJson = null`, digest still fires |
| Quiet day (all three buckets empty after dedupe) | No store, no notify; alarm re-arms |
| Re-fire on same day | Upsert overwrites; notification tag replaces |
| Reboot | `BootReceiver` re-arms |
| Timezone change | `BootReceiver` re-arms (also handles DST) |
| Pre-fire screen open | "Scheduled for ${time}" + "Generate now" button |
| DAO contention | Room serializes; suspend calls only |

## Performance

- Generation runs once per day per ship. Three DB queries (capped) + at most one AI call + at most one HTTP call. Total wall time typically <2s, dominated by AI.
- `pruneOlderThan` keeps the table small (1-2 rows steady-state).
- No flows in the alarm path — only suspend reads, since flows would never terminate.
- Wake lock held for the duration of generation (~60s timeout); released in `finally`.

## Open questions / explicitly out of scope

- **History** beyond yesterday is out of v1. Re-firing overwrites today; pruning drops yesterday on each upsert. A future "past digests" view can be layered on by relaxing the pruning rule and adding a list screen.
- **Multiple ships getting separate digests** is out of v1 (Q4-B). Future work could surface a digest per ship.
- **Digest sharing** (e.g., "post today's brief to a chat") is out of v1.
- **Highlights centroid scoring** for "important" picks is out of v1 (Q5-B).
- **Pull-to-refresh** on the digest screen is intentionally absent. The "Generate now" button in settings covers manual re-runs.

## Testing

### Unit (`app/src/test/kotlin/io/nisfeb/talon/ai/`)

- `DailyDigestSelectorTest` — bucketing + dedupe correctness.
- `DailyDigestMentionMatcherTest` — `~ourpatp` substring detection edge cases: ours appears (`hello ~mister-foo, can you...`), ours doesn't, ours appears as a prefix of a longer patp (`~mister-foo-bar` should NOT match `~mister-foo`), case sensitivity (patps are lowercase by convention), multiple mentions in one message.
- `DailyDigestPromptTest` — transcript formatter: grouping, snippet truncation, empty-list placeholder.
- `DailyDigestScheduleTest` — `nextFireMs` for normal time, midnight rollover, exactly-on-fire-time, DST forward, DST back.
- `WeatherClientParseTest` — open-meteo JSON → `WeatherToday` mapping; failure JSON → null.

### Fuzz

- `DailyDigestFuzzTest` (mirroring `WatchwordFuzzTest`):
  - Selector never throws on arbitrary inputs.
  - Mention matcher never throws + idempotent.
  - Prompt formatter never throws + bounded output length.

### Mutation

- Add `app/src/main/java/io/nisfeb/talon/ai/DailyDigest*.kt` to `scripts/mutate/mutate.sh` default targets.

### Integration

- Manual smoke: enable digest, hit "Test now", confirm notification fires, open the screen, verify the three buckets render correctly, verify weather appears when location is available.
- Verify boot survival: enable, reboot device, confirm next-fire status line still shows the right time.
- Verify quiet day: with no unread / hits / mentions in last 24h, "Test now" produces no notification (UI shows "Nothing to brief today.").
