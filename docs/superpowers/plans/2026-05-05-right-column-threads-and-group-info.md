# Right column for threads + group info + media drilldowns (Phase 3) — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fill `DesktopShell.rightSidebar` (reserved in Phase 2) with a single host that renders one of: a thread, a group-info panel, or a category drilldown. Mobile linearises the same states via `PlatformBackHandler` stack. Add a per-chat info icon to the header on both platforms.

**Architecture:** `RightPaneContent` sealed type computed at render time from flat App.kt state vars. Mutual exclusion enforced at write sites. New `MessageMediaEntity` + DAO + migration 29→30 with backfill worker. Categorisation via `MediaClassifier` (7 buckets: photo / video / gif / voice / audio / file / link).

**Tech Stack:** Room 2.7 KMP (composite primary key + index for the new table), kotlinx.coroutines.flow.StateFlow, kotlinx.serialization.json (existing message JSON parsing via `Story` model), Material3 + materialIconsCore (info icon — `Icons.Filled.Info`).

**Spec:** [`docs/superpowers/specs/2026-05-05-right-column-threads-and-group-info-design.md`](../specs/2026-05-05-right-column-threads-and-group-info-design.md)

---

## File structure

**Create:**

| Path | Responsibility |
|---|---|
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/data/MessageMediaEntity.kt` | Room entity for derived media index |
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/data/MessageMediaDao.kt` | DAO: counts, category-filtered streams, `replaceForMessage` |
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/urbit/MediaCategory.kt` | Enum + serialisation helpers |
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/urbit/MediaClassifier.kt` | `extractMedia(MessageEntity): List<MessageMediaEntity>` |
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/urbit/MediaBackfillWorker.kt` | One-shot scan-and-populate worker |
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/RightPaneContent.kt` | Sealed type for what the right pane is showing |
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/RightPaneHost.kt` | Dispatcher composable, used as `DesktopShell.rightSidebar` |
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/GroupInfoPane.kt` | Pane body: header + mute + members + media stats + leave |
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/GroupInfoScreen.kt` | Mobile full-screen wrapper around `GroupInfoPane` |
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/MediaListPane.kt` | LazyColumn for one `MediaCategory` |
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/MediaListScreen.kt` | Mobile full-screen wrapper around `MediaListPane` |
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/ThreadList.kt` | Extracted list body of `ThreadScreen` (Phase 2 pattern) |
| `composeApp/src/commonTest/kotlin/io/nisfeb/talon/urbit/MediaClassifierTest.kt` | Categorisation rules |
| `composeApp/src/desktopTest/kotlin/io/nisfeb/talon/data/MessageMediaDaoTest.kt` | DAO round-trip + queries |
| `composeApp/src/desktopTest/kotlin/io/nisfeb/talon/urbit/MediaBackfillWorkerTest.kt` | Backfill correctness + idempotency |
| `composeApp/src/desktopTest/kotlin/io/nisfeb/talon/ui/RightPaneHostTest.kt` | State-machine transitions |
| `composeApp/src/desktopTest/kotlin/io/nisfeb/talon/ui/screens/GroupInfoPaneTest.kt` | Render: zero-count rows hidden |

**Modify:**

| Path | What |
|---|---|
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/data/AppDatabase.kt` | Add `MessageMediaEntity` + DAO, bump version 29→30, add migration |
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/urbit/PostIngest.kt` | Call `MediaClassifier.extractMedia(...)` + `replaceForMessage` in the same transaction as message insert |
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/ThreadScreen.kt` | Refactor wrapper to delegate to new `ThreadList`; widen private helpers to internal |
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/DmChatScreen.kt` | Add info icon button in chat header; add `onOpenGroupInfo: () -> Unit` parameter; new `chatShape: ChatShape` parameter to gate icon visibility |
| `composeApp/src/commonMain/kotlin/io/nisfeb/talon/compose/App.kt` | New state vars; `RightPaneContent?` computation; mobile back-stack handlers; new `DesktopShell(rightSidebar = ...)` slot; refactor `detailSlot` to drop thread branch on wide |
| `RELEASE.md` | Phase 3 smoke checklist |
| `composeApp/build.gradle.kts` | Bump `talonVersionCode` / `talonVersionName` to `0.10.0-rc1` |

---

## Phase 1 — Categorisation foundation

### Task 1.1: `MediaCategory` enum

**Files:**
- Create: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/urbit/MediaCategory.kt`

- [ ] **Step 1: Write the file**

```kotlin
package io.nisfeb.talon.urbit

/**
 * Buckets that [MediaClassifier.extractMedia] sorts inline-content URLs
 * into. Powers the group-info shared-media stats grid (Phase 3) and
 * the per-bucket drilldown list. Order matches the stats grid's
 * top-to-bottom rendering order.
 *
 * Adding a new bucket is a four-touch change:
 *  1. Add the enum value here.
 *  2. Add a rule to [MediaClassifier.extractMedia].
 *  3. Add an icon + label to [GroupInfoPane]'s grid.
 *  4. Add a tap handler to [MediaListPane] (image viewer / player /
 *     uri / etc.) — most new buckets reuse an existing handler.
 */
enum class MediaCategory { Photo, Video, Gif, Voice, Audio, File, Link }

/**
 * Persistence helper. Falls back to [Link] on any parse failure so a
 * future enum rename doesn't blow up rows that pre-date the change.
 */
fun mediaCategoryOrLink(name: String?): MediaCategory {
    if (name.isNullOrBlank()) return MediaCategory.Link
    return runCatching { MediaCategory.valueOf(name) }.getOrDefault(MediaCategory.Link)
}
```

- [ ] **Step 2: Compile**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :composeApp:compileKotlinDesktop`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/urbit/MediaCategory.kt
git commit -m "data: MediaCategory enum + parse-fallback helper"
```

---

### Task 1.2: `MessageMediaEntity`

**Files:**
- Create: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/data/MessageMediaEntity.kt`

- [ ] **Step 1: Write the file**

```kotlin
package io.nisfeb.talon.data

import androidx.room.Entity
import androidx.room.Index

/**
 * Derived media index: one row per (message, url) where the message
 * mentions a categorisable URL or first-class image. Populated in the
 * same transaction as [MessageEntity] inserts via [PostIngest], plus a
 * one-shot backfill on first launch after upgrade.
 *
 * The composite PK on (whom, messageId, url) lets a single message
 * contribute multiple rows (a post with three image URLs lands as
 * three rows). Duplicate URLs in the same message are de-duplicated
 * to one row by the PK.
 *
 * The (whom, category, sentMs) index covers both queries the UI
 * issues: the grouped count (`SELECT category, COUNT(*) GROUP BY`)
 * and the per-category drilldown (`WHERE whom AND category ORDER BY
 * sentMs DESC`).
 */
@Entity(
    tableName = "message_media",
    primaryKeys = ["whom", "messageId", "url"],
    indices = [Index(value = ["whom", "category", "sentMs"])],
)
data class MessageMediaEntity(
    val whom: String,
    val messageId: String,
    val url: String,
    val category: String,        // MediaCategory.name
    val displayText: String?,    // e.g. "🎙 Voice 12s"; null if URL is bare
    val sentMs: Long,
    val author: String,
)
```

- [ ] **Step 2: Compile**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :composeApp:compileKotlinDesktop`
Expected: BUILD SUCCESSFUL.

(The entity isn't yet wired into `AppDatabase` — that happens in Task 2.2. Until then it compiles standalone but Room's annotation processor doesn't see it.)

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/data/MessageMediaEntity.kt
git commit -m "data: MessageMediaEntity for derived media index"
```

---

### Task 1.3: `MediaClassifier` + tests

**Files:**
- Create: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/urbit/MediaClassifier.kt`
- Create: `composeApp/src/commonTest/kotlin/io/nisfeb/talon/urbit/MediaClassifierTest.kt`

- [ ] **Step 1: Read existing helpers**

`StoryRenderer.kt` already has `classifyMediaUrl(url: String): MediaKind?` for AUDIO / VIDEO. Reuse the lowercase-and-trim pattern but write a fresh classifier — the Phase-3 buckets are different (gif and image are distinct; voice and audio are distinct; files have their own list).

Run:
```
grep -n 'fun classifyMediaUrl\|substringBefore' composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/StoryRenderer.kt
```
The pattern to mirror: `url.lowercase().substringBefore('?').substringBefore('#')`.

- [ ] **Step 2: Write the classifier**

```kotlin
package io.nisfeb.talon.urbit

import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.data.MessageMediaEntity

/**
 * Walks a parsed [MessageEntity]'s [Story] and emits one
 * [MessageMediaEntity] per categorisable URL or first-class image.
 *
 * Rules apply in order; first match wins. Extension matching is
 * case-insensitive and ignores `?query` and `#fragment`.
 */
object MediaClassifier {

    private val IMAGE_EXTS = setOf(".jpg", ".jpeg", ".png", ".webp")
    private val VIDEO_EXTS = setOf(".mp4", ".webm", ".mov", ".m4v")
    private val AUDIO_EXTS = setOf(".mp3", ".m4a", ".aac", ".wav", ".ogg", ".flac")
    private val FILE_EXTS = setOf(
        ".pdf", ".zip", ".doc", ".docx", ".xls", ".xlsx",
        ".ppt", ".pptx", ".txt", ".csv", ".tar", ".gz", ".7z", ".rar",
    )
    private const val VOICE_PREFIX = "🎙"

    fun extractMedia(message: MessageEntity): List<MessageMediaEntity> {
        if (message.isDeleted) return emptyList()
        val story = parseStoryOrNull(message.contentJson) ?: return emptyList()
        val rows = mutableListOf<MessageMediaEntity>()
        val seenUrls = mutableSetOf<String>()
        for (part in story) {
            collect(part, message, rows, seenUrls)
        }
        return rows
    }

    private fun collect(
        part: StoryPart,
        message: MessageEntity,
        out: MutableList<MessageMediaEntity>,
        seen: MutableSet<String>,
    ) {
        when (part) {
            is StoryPart.Image -> {
                val src = part.src
                if (src in seen) return
                seen += src
                out += row(message, src, displayText = part.alt, category = categoryForImage(src))
            }
            is StoryPart.Text -> {
                val anns = part.text.getStringAnnotations(URL_TAG, 0, part.text.length)
                for (a in anns) {
                    val url = a.item
                    if (url in seen) continue
                    seen += url
                    val displayText = part.text.substring(a.start, a.end).takeIf { it != url }
                    out += row(message, url, displayText, categoryForUrl(url, displayText))
                }
            }
            is StoryPart.LinkPreview -> {
                val url = part.url
                if (url in seen) return
                seen += url
                out += row(message, url, part.title, categoryForUrl(url, part.title))
            }
            else -> Unit  // Code, Citation, widgets — never produce media rows
        }
    }

    /**
     * `StoryPart.Image` is always Photo OR Gif. `.gif` files don't get
     * the inline-image treatment in StoryRenderer (they fall through),
     * but a hosted gif uploaded as an image attachment still arrives
     * as `StoryPart.Image`. Treat `.gif` as Gif regardless.
     */
    private fun categoryForImage(url: String): String =
        if (canonicalExt(url) == ".gif") MediaCategory.Gif.name
        else MediaCategory.Photo.name

    private fun categoryForUrl(url: String, displayText: String?): String {
        val ext = canonicalExt(url)
        return when {
            ext == ".gif" -> MediaCategory.Gif.name
            ext in IMAGE_EXTS -> MediaCategory.Photo.name
            ext in VIDEO_EXTS -> MediaCategory.Video.name
            ext in AUDIO_EXTS && displayText?.startsWith(VOICE_PREFIX) == true ->
                MediaCategory.Voice.name
            ext in AUDIO_EXTS -> MediaCategory.Audio.name
            ext in FILE_EXTS -> MediaCategory.File.name
            else -> MediaCategory.Link.name
        }
    }

    /**
     * Lowercase the URL and chop the query string + fragment, then
     * return the last `.foo` substring. Returns "" if none.
     */
    private fun canonicalExt(url: String): String {
        val cleaned = url.lowercase().substringBefore('?').substringBefore('#')
        val dot = cleaned.lastIndexOf('.')
        if (dot < 0) return ""
        val slash = cleaned.lastIndexOf('/')
        if (slash > dot) return ""  // dot is in the path, not the filename
        return cleaned.substring(dot)
    }

    private fun row(
        message: MessageEntity,
        url: String,
        displayText: String?,
        category: String,
    ) = MessageMediaEntity(
        whom = message.whom,
        messageId = message.id,
        url = url,
        category = category,
        displayText = displayText,
        sentMs = message.sentMs,
        author = message.author,
    )

    /**
     * Parse the message's contentJson into a list of StoryPart, or
     * null if it's malformed / empty. Wraps `Story.parse(JsonElement?)`
     * (in `composeApp/src/commonMain/kotlin/io/nisfeb/talon/urbit/Story.kt`)
     * with safe-parse for the raw JSON string.
     */
    private fun parseStoryOrNull(contentJson: String): List<StoryPart>? {
        val element = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(contentJson)
        }.getOrNull() ?: return null
        return Story.parse(element).takeIf { it.isNotEmpty() }
    }
}
```

- [ ] **Step 3: Compile**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :composeApp:compileKotlinDesktop`
Expected: BUILD SUCCESSFUL — but only after the `TODO` is replaced.

- [ ] **Step 4: Write tests**

```kotlin
package io.nisfeb.talon.urbit

import io.nisfeb.talon.data.MessageEntity
import kotlin.test.Test
import kotlin.test.assertEquals

class MediaClassifierTest {

    private fun textMessage(json: String): MessageEntity = MessageEntity(
        whom = "~zod", id = "~zod/1.000",
        author = "~zod", sentMs = 0L,
        contentJson = json, kind = "/chat",
    )

    /** Build a Tlon-shaped story (JsonArray of verses) containing one
     *  inline link with the given display text + url. Shape verified
     *  against `Story.parse` at `Story.kt:253` — `content` is a plain
     *  string, not an array. */
    private fun linkVerse(displayText: String, url: String): String =
        """[{"inline":[{"link":{"href":"$url","content":"$displayText"}}]}]"""

    /** One verse with a `block.image`. Shape verified against
     *  `Story.parse` at `Story.kt:401` — `src`, `width`, `height`,
     *  `alt` keys; `width`/`height` are `Long`. */
    private fun imageVerse(src: String, alt: String = ""): String =
        """[{"block":{"image":{"src":"$src","alt":"$alt","width":0,"height":0}}}]"""

    @Test
    fun `photo extensions land as Photo`() {
        val rows = MediaClassifier.extractMedia(textMessage(linkVerse("img", "https://x.com/a.jpg")))
        assertEquals("Photo", rows.single().category)
    }

    @Test
    fun `gif extension lands as Gif regardless of context`() {
        val rows = MediaClassifier.extractMedia(textMessage(linkVerse("party", "https://x.com/cat.gif")))
        assertEquals("Gif", rows.single().category)
    }

    @Test
    fun `gif image attachment also lands as Gif`() {
        val rows = MediaClassifier.extractMedia(textMessage(imageVerse("https://x.com/cat.gif")))
        assertEquals("Gif", rows.single().category)
    }

    @Test
    fun `mp4 lands as Video`() {
        val rows = MediaClassifier.extractMedia(textMessage(linkVerse("clip", "https://x.com/c.mp4")))
        assertEquals("Video", rows.single().category)
    }

    @Test
    fun `mp3 with voice prefix lands as Voice`() {
        val rows = MediaClassifier.extractMedia(textMessage(linkVerse("🎙 Voice 12s", "https://x.com/v.mp3")))
        assertEquals("Voice", rows.single().category)
    }

    @Test
    fun `mp3 without voice prefix lands as Audio`() {
        val rows = MediaClassifier.extractMedia(textMessage(linkVerse("song", "https://x.com/s.mp3")))
        assertEquals("Audio", rows.single().category)
    }

    @Test
    fun `pdf lands as File`() {
        val rows = MediaClassifier.extractMedia(textMessage(linkVerse("doc", "https://x.com/r.pdf")))
        assertEquals("File", rows.single().category)
    }

    @Test
    fun `bare url lands as Link`() {
        val rows = MediaClassifier.extractMedia(textMessage(linkVerse("site", "https://x.com/article")))
        assertEquals("Link", rows.single().category)
    }

    @Test
    fun `query string and fragment do not affect extension match`() {
        val rows = MediaClassifier.extractMedia(textMessage(linkVerse("img", "https://x.com/a.jpg?w=200#crop")))
        assertEquals("Photo", rows.single().category)
    }

    @Test
    fun `mixed case extension matches`() {
        val rows = MediaClassifier.extractMedia(textMessage(linkVerse("img", "https://x.com/a.JPG")))
        assertEquals("Photo", rows.single().category)
    }

    @Test
    fun `dot in path but no extension is Link`() {
        // A bare URL like https://example.com/v1.0/api/users — the dot
        // is in the path, not a filename extension. Should fall to Link.
        val rows = MediaClassifier.extractMedia(textMessage(linkVerse("api", "https://x.com/v1.0/api")))
        assertEquals("Link", rows.single().category)
    }

    @Test
    fun `empty content yields no rows`() {
        val rows = MediaClassifier.extractMedia(textMessage("""[{"inline":[]}]"""))
        assertEquals(emptyList(), rows)
    }

    @Test
    fun `deleted message yields no rows`() {
        val m = textMessage(linkVerse("img", "https://x.com/a.jpg")).copy(isDeleted = true)
        assertEquals(emptyList(), MediaClassifier.extractMedia(m))
    }

    @Test
    fun `duplicate urls in one message dedupe to one row`() {
        // Two verses, both linking the same URL with different display
        // text. Should dedupe to one media row.
        val json = """[
            {"inline":[{"link":{"href":"https://x.com/a.jpg","content":"a"}}]},
            {"inline":[{"link":{"href":"https://x.com/a.jpg","content":"b"}}]}
        ]"""
        val rows = MediaClassifier.extractMedia(textMessage(json))
        assertEquals(1, rows.size)
    }
}
```

The `linkVerse` and `imageVerse` shapes above are verified against `Story.parse` (lines 253 and 401 of `Story.kt`). If existing tests already include a story-fixture builder, prefer reusing it — search:

```
grep -rn 'storyJson\|verseJson\|fun.*[Ss]tory.*\bJson\b' composeApp/src/commonTest composeApp/src/desktopTest --include='*.kt'
```

If nothing turns up, the inline helpers above are sufficient.

- [ ] **Step 5: Run tests**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :composeApp:desktopTest --tests 'io.nisfeb.talon.urbit.MediaClassifierTest'`
Expected: 14 tests pass.

If a test fails because of JSON envelope mismatch, fix the helpers in Step 4 — the rules in `MediaClassifier` itself should be correct.

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/urbit/MediaClassifier.kt \
        composeApp/src/commonTest/kotlin/io/nisfeb/talon/urbit/MediaClassifierTest.kt
git commit -m "urbit: MediaClassifier — 7-bucket categorisation of message media"
```

---

## Phase 2 — Persistence + ingest hooks

### Task 2.1: `MessageMediaDao`

**Files:**
- Create: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/data/MessageMediaDao.kt`

- [ ] **Step 1: Write the DAO**

```kotlin
package io.nisfeb.talon.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Reads + writes for the derived media index.
 *
 * - [streamCounts] backs the group-info stats grid.
 * - [streamCategory] backs the drilldown LazyColumn.
 * - [replaceForMessage] is the single mutator path; called from
 *   [PostIngest] on insert/edit and from the backfill worker.
 */
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

    @Query("DELETE FROM message_media WHERE whom = :whom AND messageId = :id")
    suspend fun deleteForMessage(whom: String, id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<MessageMediaEntity>)

    @Transaction
    suspend fun replaceForMessage(
        whom: String,
        messageId: String,
        rows: List<MessageMediaEntity>,
    ) {
        deleteForMessage(whom, messageId)
        if (rows.isNotEmpty()) insertAll(rows)
    }

    @Query("SELECT COUNT(*) FROM message_media")
    suspend fun totalCount(): Int
}

data class CategoryCount(val category: String, val n: Int)
```

- [ ] **Step 2: Compile**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :composeApp:compileKotlinDesktop`
Expected: BUILD SUCCESSFUL. (Room's annotation processor doesn't see the DAO until Task 2.2 wires it into `AppDatabase`; the file compiles standalone.)

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/data/MessageMediaDao.kt
git commit -m "data: MessageMediaDao — counts, category streams, replace-for-message"
```

---

### Task 2.2: Wire into `AppDatabase` + migration 29→30

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/data/AppDatabase.kt`

- [ ] **Step 1: Inspect current `AppDatabase`**

Run: `grep -n 'version =\|@Database\|abstract fun\|migrations\|MIGRATION_' composeApp/src/commonMain/kotlin/io/nisfeb/talon/data/AppDatabase.kt`
Note the:
- `version = 29` (will become `30`)
- entity list inside `@Database(entities = [...])`
- abstract DAO accessors
- existing migration list (where `MIGRATION_28_29` etc. live — find the convention)

- [ ] **Step 2: Add the entity to `@Database`**

In the `@Database(entities = [...])` array, add `MessageMediaEntity::class` (alphabetised or appended — match the existing style).

Bump `version = 29` to `version = 30`.

- [ ] **Step 3: Add the abstract DAO accessor**

In the abstract class body, next to the other `abstract fun ...Dao(): ...` declarations:

```kotlin
abstract fun messageMediaDao(): MessageMediaDao
```

Match the existing naming convention — if the file uses `fun messages(): MessageDao` instead of `messagesDao()`, follow that. Search: `grep -n 'abstract fun' composeApp/src/commonMain/kotlin/io/nisfeb/talon/data/AppDatabase.kt`.

- [ ] **Step 4: Add the migration**

Find where existing migrations are declared (search for `Migration(28, 29)` or similar). Add:

```kotlin
val MIGRATION_29_30 = object : Migration(29, 30) {
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

Add `MIGRATION_29_30` to whatever migration list/array the database builder uses (search for `addMigrations(`).

If the imports are missing, add: `import androidx.room.migration.Migration`, `import androidx.sqlite.db.SupportSQLiteDatabase`.

- [ ] **Step 5: Schema export**

Room exports schema JSON under `composeApp/schemas/<DB-class-fqcn>/30.json`. The build will fail on `assembleDebug` if the schema doesn't match the actual DB layout. To regenerate:

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :composeApp:compileDebugKotlinAndroid`

If the Room compiler complains about the schema, look at the generated `.json` in `build/` and copy it to `composeApp/schemas/`. (Schema JSON is checked in.)

- [ ] **Step 6: Compile both targets + run tests**

```
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :composeApp:compileKotlinDesktop :composeApp:compileDebugKotlinAndroid :composeApp:desktopTest
```
Expected: BUILD SUCCESSFUL.

If existing tests fail on the migration (e.g., a test that builds an in-memory database expects version 29), update the test's expected version. Search: `grep -rn 'version = 29\|version=29\|.version(29' composeApp/src --include='*.kt'`.

- [ ] **Step 7: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/data/AppDatabase.kt \
        composeApp/schemas/
git commit -m "data: AppDatabase 29→30 — message_media table"
```

---

### Task 2.3: `PostIngest` writes media rows on insert/edit/delete

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/urbit/PostIngest.kt`

- [ ] **Step 1: Inspect `PostIngest.kt` to find insertion sites**

Run: `grep -n 'fun ingest\|insertMessage\|messages()\|insert\|update\|delete' composeApp/src/commonMain/kotlin/io/nisfeb/talon/urbit/PostIngest.kt | head -40`

Identify:
- The function(s) that insert messages.
- The function(s) that update / edit messages.
- The function(s) that delete (or mark deleted) messages.

`PostIngest` likely has a single `ingest(message: MessageEntity)` entry point + edit/delete branches.

- [ ] **Step 2: Add a `MessageMediaDao` parameter**

Wherever `PostIngest` accepts a `MessageDao`, also accept a `MessageMediaDao`. If `PostIngest` is a class, add it to the constructor; if it's an object/top-level function, add it to the function signature. The single new parameter chains through to all the callers (`TlonChatRepo`, etc.) — they each get an `mediaDao = db.messageMediaDao()` argument.

- [ ] **Step 3: Hook media extraction into the insert path**

After every successful `messages.insert(message)` / `messages.upsert(message)`, in the same `withTransaction` (or `runInTransaction`) block:

```kotlin
val mediaRows = MediaClassifier.extractMedia(message)
mediaDao.replaceForMessage(message.whom, message.id, mediaRows)
```

If the existing path doesn't have a transaction wrapper, wrap insert + replaceForMessage together so a partial state can't leak. Look at how `MessageDao` does it for reactions / unread updates — match that.

- [ ] **Step 4: Hook into the delete / mark-deleted path**

For deletions (Tlon has a "delete" semantic that marks `isDeleted = true`), call:

```kotlin
mediaDao.replaceForMessage(whom, messageId, emptyList())
```

This wipes any media rows the message previously contributed.

- [ ] **Step 5: Update callers**

Compile errors will tell you where to thread the new `MessageMediaDao` parameter. The likely callers:
- `TlonChatRepo`
- Any other file that constructs `PostIngest`

Run:
```
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :composeApp:compileKotlinDesktop :composeApp:compileDebugKotlinAndroid
```
Walk the errors. Each is a one-line change at a call site.

- [ ] **Step 6: Run all tests**

```
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :composeApp:desktopTest
```
Expected: BUILD SUCCESSFUL. Existing tests that exercise `PostIngest` may need an `MessageMediaDao` mock — pass `db.messageMediaDao()` from the test's in-memory DB.

If a test fails because it now sees media rows it didn't expect, that's a sign the test's fixture data has URLs in it. Either accept the new rows (assert on them) or use a URL-free fixture.

- [ ] **Step 7: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/urbit/PostIngest.kt
# Plus any caller files that needed updating:
git add <files-the-compile-errors-flagged>
git commit -m "ingest: write message_media rows on insert/edit/delete"
```

---

## Phase 3 — Backfill

### Task 3.1: `MediaBackfillWorker`

**Files:**
- Create: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/urbit/MediaBackfillWorker.kt`

- [ ] **Step 1: Write the worker**

```kotlin
package io.nisfeb.talon.urbit

import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.Log
import io.nisfeb.talon.data.MessageEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * One-shot scan that walks every existing `messages` row and writes
 * `message_media` rows. Skipped if `message_media` already has rows
 * (idempotent — the only scenario where we want to re-run is a bug
 * fix to [MediaClassifier], in which case the developer manually
 * truncates `message_media` and we re-populate).
 *
 * Runs on [Dispatchers.IO] in 1000-message chunks so the UI thread
 * doesn't block. Each chunk commits in its own transaction; Room's
 * flows fire as the chunks land, so the group-info stats grid
 * updates live as the backfill progresses.
 */
object MediaBackfillWorker {

    private const val CHUNK_SIZE = 1000

    /**
     * Launch the backfill on [scope]. No-op if already populated.
     * Safe to call multiple times — second invocation is a no-op
     * after the first completes.
     */
    fun launchIfNeeded(scope: CoroutineScope, db: AppDatabase) {
        scope.launch(Dispatchers.IO) {
            runIfNeeded(db)
        }
    }

    /** Suspend variant for tests + manual triggers. */
    suspend fun runIfNeeded(db: AppDatabase) {
        val mediaDao = db.messageMediaDao()
        if (mediaDao.totalCount() > 0) {
            Log.i("MediaBackfill", "skip — message_media already populated")
            return
        }
        var offset = 0
        var totalProcessed = 0
        while (true) {
            // Implementer: locate the right MessageDao query for "all
            // messages with limit + offset". If MessageDao doesn't have
            // it, add a query like:
            //   @Query("SELECT * FROM messages ORDER BY sentMs LIMIT :limit OFFSET :offset")
            //   suspend fun all(limit: Int, offset: Int): List<MessageEntity>
            val chunk: List<MessageEntity> = db.messages().all(CHUNK_SIZE, offset)
            if (chunk.isEmpty()) break
            val rows = chunk.flatMap { MediaClassifier.extractMedia(it) }
            if (rows.isNotEmpty()) {
                mediaDao.insertAll(rows)
            }
            totalProcessed += chunk.size
            offset += CHUNK_SIZE
            Log.i("MediaBackfill", "processed=$totalProcessed mediaRows=${rows.size}")
        }
        Log.i("MediaBackfill", "complete — processed=$totalProcessed")
    }
}
```

**IMPORTANT:** the `db.messages().all(CHUNK_SIZE, offset)` line assumes a paginated `all` query exists on `MessageDao`. Run:
```
grep -n 'fun all\|stream\|@Query' composeApp/src/commonMain/kotlin/io/nisfeb/talon/data/MessageDao.kt | head -20
```

If no equivalent exists, add one as part of this task (it's a one-line `@Query`):

```kotlin
@Query("SELECT * FROM messages ORDER BY sentMs LIMIT :limit OFFSET :offset")
suspend fun all(limit: Int, offset: Int): List<MessageEntity>
```

The chunk-with-offset pattern works because we're scanning a non-mutating snapshot — new messages arrive via the live ingest path which already populates media.

- [ ] **Step 2: Wire into App startup**

Find the App.kt site where the `AppDatabase` is constructed (search `grep -n 'AppDatabase\b' composeApp/src/commonMain/kotlin/io/nisfeb/talon/compose/App.kt | head -10`). After the database is opened and the initial UI has rendered (i.e., inside a `LaunchedEffect(Unit)` or similar), call:

```kotlin
LaunchedEffect(Unit) {
    MediaBackfillWorker.runIfNeeded(db)
}
```

Place this near the existing `LaunchedEffect(Unit)` blocks that do startup work (e.g., the initial `repo.bootstrap()` call). The exact location doesn't matter — what matters is that it runs once per process launch and doesn't block initial UI render. The `LaunchedEffect(Unit)` body runs after the first composition.

- [ ] **Step 3: Compile + run existing tests**

```
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :composeApp:compileKotlinDesktop :composeApp:compileDebugKotlinAndroid :composeApp:desktopTest
```
Expected: BUILD SUCCESSFUL. Existing tests should still pass (the backfill never runs in tests because they construct fresh empty DBs).

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/urbit/MediaBackfillWorker.kt \
        composeApp/src/commonMain/kotlin/io/nisfeb/talon/data/MessageDao.kt \
        composeApp/src/commonMain/kotlin/io/nisfeb/talon/compose/App.kt
git commit -m "urbit: MediaBackfillWorker — populate message_media for legacy messages"
```

---

### Task 3.2: Backfill tests

**Files:**
- Create: `composeApp/src/desktopTest/kotlin/io/nisfeb/talon/urbit/MediaBackfillWorkerTest.kt`

- [ ] **Step 1: Write the tests**

```kotlin
package io.nisfeb.talon.urbit

import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.MessageEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MediaBackfillWorkerTest {

    private lateinit var db: AppDatabase

    @BeforeTest
    fun setUp() {
        // Implementer: use whichever in-memory DB factory existing tests
        // use. Search desktopTest for `AppDatabase` constructions:
        //   grep -n 'AppDatabase\b' composeApp/src/desktopTest --include='*.kt' -r
        // If there's a `TestDb.create()` helper, use it. Otherwise:
        //   db = Room.inMemoryDatabaseBuilder(...).build()
        db = TODO("wire to existing in-memory DB factory")
    }

    @AfterTest
    fun tearDown() { db.close() }

    @Test
    fun `empty messages table yields no media rows`() = runBlocking {
        MediaBackfillWorker.runIfNeeded(db)
        assertEquals(0, db.messageMediaDao().totalCount())
    }

    @Test
    fun `seeded messages get media rows`() = runBlocking {
        // Implementer: build a couple of MessageEntity with a known
        // contentJson that MediaClassifier extracts at least one row
        // from. Use the same JSON helpers as MediaClassifierTest.
        val m1 = TODO("MessageEntity with one image URL") as MessageEntity
        val m2 = TODO("MessageEntity with one PDF link") as MessageEntity
        db.messages().insert(m1)
        db.messages().insert(m2)
        MediaBackfillWorker.runIfNeeded(db)
        assertEquals(2, db.messageMediaDao().totalCount())
    }

    @Test
    fun `idempotent — second run is a no-op`() = runBlocking {
        // Insert one message, run backfill, hand-edit a media row to
        // a sentinel value, run backfill again, sentinel survives.
        val m = TODO("MessageEntity with one image URL") as MessageEntity
        db.messages().insert(m)
        MediaBackfillWorker.runIfNeeded(db)
        val before = db.messageMediaDao().totalCount()
        assertTrue(before > 0)
        // Second run must be skipped (totalCount > 0 short-circuits).
        MediaBackfillWorker.runIfNeeded(db)
        assertEquals(before, db.messageMediaDao().totalCount())
    }
}
```

**IMPORTANT:** the `TODO()` calls are placeholders the implementer must replace with real fixtures. Search desktopTest for existing fixture helpers — there's likely a `TestDb.create()` helper or a `MessageEntity.dummy(...)` helper from prior phases. Reuse rather than reinvent.

- [ ] **Step 2: Run tests**

```
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :composeApp:desktopTest --tests 'io.nisfeb.talon.urbit.MediaBackfillWorkerTest'
```
Expected: 3 tests pass.

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/desktopTest/kotlin/io/nisfeb/talon/urbit/MediaBackfillWorkerTest.kt
git commit -m "test(urbit): MediaBackfillWorker correctness + idempotency"
```

---

## Phase 4 — Right-pane state machine + host

### Task 4.1: `RightPaneContent` sealed type

**Files:**
- Create: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/RightPaneContent.kt`

- [ ] **Step 1: Write the file**

```kotlin
package io.nisfeb.talon.ui

import io.nisfeb.talon.urbit.MediaCategory

/**
 * What the right column ([DesktopShell.rightSidebar] on wide, full-
 * screen on compact) is showing right now. Computed at render time
 * from App.kt's flat state vars; mutual exclusion is enforced at
 * write sites in App.kt — opening a thread clears group-info state
 * and vice versa.
 *
 * `null` = pane closed; on wide that means no fourth column is
 * rendered; on compact that means we're in the chat detail view.
 */
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

- [ ] **Step 2: Compile**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :composeApp:compileKotlinDesktop`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/RightPaneContent.kt
git commit -m "ui: RightPaneContent sealed type for right-pane state"
```

---

### Task 4.2: `RightPaneHost` composable

**Files:**
- Create: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/RightPaneHost.kt`

- [ ] **Step 1: Write the host**

```kotlin
package io.nisfeb.talon.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.urbit.MediaCategory
import io.nisfeb.talon.urbit.TlonChatRepo

/**
 * Right-column content dispatcher. Used as [DesktopShell.rightSidebar]
 * on wide windows. On compact, the same surfaces are wrapped by their
 * full-screen `*Screen` siblings (`GroupInfoScreen`, `MediaListScreen`,
 * `ThreadScreen`) and the host is unused.
 *
 * Each branch renders a header (title + close button) and the matching
 * pane body. The header is the host's responsibility, not the pane's,
 * so the same pane composables can be reused full-screen with their
 * own back-arrow header on compact.
 */
@Composable
fun RightPaneHost(
    content: RightPaneContent,
    db: AppDatabase,
    repo: TlonChatRepo,
    ourPatp: String,
    onClose: () -> Unit,
    onOpenCategory: (MediaCategory) -> Unit,
    onLeaveCategoryDrilldown: () -> Unit,
    onOpenConversation: (whom: String) -> Unit,
    onOpenImage: (url: String) -> Unit,
    onOpenMembers: (whom: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val title = when (content) {
                is RightPaneContent.Thread -> "Thread"
                is RightPaneContent.GroupInfo -> "Info"
                is RightPaneContent.GroupInfoDrilldown -> categoryLabel(content.category)
            }
            Text(
                title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            IconButton(
                onClick = if (content is RightPaneContent.GroupInfoDrilldown) onLeaveCategoryDrilldown
                          else onClose,
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Close")
            }
        }
        HorizontalDivider()
        Box(modifier = Modifier.fillMaxSize()) {
            when (content) {
                is RightPaneContent.Thread -> io.nisfeb.talon.ui.screens.ThreadList(
                    db = db,
                    repo = repo,
                    ourPatp = ourPatp,
                    whom = content.whom,
                    parentId = content.parentId,
                    initialScrollReplyId = content.replyAnchor,
                    onOpenConversation = onOpenConversation,
                    onOpenImage = onOpenImage,
                )
                is RightPaneContent.GroupInfo -> io.nisfeb.talon.ui.screens.GroupInfoPane(
                    db = db,
                    repo = repo,
                    whom = content.whom,
                    onOpenCategory = onOpenCategory,
                    onOpenMembers = { onOpenMembers(content.whom) },
                )
                is RightPaneContent.GroupInfoDrilldown -> io.nisfeb.talon.ui.screens.MediaListPane(
                    db = db,
                    whom = content.whom,
                    category = content.category,
                    onOpenImage = onOpenImage,
                )
            }
        }
    }
}

private fun categoryLabel(c: MediaCategory): String = when (c) {
    MediaCategory.Photo -> "Photos"
    MediaCategory.Video -> "Videos"
    MediaCategory.Gif -> "GIFs"
    MediaCategory.Voice -> "Voice messages"
    MediaCategory.Audio -> "Audio"
    MediaCategory.File -> "Files"
    MediaCategory.Link -> "Links"
}
```

The host references composables that don't exist yet (`ThreadList` from Task 7.1, `GroupInfoPane` from Task 5.1, `MediaListPane` from Task 6.1). The build will fail until those land. That's expected — comment out the offending lines temporarily if you want to verify the rest compiles in isolation, but uncomment before committing this task. Or land Tasks 5.1 / 6.1 / 7.1 first and come back to wire them.

For sequencing convenience, this plan recommends: implement Tasks 5.1, 6.1, 7.1 BEFORE this task. Then 4.2's commit is unconditional.

- [ ] **Step 2: Compile**

After Tasks 5.1, 6.1, 7.1 are landed:

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :composeApp:compileKotlinDesktop`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/RightPaneHost.kt
git commit -m "ui: RightPaneHost — dispatch Thread / GroupInfo / drilldown"
```

---

## Phase 5 — Group info pane

### Task 5.1: `GroupInfoPane` body

**Files:**
- Create: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/GroupInfoPane.kt`

- [ ] **Step 1: Write the pane**

```kotlin
package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.urbit.MediaCategory
import io.nisfeb.talon.urbit.TlonChatRepo
import io.nisfeb.talon.urbit.mediaCategoryOrLink
import kotlinx.coroutines.launch

/**
 * Group info body: header + mute toggle + member-count link + media
 * stats + leave button. Hosted by [RightPaneHost] on wide and by
 * [GroupInfoScreen] on compact.
 */
@Composable
fun GroupInfoPane(
    db: AppDatabase,
    repo: TlonChatRepo,
    whom: String,
    onOpenCategory: (MediaCategory) -> Unit,
    onOpenMembers: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val groupRow by remember(whom) { db.groups().streamOne(whom) }
        .collectAsState(initial = null)
    val memberCount by remember(whom) { db.groups().streamMemberCount(whom) }
        .collectAsState(initial = 0)
    val notifyPref by remember(whom) { db.notifyPrefs().stream(whom) }
        .collectAsState(initial = null)
    val countsList by remember(whom) {
        db.messageMediaDao().streamCounts(whom)
    }.collectAsState(initial = emptyList())

    val countsByCategory = remember(countsList) {
        countsList.associate { mediaCategoryOrLink(it.category) to it.n }
    }

    LazyColumn(modifier = modifier.fillMaxWidth()) {
        item {
            // Header: avatar + name + member count.
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    groupRow?.title ?: whom,
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "$memberCount members",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
        }

        item {
            // Mute toggle row.
            val muted = (notifyPref?.level ?: "default") != "default"
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (muted) Icons.Filled.NotificationsOff else Icons.Filled.Notifications,
                    contentDescription = null,
                )
                Spacer(Modifier.size(12.dp))
                Text("Mute", modifier = Modifier.weight(1f))
                Switch(
                    checked = muted,
                    onCheckedChange = { newMuted ->
                        scope.launch {
                            val level = if (newMuted) "nothing" else "default"
                            runCatching { repo.settingsSync?.setNotifyLevel(whom, level) }
                        }
                    },
                )
            }
            HorizontalDivider()
        }

        item {
            // Members link → opens existing GroupAdminScreen.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.People, contentDescription = null)
                Spacer(Modifier.size(12.dp))
                Text(
                    "View members ($memberCount)",
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                IconButton(onClick = onOpenMembers) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Open members")
                }
            }
            HorizontalDivider()
        }

        item {
            // Section heading.
            Text(
                "Shared media",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }

        item {
            // Media stats grid. Hide categories with zero count.
            MediaStatsGrid(
                counts = countsByCategory,
                onSelect = onOpenCategory,
            )
        }

        item {
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ExitToApp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    "Leave group",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            scope.launch {
                                runCatching { repo.leaveChat(whom) }
                            }
                        },
                )
            }
        }
    }
}

@Composable
private fun MediaStatsGrid(
    counts: Map<MediaCategory, Int>,
    onSelect: (MediaCategory) -> Unit,
) {
    // Order matches the spec mock-up: Photo, Video, Gif, Voice, Audio,
    // File, Link. Zero-count buckets are hidden.
    val entries = MediaCategory.entries
        .map { it to (counts[it] ?: 0) }
        .filter { (_, n) -> n > 0 }
    if (entries.isEmpty()) {
        Text(
            "No shared media yet",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        return
    }
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for ((cat, n) in entries) {
            StatCell(category = cat, count = n, onClick = { onSelect(cat) })
        }
    }
}

@Composable
private fun StatCell(
    category: MediaCategory,
    count: Int,
    onClick: () -> Unit,
) {
    val emoji = when (category) {
        MediaCategory.Photo -> "📷"
        MediaCategory.Video -> "🎥"
        MediaCategory.Gif -> "🎞"
        MediaCategory.Voice -> "🎙"
        MediaCategory.Audio -> "🎵"
        MediaCategory.File -> "📄"
        MediaCategory.Link -> "🔗"
    }
    Box(
        modifier = Modifier
            .size(width = 64.dp, height = 64.dp)
            .padding(2.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(count.toString(), style = MaterialTheme.typography.titleMedium)
            Text(emoji, style = MaterialTheme.typography.titleSmall)
        }
    }
}
```

The pane references `db.groups().streamOne(whom)` and `db.groups().streamMemberCount(whom)`. If those don't exist on `GroupDao`, add them — this is the simplest place to add them since they're only used here. Look at `streamPinnedPostId` in `GroupDao` for the right query shape.

The `Icons.AutoMirrored.Filled.ExitToApp` and `Icons.AutoMirrored.Filled.KeyboardArrowRight` are from `material-icons-core`; if the build complains about missing icons, swap to `Icons.Filled.ExitToApp` / `Icons.Filled.KeyboardArrowRight` — both are core too.

The `clickable` on the Leave-group `Text` requires `import androidx.compose.foundation.clickable`.

- [ ] **Step 2: Compile**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :composeApp:compileKotlinDesktop :composeApp:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL.

If `db.groups()` accessor naming differs, adjust. If `repo.leaveChat(whom)` doesn't exist, search `TlonChatRepo` for the existing leave method (may be `leaveGroup`, `quit`, etc.).

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/GroupInfoPane.kt \
        composeApp/src/commonMain/kotlin/io/nisfeb/talon/data/GroupDao.kt
git commit -m "ui(screens): GroupInfoPane — header + mute + members + media stats + leave"
```

---

### Task 5.2: `GroupInfoScreen` mobile wrapper

**Files:**
- Create: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/GroupInfoScreen.kt`

- [ ] **Step 1: Write the wrapper**

Same pattern as Phase 2's `StatusFeedScreen`/`StatusFeedList` split. Header (back arrow + title) + `HorizontalDivider()` + `GroupInfoPane(...)`.

```kotlin
package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.urbit.MediaCategory
import io.nisfeb.talon.urbit.TlonChatRepo

@Composable
fun GroupInfoScreen(
    db: AppDatabase,
    repo: TlonChatRepo,
    whom: String,
    onBack: () -> Unit,
    onOpenCategory: (MediaCategory) -> Unit,
    onOpenMembers: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                "Info",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        HorizontalDivider()
        GroupInfoPane(
            db = db,
            repo = repo,
            whom = whom,
            onOpenCategory = onOpenCategory,
            onOpenMembers = onOpenMembers,
        )
    }
}
```

- [ ] **Step 2: Compile**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :composeApp:compileKotlinDesktop :composeApp:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/GroupInfoScreen.kt
git commit -m "ui(screens): GroupInfoScreen — mobile full-screen wrapper"
```

---

### Task 5.3: `GroupInfoPaneTest` — render zero-count rows hidden

**Files:**
- Create: `composeApp/src/desktopTest/kotlin/io/nisfeb/talon/ui/screens/GroupInfoPaneTest.kt`

- [ ] **Step 1: Write the tests**

```kotlin
package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test

class GroupInfoPaneTest {

    /**
     * Build an [io.nisfeb.talon.data.AppDatabase] with two messages —
     * one with a photo URL, one with a link URL — so the stats grid
     * should show Photo:1 + Link:1 and hide Video / Gif / Voice / Audio
     * / File. This test focuses on the *rendering* — the DAO and the
     * classifier are tested elsewhere.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `zero-count rows are hidden`() = runComposeUiTest {
        // Implementer: stand up an in-memory DB, seed two messages,
        // and run backfill so message_media is populated. Then setContent
        // a GroupInfoPane and assert the visible categories.
        // - Use the same in-memory DB factory that other desktopTests
        //   use.
        // - Seed a TlonChatRepo with whatever no-op constructor
        //   existing tests use.
        setContent {
            Box(Modifier.size(width = 400.dp, height = 800.dp)) {
                GroupInfoPane(
                    db = TODO("in-memory DB seeded with 1 photo + 1 link"),
                    repo = TODO("no-op repo or test double"),
                    whom = "chat/~zod/test",
                    onOpenCategory = {},
                    onOpenMembers = {},
                )
            }
        }
        // Visible: photo count "1" with 📷, link count "1" with 🔗.
        // Hidden: nothing for Video / Gif / Voice / Audio / File.
        onNodeWithText("📷").assertExists()
        onNodeWithText("🔗").assertExists()
        onNodeWithText("🎥").assertDoesNotExist()
        onNodeWithText("🎞").assertDoesNotExist()
        onNodeWithText("🎙").assertDoesNotExist()
        onNodeWithText("🎵").assertDoesNotExist()
        onNodeWithText("📄").assertDoesNotExist()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `empty group shows no shared media placeholder`() = runComposeUiTest {
        setContent {
            Box(Modifier.size(width = 400.dp, height = 800.dp)) {
                GroupInfoPane(
                    db = TODO("in-memory DB with no messages"),
                    repo = TODO("no-op repo"),
                    whom = "chat/~zod/empty",
                    onOpenCategory = {},
                    onOpenMembers = {},
                )
            }
        }
        onNodeWithText("No shared media yet").assertExists()
    }
}
```

The `TODO()`s are real fixtures the implementer must build. If existing test files in `composeApp/src/desktopTest/kotlin/io/nisfeb/talon/ui/screens/` already have an in-memory DB + repo helper, reuse it.

- [ ] **Step 2: Run tests**

```
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :composeApp:desktopTest --tests 'io.nisfeb.talon.ui.screens.GroupInfoPaneTest'
```
Expected: 2 tests pass.

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/desktopTest/kotlin/io/nisfeb/talon/ui/screens/GroupInfoPaneTest.kt
git commit -m "test(screens): GroupInfoPane — zero-count rows hidden"
```

---

## Phase 6 — Media drilldown

### Task 6.1: `MediaListPane` body

**Files:**
- Create: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/MediaListPane.kt`

- [ ] **Step 1: Write the pane**

```kotlin
package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.MessageMediaEntity
import io.nisfeb.talon.urbit.MediaCategory

/**
 * Drilldown list for one [MediaCategory] in a single chat. Tap on an
 * item routes to the right behaviour for the bucket: image viewer for
 * Photo/Gif, system URI handler for Link/File/Video/Audio (the inline
 * media player handles Audio/Video playback elsewhere; opening the
 * raw URL in a browser is a deliberate choice for now). Voice plays
 * via the existing [LocalInlineMediaPlayer] when a tap lands.
 */
@Composable
fun MediaListPane(
    db: AppDatabase,
    whom: String,
    category: MediaCategory,
    onOpenImage: (url: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uri = LocalUriHandler.current
    val items by remember(whom, category) {
        db.messageMediaDao().streamCategory(
            whom = whom,
            category = category.name,
            limit = 500, // first page; pagination can come later if needed
            offset = 0,
        )
    }.collectAsState(initial = emptyList())

    if (items.isEmpty()) {
        Box(modifier = modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
            Text("Nothing yet", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }

    LazyColumn(modifier = modifier.fillMaxWidth()) {
        items(items, key = { "${it.messageId}|${it.url}" }) { item ->
            MediaRow(
                item = item,
                onClick = {
                    when (category) {
                        MediaCategory.Photo, MediaCategory.Gif -> onOpenImage(item.url)
                        else -> uri.openUri(item.url)
                    }
                },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun MediaRow(
    item: MessageMediaEntity,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                item.displayText ?: item.url,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
            )
            Text(
                item.author,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
```

- [ ] **Step 2: Compile**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :composeApp:compileKotlinDesktop :composeApp:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/MediaListPane.kt
git commit -m "ui(screens): MediaListPane — per-category drilldown list"
```

---

### Task 6.2: `MediaListScreen` mobile wrapper

**Files:**
- Create: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/MediaListScreen.kt`

- [ ] **Step 1: Write the wrapper**

Mirror `GroupInfoScreen`. Title is the category label.

```kotlin
package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.urbit.MediaCategory

@Composable
fun MediaListScreen(
    db: AppDatabase,
    whom: String,
    category: MediaCategory,
    onBack: () -> Unit,
    onOpenImage: (url: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                title(category),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        HorizontalDivider()
        MediaListPane(
            db = db,
            whom = whom,
            category = category,
            onOpenImage = onOpenImage,
        )
    }
}

private fun title(c: MediaCategory): String = when (c) {
    MediaCategory.Photo -> "Photos"
    MediaCategory.Video -> "Videos"
    MediaCategory.Gif -> "GIFs"
    MediaCategory.Voice -> "Voice messages"
    MediaCategory.Audio -> "Audio"
    MediaCategory.File -> "Files"
    MediaCategory.Link -> "Links"
}
```

- [ ] **Step 2: Compile**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :composeApp:compileKotlinDesktop :composeApp:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/MediaListScreen.kt
git commit -m "ui(screens): MediaListScreen — mobile full-screen wrapper"
```

---

## Phase 7 — Thread refactor

### Task 7.1: Extract `ThreadList`

**Files:**
- Create: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/ThreadList.kt`
- Modify: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/ThreadScreen.kt`

Same pattern as Phase 2's three extractions (`StatusFeedList`, `BookmarksList`, `ActivityList`). The pattern reference is the most recent extraction commit — `0cadfb7` (`extract ActivityList from ActivityFeedScreen`).

- [ ] **Step 1: Read the existing screen**

Run: `wc -l composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/ThreadScreen.kt`
Read the file in full. Identify the header region (TopAppBar / Row + back arrow + "Thread" title) and the body region (LazyColumn of replies, composer, etc.).

- [ ] **Step 2: Create `ThreadList.kt`**

The body composable holds everything below the header in `ThreadScreen`. Param list:

```kotlin
@Composable
fun ThreadList(
    db: AppDatabase,
    repo: TlonChatRepo,
    ourPatp: String,
    whom: String,
    parentId: String,
    initialScrollReplyId: String?,
    onScrollConsumed: () -> Unit = {},
    onOpenConversation: (whom: String) -> Unit,
    onOpenImage: (url: String) -> Unit,
    modifier: Modifier = Modifier,
)
```

(Drop `onBack` only — every other parameter that the current screen takes carries through unchanged. Adapt if the existing screen takes additional callbacks.)

Widen any `private` helper composables used by the body to `internal` so `ThreadList` can call them without inlining.

- [ ] **Step 3: Refactor `ThreadScreen` to delegate**

`ThreadScreen` keeps its existing public signature. Body becomes:

1. Existing header (back arrow + "Thread" title) byte-for-byte.
2. `HorizontalDivider()` (if there's one today; preserve as-is).
3. `ThreadList(...)` passing through every non-`onBack` parameter.

- [ ] **Step 4: Compile + test**

```
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :composeApp:compileKotlinDesktop :composeApp:compileDebugKotlinAndroid :composeApp:desktopTest
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/ThreadList.kt \
        composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/ThreadScreen.kt
git commit -m "ui(screens): extract ThreadList from ThreadScreen"
```

---

## Phase 8 — Wiring

### Task 8.1: Add info icon to chat header in `DmChatScreen`

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/DmChatScreen.kt`

- [ ] **Step 1: Add new parameters**

Find the `DmChatScreen` signature (around line 147). Add two new parameters at the end (before `modifier`):

```kotlin
/**
 * Tap handler for the new info icon in the chat header. v1 routes
 * this to the right pane on wide and to a full-screen
 * [GroupInfoScreen] on compact. Caller decides — `DmChatScreen`
 * just fires the lambda. Pass `null` from a caller that hasn't
 * wired info yet (icon stays hidden).
 */
onOpenGroupInfo: (() -> Unit)? = null,
```

- [ ] **Step 2: Add the icon button to the chat header `Row`**

Find the chat header `Row` at line 589 (the one with back arrow + title + Topics icon + NotifyLevelDropdown). Insert the info icon button between the title `Text` and the optional Topics `IconButton`:

```kotlin
if (onOpenGroupInfo != null && (whom.startsWith("chat/") || whom.startsWith("0v"))) {
    IconButton(onClick = onOpenGroupInfo) {
        Icon(Icons.Filled.Info, contentDescription = "Info")
    }
}
```

The `whom.startsWith("chat/") || whom.startsWith("0v")` predicate gates visibility to groups + clubs (per the spec — DMs are out of scope for v1). DM whoms start with `~`.

Add `import androidx.compose.material.icons.filled.Info`.

- [ ] **Step 3: Compile + run tests**

```
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :composeApp:compileKotlinDesktop :composeApp:compileDebugKotlinAndroid :composeApp:desktopTest
```
Expected: BUILD SUCCESSFUL. The new param is nullable with a default, so existing callers that don't pass it still compile.

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/DmChatScreen.kt
git commit -m "ui(chat): info icon in chat header for groups + clubs"
```

---

### Task 8.2: App.kt — `RightPaneContent` state + `DesktopShell.rightSidebar`

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/io/nisfeb/talon/compose/App.kt`

This task is large. Break it into sub-steps. Land everything in one commit so the wide path doesn't half-work.

- [ ] **Step 1: Add new state vars**

Near the existing thread state (search `var openThreadParent`):

```kotlin
var groupInfoOpenFor by remember { mutableStateOf<String?>(null) }
var groupInfoDrilldown by remember { mutableStateOf<MediaCategory?>(null) }
```

Add imports:

```kotlin
import io.nisfeb.talon.urbit.MediaCategory
import io.nisfeb.talon.ui.RightPaneContent
import io.nisfeb.talon.ui.RightPaneHost
```

- [ ] **Step 2: Compute `rightPaneContent`**

Inside the `BoxWithConstraints` block that Phase 2 added (where `expanded` is in scope), above the existing `when {}` navigation:

```kotlin
val rightPaneContent: RightPaneContent? = when {
    openThreadParent != null -> RightPaneContent.Thread(
        whom = openChat ?: return@BoxWithConstraints,
        parentId = openThreadParent!!,
        replyAnchor = openThreadReplyAnchor,
    )
    groupInfoDrilldown != null && groupInfoOpenFor != null -> RightPaneContent.GroupInfoDrilldown(
        whom = groupInfoOpenFor!!,
        category = groupInfoDrilldown!!,
    )
    groupInfoOpenFor != null -> RightPaneContent.GroupInfo(whom = groupInfoOpenFor!!)
    else -> null
}
```

The `return@BoxWithConstraints` short-circuit handles the edge case where `openThreadParent` is set but `openChat` is null (shouldn't happen, but defensive).

- [ ] **Step 3: Drop the thread branch from `detailSlot`**

Find the `detailSlot` chain (around line 838). Today it has:

```kotlin
openChat != null && openThreadParent != null -> ({ ThreadScreen(...) })
openChat != null -> ({ DmChatScreen(...) })
```

Remove the thread branch. `openChat != null -> DmChatScreen(...)` is the only chat-related branch. The thread either lives in the right pane (wide) or in a new outer-when branch (compact, Step 5).

- [ ] **Step 4: Wire `DesktopShell(rightSidebar = ...)`**

Find the `DesktopShell(...)` call. Add a `rightSidebar` arg:

```kotlin
DesktopShell(
    activeRailTab = activeRailTab,
    onSelectRailTab = { uiSettings.setActiveRailTab(it) },
    list = railListSlot,
    detail = detailSlot,
    listFraction = listFraction,
    onListFractionChange = { uiSettings.setChatPaneListFraction(it) },
    rightSidebar = rightPaneContent?.let { content ->
        {
            RightPaneHost(
                content = content,
                db = db,
                repo = repo,
                ourPatp = ship,
                onClose = {
                    openThreadParent = null
                    openThreadReplyAnchor = null
                    groupInfoOpenFor = null
                    groupInfoDrilldown = null
                },
                onOpenCategory = { groupInfoDrilldown = it },
                onLeaveCategoryDrilldown = { groupInfoDrilldown = null },
                onOpenConversation = { other ->
                    openThreadParent = null
                    openThreadReplyAnchor = null
                    groupInfoOpenFor = null
                    groupInfoDrilldown = null
                    openChat = other
                },
                onOpenImage = { url -> viewerImageUrl = url },
                onOpenMembers = { whom -> showGroupAdminFor = whom },  // or whatever existing handler
            )
        }
    },
)
```

`onOpenMembers` should match the existing kebab-menu handler that opens `GroupAdminScreen` for a given whom. Search App.kt for `GroupAdminScreen(` and copy the handler's body verbatim.

- [ ] **Step 5: Mobile back-stack — new outer-when branches**

Inside the navigation `when {}` block, ABOVE the `else -> ...` branch, add three branches in this order (drilldown first so its back-handler fires first):

```kotlin
groupInfoDrilldown != null && groupInfoOpenFor != null && !expanded -> {
    MediaListScreen(
        db = db,
        whom = groupInfoOpenFor!!,
        category = groupInfoDrilldown!!,
        onBack = { groupInfoDrilldown = null },
        onOpenImage = { url -> viewerImageUrl = url },
    )
}
groupInfoOpenFor != null && !expanded -> {
    GroupInfoScreen(
        db = db,
        repo = repo,
        whom = groupInfoOpenFor!!,
        onBack = { groupInfoOpenFor = null },
        onOpenCategory = { groupInfoDrilldown = it },
        onOpenMembers = { showGroupAdminFor = groupInfoOpenFor },
    )
}
openThreadParent != null && openChat != null && !expanded -> {
    ThreadScreen(
        db = db,
        repo = repo,
        ourPatp = ship,
        whom = openChat!!,
        parentId = openThreadParent!!,
        initialScrollReplyId = openThreadReplyAnchor,
        onScrollConsumed = { openThreadReplyAnchor = null },
        onBack = {
            openThreadParent = null
            openThreadReplyAnchor = null
        },
        onOpenConversation = { other ->
            openThreadParent = null
            openThreadReplyAnchor = null
            openChat = other
        },
        onOpenImage = { url -> viewerImageUrl = url },
    )
}
```

This replaces the thread branch that used to live inside `detailSlot`.

Add imports:

```kotlin
import io.nisfeb.talon.ui.screens.GroupInfoScreen
import io.nisfeb.talon.ui.screens.MediaListScreen
```

- [ ] **Step 6: Wire `onOpenGroupInfo` on the `DmChatScreen` call site**

Find where `DmChatScreen(...)` is called inside `detailSlot` (post-Phase-2 it's in the `else` branch of the chain). Add:

```kotlin
onOpenGroupInfo = { groupInfoOpenFor = openChat },
```

(Yes, `openChat` — that's what's currently in the detail pane. Snapshotting it at the moment of click is fine; the lambda captures the current `openChat` value.)

If on wide and a thread is open, opening group info should clear it. Add the side effect:

```kotlin
onOpenGroupInfo = {
    openThreadParent = null
    openThreadReplyAnchor = null
    groupInfoOpenFor = openChat
},
```

- [ ] **Step 7: Add `PlatformBackHandler`s for the mobile back-stack**

Near the existing `PlatformBackHandler`s (search `PlatformBackHandler`):

```kotlin
PlatformBackHandler(enabled = groupInfoDrilldown != null) {
    groupInfoDrilldown = null
}
PlatformBackHandler(enabled = groupInfoOpenFor != null && groupInfoDrilldown == null) {
    groupInfoOpenFor = null
}
```

The drilldown handler comes first so it intercepts `Esc` before the group-info handler.

- [ ] **Step 8: Compile both targets + run tests**

```
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :composeApp:compileKotlinDesktop :composeApp:compileDebugKotlinAndroid :composeApp:desktopTest
```
Expected: BUILD SUCCESSFUL.

If `Phase2 DesktopShellTest` starts failing because the `rightSidebar` lambda is now non-null in the existing tests… those tests pass `rightSidebar = null` explicitly, so they're unaffected. Verify by reading the test signatures.

- [ ] **Step 9: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/compose/App.kt
git commit -m "ui(app): wire RightPaneHost into DesktopShell + mobile back-stack"
```

---

## Phase 9 — Tests + ship

### Task 9.1: `RightPaneHostTest` — state-machine transitions

**Files:**
- Create: `composeApp/src/desktopTest/kotlin/io/nisfeb/talon/ui/RightPaneHostTest.kt`

This test verifies the *App.kt write-site mutations*, not `RightPaneHost` itself (which is a pure dispatcher). The transition rules are:

| Action | Expected effect |
|---|---|
| Open thread while group info is open | group info clears, thread opens |
| Open group info while thread is open | thread clears, group info opens |
| Drill into a category | drilldown set; group info still set |
| Back from drilldown | drilldown clears; group info stays |
| Close (X) on drilldown | drilldown clears; group info stays |
| Close (X) on group info | both group-info-open-for and drilldown clear |
| `onOpenConversation` | all right-pane state clears, openChat set |

These are tested by exercising the `App` composable's behaviour. That's hard to set up. Pragmatic alternative: extract the transition logic into a small `RightPaneStateReducer` object and unit-test it.

- [ ] **Step 1: Refactor — extract state transitions into a reducer**

In a new file `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/RightPaneStateReducer.kt`:

```kotlin
package io.nisfeb.talon.ui

import io.nisfeb.talon.urbit.MediaCategory

/**
 * Pure state model for the right pane's flat backing state. Used by
 * App.kt to keep the mutual-exclusion rules in one place; lives apart
 * from Compose so it's trivially unit-testable.
 */
data class RightPaneState(
    val openThreadParent: String? = null,
    val openThreadReplyAnchor: String? = null,
    val groupInfoOpenFor: String? = null,
    val groupInfoDrilldown: MediaCategory? = null,
)

object RightPaneStateReducer {
    fun openThread(state: RightPaneState, parentId: String, replyAnchor: String?): RightPaneState =
        state.copy(
            openThreadParent = parentId,
            openThreadReplyAnchor = replyAnchor,
            groupInfoOpenFor = null,
            groupInfoDrilldown = null,
        )

    fun openGroupInfo(state: RightPaneState, whom: String): RightPaneState =
        state.copy(
            openThreadParent = null,
            openThreadReplyAnchor = null,
            groupInfoOpenFor = whom,
            groupInfoDrilldown = null,
        )

    fun openCategory(state: RightPaneState, category: MediaCategory): RightPaneState =
        state.copy(groupInfoDrilldown = category)

    fun closeDrilldown(state: RightPaneState): RightPaneState =
        state.copy(groupInfoDrilldown = null)

    fun closeRightPane(state: RightPaneState): RightPaneState =
        RightPaneState()  // clears everything
}
```

Then in App.kt's `BoxWithConstraints` block, replace the four `var` state declarations with one:

```kotlin
var rightPaneState by remember { mutableStateOf(RightPaneState()) }
```

…and the various write sites become `rightPaneState = RightPaneStateReducer.openThread(rightPaneState, ...)` etc.

The `rightPaneContent` computation reads `rightPaneState.openThreadParent`, `.groupInfoOpenFor`, etc. The same handler bodies in Task 8.2 Step 4 — `onClose`, `onOpenCategory`, etc. — call into the reducer.

- [ ] **Step 2: Write the reducer tests**

```kotlin
package io.nisfeb.talon.ui

import io.nisfeb.talon.urbit.MediaCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RightPaneStateReducerTest {

    @Test
    fun `opening a thread clears group info`() {
        val s0 = RightPaneState(groupInfoOpenFor = "chat/~zod/x", groupInfoDrilldown = MediaCategory.Photo)
        val s1 = RightPaneStateReducer.openThread(s0, parentId = "~zod/1.000", replyAnchor = null)
        assertEquals("~zod/1.000", s1.openThreadParent)
        assertNull(s1.groupInfoOpenFor)
        assertNull(s1.groupInfoDrilldown)
    }

    @Test
    fun `opening group info clears thread`() {
        val s0 = RightPaneState(openThreadParent = "~zod/1.000", openThreadReplyAnchor = "~zod/2.000")
        val s1 = RightPaneStateReducer.openGroupInfo(s0, whom = "chat/~zod/x")
        assertEquals("chat/~zod/x", s1.groupInfoOpenFor)
        assertNull(s1.openThreadParent)
        assertNull(s1.openThreadReplyAnchor)
    }

    @Test
    fun `opening a category preserves the group-info anchor`() {
        val s0 = RightPaneState(groupInfoOpenFor = "chat/~zod/x")
        val s1 = RightPaneStateReducer.openCategory(s0, MediaCategory.Photo)
        assertEquals(MediaCategory.Photo, s1.groupInfoDrilldown)
        assertEquals("chat/~zod/x", s1.groupInfoOpenFor)
    }

    @Test
    fun `closing the drilldown preserves the group-info anchor`() {
        val s0 = RightPaneState(
            groupInfoOpenFor = "chat/~zod/x",
            groupInfoDrilldown = MediaCategory.Photo,
        )
        val s1 = RightPaneStateReducer.closeDrilldown(s0)
        assertNull(s1.groupInfoDrilldown)
        assertEquals("chat/~zod/x", s1.groupInfoOpenFor)
    }

    @Test
    fun `closing the right pane clears everything`() {
        val s0 = RightPaneState(
            openThreadParent = "~zod/1.000",
            groupInfoOpenFor = "chat/~zod/x",
            groupInfoDrilldown = MediaCategory.Photo,
        )
        val s1 = RightPaneStateReducer.closeRightPane(s0)
        assertEquals(RightPaneState(), s1)
    }
}
```

Note: this test lives in `commonTest` (not `desktopTest`) since it has no Compose dependencies — pure logic.

- [ ] **Step 3: Run tests**

```
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :composeApp:desktopTest --tests 'io.nisfeb.talon.ui.RightPaneStateReducerTest'
```
Expected: 5 tests pass.

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/RightPaneStateReducer.kt \
        composeApp/src/commonMain/kotlin/io/nisfeb/talon/compose/App.kt \
        composeApp/src/commonTest/kotlin/io/nisfeb/talon/ui/RightPaneStateReducerTest.kt
git commit -m "ui: RightPaneStateReducer + tests for mutual-exclusion rules"
```

---

### Task 9.2: RELEASE.md smoke checklist

**Files:**
- Modify: `RELEASE.md`

- [ ] **Step 1: Append a new section after the Phase 2 rail block**

```markdown
**Right column — threads + group info + media drilldowns (≥0.10.0):**
- [ ] Wide window, group chat: tap info icon in chat header → group info opens in right column.
- [ ] Wide window, DM chat: no info icon visible.
- [ ] Wide window, club chat: info icon visible.
- [ ] Wide window: tap a thread reply → thread opens in right column; chat detail stays.
- [ ] Wide window: open group info, then open a thread → group info closes, thread takes its place.
- [ ] Wide window: open thread, then tap info icon → thread closes, group info takes its place.
- [ ] Group info pane: only categories with count > 0 render in the stats grid.
- [ ] Group info pane: tapping a category swaps the right pane to the drilldown list (header changes to "Photos" / "Videos" / etc., X closes back to group info).
- [ ] Group info pane: mute toggle reads + writes per-chat notify level.
- [ ] Group info pane: View members (N) opens the existing GroupAdminScreen.
- [ ] Group info pane: Leave group leaves the chat.
- [ ] Drilldown — Photos: tap a row → image viewer opens.
- [ ] Drilldown — Links: tap a row → URL opens in system browser.
- [ ] Drilldown — Voice: voice-message rows show "🎙 Voice Ns" labels and play via the system handler.
- [ ] Mobile (<840dp): tap info icon → full-screen GroupInfoScreen.
- [ ] Mobile: tap a category → full-screen MediaListScreen; back returns to GroupInfoScreen.
- [ ] Mobile: thread replies still go to full-screen ThreadScreen with back arrow (unchanged from prior versions).
- [ ] Backfill: install over an existing 0.9.x install with chat history → group info shows correct counts within seconds (live updates).
- [ ] Cold open of a fresh group with one shared image: count appears on first render.
- [ ] New message arrives while group info is open: count increments live.
```

- [ ] **Step 2: Commit**

```bash
git add RELEASE.md
git commit -m "docs(release): Phase 3 smoke checklist"
```

---

### Task 9.3: Bump version + tag 0.10.0-rc1

**Files:**
- Modify: `composeApp/build.gradle.kts`

- [ ] **Step 1: Bump version**

```kotlin
val talonVersionCode = 68
val talonVersionName = "0.10.0-rc1"
```

- [ ] **Step 2: Compile + tests**

```
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :composeApp:compileKotlinDesktop :composeApp:compileDebugKotlinAndroid :composeApp:desktopTest :relay:test
```
Expected: BUILD SUCCESSFUL. All tests pass.

- [ ] **Step 3: Commit + tag + push**

```bash
git add composeApp/build.gradle.kts
git commit -m "release: 0.10.0-rc1 — right column for threads + group info + media drilldowns"
git tag -a v0.10.0-rc1 -m "Talon 0.10.0-rc1 — right column (threads + group info + media)"
git push github desktop-split-pane
git push github v0.10.0-rc1
```

CI builds and publishes the pre-release.

---

## Done criteria

- All listed components committed on the `desktop-split-pane` branch (or successor).
- `desktopTest` and `commonTest` green on the rc1 commit.
- Manually verified per the RELEASE.md smoke checklist on the rc1 build, both wide and narrow.
- Backfill completes silently on a real Talon install with at least one chat history.
- DM chats are unaffected (info icon hidden, no group info pane reachable).
- Mobile thread flow unchanged: tap a reply → full-screen ThreadScreen with back arrow.

After validation, fast-forward to master, drop the `-rc1`, re-tag `v0.10.0`. Phase 3 ships as the 0.10.0 minor.
