package io.nisfeb.talon.urbit

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.ai.AiSettings
import io.nisfeb.talon.ai.AiSettingsRepository
import io.nisfeb.talon.ai.DailyDigestSettings
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.BookmarkEntity
import io.nisfeb.talon.data.BookmarkFolderEntity
import io.nisfeb.talon.data.BookmarkFolderMemberEntity
import io.nisfeb.talon.data.FolderEntity
import io.nisfeb.talon.data.FolderMemberEntity
import io.nisfeb.talon.data.GroupOrderEntity
import io.nisfeb.talon.data.NotifyLevel
import io.nisfeb.talon.data.NotifyPreferenceEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins SettingsSync.applyBucket and the applySettingsEvent envelope
 * dispatcher across the non-watchword buckets that
 * SettingsSyncBucketTest doesn't touch:
 *   - group-orders
 *   - folders + folder-members
 *   - notify-prefs
 *   - bookmarks + bookmark-folders + bookmark-folder-members
 *   - ai-settings (apply path)
 *   - daily-digest (apply + per-key event dispatch)
 *
 * Plus the four envelope shapes for applySettingsEvent
 * (put-entry / del-entry / put-bucket / del-bucket) and the desk-
 * gating that drops events for foreign desks.
 */
class SettingsSyncApplyBucketTest {
    private lateinit var tmpDir: File
    private lateinit var db: AppDatabase
    private lateinit var aiSettings: FakeAiSettings
    private lateinit var dailyDigest: FakeDailyDigest
    private var rearmCount = 0
    private lateinit var sync: SettingsSyncImpl

    @Before
    fun setUp() {
        tmpDir = createTempDirectory(prefix = "talon-applybucket-test-").toFile()
        val dbFile = File(tmpDir, "test.db")
        db = Room.databaseBuilder<AppDatabase>(name = dbFile.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        aiSettings = FakeAiSettings()
        dailyDigest = FakeDailyDigest()
        rearmCount = 0
        sync = SettingsSyncImpl(
            db = db,
            aiSettings = aiSettings,
            dailyDigestSettings = dailyDigest,
            rearmDailyDigest = { rearmCount += 1 },
        )
    }

    @After
    fun tearDown() {
        runCatching { db.close() }
        tmpDir.deleteRecursively()
    }

    // ── group-orders ────────────────────────────────────────────────

    @Test
    fun `applyBucket GROUP_ORDERS replaces local with incoming order`() = runBlocking {
        // Local has its own order; ship's order must win on apply.
        db.groupOrders().reorder(listOf("~zod/old", "~bus/older"))
        val bucket = buildJsonObject {
            put("~zod/new", buildJsonObject { put("ordinal", 0) })
            put("~bus/newer", buildJsonObject { put("ordinal", 1) })
        }
        sync.applyBucket(SettingsSyncImpl.BUCKET_GROUP_ORDERS, bucket)

        val rows = db.groupOrders().stream().first().sortedBy { it.ordinal }
        assertEquals(listOf("~zod/new", "~bus/newer"), rows.map { it.flag })
        // Old flags must be wiped — replace-on-apply contract.
        assertNull(rows.firstOrNull { it.flag == "~zod/old" })
    }

    @Test
    fun `applyBucket GROUP_ORDERS drops entries with non-int ordinal`() = runBlocking {
        // Defensive: bucket entries whose `ordinal` is missing or
        // non-numeric must be dropped silently rather than crash.
        val bucket = buildJsonObject {
            put("~zod/good", buildJsonObject { put("ordinal", 0) })
            put("~bus/bad", buildJsonObject { /* no ordinal */ })
            put("~bus/badder", buildJsonObject { put("ordinal", "not-a-number") })
        }
        sync.applyBucket(SettingsSyncImpl.BUCKET_GROUP_ORDERS, bucket)

        val rows = db.groupOrders().stream().first()
        assertEquals(listOf("~zod/good"), rows.map { it.flag })
    }

    // ── folders + members ───────────────────────────────────────────

    @Test
    fun `applyBucket FOLDERS replaces and drops keys that aren't valid Long ids`() =
        runBlocking {
            db.folders().upsert(FolderEntity(id = 99L, name = "Dropme", sortOrder = 0))
            val bucket = buildJsonObject {
                put("1", buildJsonObject {
                    put("name", "Work")
                    put("sortOrder", 0)
                })
                put("notALong", buildJsonObject {
                    put("name", "Bad")
                    put("sortOrder", 1)
                })
            }
            sync.applyBucket(SettingsSyncImpl.BUCKET_FOLDERS, bucket)

            val rows = db.folders().streamFolders().first()
            assertEquals(listOf(1L), rows.map { it.id })
            assertEquals("Work", rows[0].name)
        }

    @Test
    fun `applyBucket FOLDER_MEMBERS parses the colon-separated key`() = runBlocking {
        // Folder must exist for the FK; create it directly.
        db.folders().upsert(FolderEntity(id = 1L, name = "Work"))
        val bucket = buildJsonObject {
            put("1:~zod", buildJsonObject {
                put("ordinal", 5)
                put("kind", FolderMemberEntity.KIND_WHOM)
            })
            put("1:chat/~host/general", buildJsonObject {
                put("ordinal", 7)
                // No kind → default KIND_WHOM.
            })
            // Malformed keys must be silently dropped.
            put("nokey", buildJsonObject { put("ordinal", 0) })
            put(":~bad", buildJsonObject { put("ordinal", 0) })
        }
        sync.applyBucket(SettingsSyncImpl.BUCKET_FOLDER_MEMBERS, bucket)

        val rows = db.folders().streamMembers().first()
        assertEquals(2, rows.size)
        val byWhom = rows.associateBy { it.whom }
        assertEquals(5, byWhom["~zod"]?.ordinal)
        assertEquals(7, byWhom["chat/~host/general"]?.ordinal)
    }

    // ── notify-prefs ────────────────────────────────────────────────

    @Test
    fun `applyBucket NOTIFY_PREFS replaces local prefs`() = runBlocking {
        db.notifyPrefs().upsert(NotifyPreferenceEntity("~zod", NotifyLevel.NONE))
        val bucket = buildJsonObject {
            put("~zod", buildJsonObject { put("level", NotifyLevel.MENTIONS) })
            put("chat/~host/general", buildJsonObject { put("level", NotifyLevel.ALL) })
        }
        sync.applyBucket(SettingsSyncImpl.BUCKET_NOTIFY_PREFS, bucket)

        assertEquals(NotifyLevel.MENTIONS, db.notifyPrefs().levelFor("~zod"))
        assertEquals(NotifyLevel.ALL, db.notifyPrefs().levelFor("chat/~host/general"))
    }

    @Test
    fun `applyBucket NOTIFY_PREFS drops entries missing the level field`() = runBlocking {
        val bucket = buildJsonObject {
            put("~zod", buildJsonObject { /* no level */ })
            put("~bus", buildJsonObject { put("level", NotifyLevel.NONE) })
        }
        sync.applyBucket(SettingsSyncImpl.BUCKET_NOTIFY_PREFS, bucket)

        assertNull(db.notifyPrefs().levelFor("~zod"))
        assertEquals(NotifyLevel.NONE, db.notifyPrefs().levelFor("~bus"))
    }

    // ── bookmarks ───────────────────────────────────────────────────

    @Test
    fun `applyBucket BOOKMARKS uses pipe-separated key and persists ts`() = runBlocking {
        // Bookmarks key is `whom|postId` — the pipe is required because
        // both whom and postId can contain `:` (e.g. dotted post ids).
        val bucket = buildJsonObject {
            put("~zod|1234.5678", buildJsonObject { put("ts", 1_700_000_000_000L) })
            put("chat/~host/general|abc.def", buildJsonObject { put("ts", 1_700_000_111_111L) })
            // Missing pipe → drop silently.
            put("nopipe", buildJsonObject { put("ts", 0L) })
        }
        sync.applyBucket(SettingsSyncImpl.BUCKET_BOOKMARKS, bucket)

        val rows = db.bookmarks().all()
        assertEquals(2, rows.size)
        val byPostId = rows.associateBy { it.postId }
        assertEquals(1_700_000_000_000L, byPostId["1234.5678"]?.bookmarkedMs)
        assertEquals("chat/~host/general", byPostId["abc.def"]?.whom)
    }

    @Test
    fun `applyBucket BOOKMARKS drops local rows not present in bucket`() = runBlocking {
        db.bookmarks().upsert(BookmarkEntity("~zod", "old.id", 1L))
        val bucket = buildJsonObject {
            put("~zod|new.id", buildJsonObject { put("ts", 2L) })
        }
        sync.applyBucket(SettingsSyncImpl.BUCKET_BOOKMARKS, bucket)

        val rows = db.bookmarks().all()
        assertEquals(listOf("new.id"), rows.map { it.postId })
    }

    // ── bookmark folders + members ──────────────────────────────────

    @Test
    fun `applyBucket BOOKMARK_FOLDERS replaces local with incoming`() = runBlocking {
        db.bookmarkFolders().upsert(BookmarkFolderEntity(id = 99L, name = "Drop"))
        val bucket = buildJsonObject {
            put("1", buildJsonObject {
                put("name", "Reading")
                put("sortOrder", 0)
            })
        }
        sync.applyBucket(SettingsSyncImpl.BUCKET_BOOKMARK_FOLDERS, bucket)

        val rows = db.bookmarkFolders().streamFolders().first()
        assertEquals(listOf(1L), rows.map { it.id })
        assertEquals("Reading", rows[0].name)
    }

    @Test
    fun `applyBucket BOOKMARK_FOLDER_MEMBERS parses the three-part key`() = runBlocking {
        db.bookmarkFolders().upsert(BookmarkFolderEntity(id = 1L, name = "Reading"))
        val bucket = buildJsonObject {
            put("1|~zod|1234.5678", buildJsonObject { put("ordinal", 3) })
            put("1|chat/~host/general|abc", buildJsonObject { put("ordinal", 4) })
            // Malformed (only two segments) → drop silently.
            put("1|~bad", buildJsonObject { put("ordinal", 0) })
        }
        sync.applyBucket(SettingsSyncImpl.BUCKET_BOOKMARK_FOLDER_MEMBERS, bucket)

        val rows = db.bookmarkFolders().streamMembers().first()
        assertEquals(2, rows.size)
        val byPostId = rows.associateBy { it.postId }
        assertEquals(3, byPostId["1234.5678"]?.ordinal)
        assertEquals("chat/~host/general", byPostId["abc"]?.whom)
    }

    @Test
    fun `parseBookmarkFolderMemberKey rejects empty parts`() {
        // File-scoped helper exposed for direct testing — a missing
        // whom or postId must not produce a Triple with blank fields.
        assertNotNull(parseBookmarkFolderMemberKey("1|~zod|x"))
        assertNull(parseBookmarkFolderMemberKey("1||x"))
        assertNull(parseBookmarkFolderMemberKey("1|~zod|"))
        assertNull(parseBookmarkFolderMemberKey("notLong|~zod|x"))
        assertNull(parseBookmarkFolderMemberKey("1|~zod"))
    }

    // ── ai-settings ─────────────────────────────────────────────────

    @Test
    fun `applyBucket AI_SETTINGS pulls cloud-key fields when local sync is opted in`() =
        runBlocking {
            // syncEnabled=true on the local device → the apply path
            // honors the wire's provider/apiKey/model. Without the
            // opt-in, the cloud-key fields stay local (covered by
            // the next test).
            aiSettings.applyRemote(
                AiSettings.Config(
                    provider = AiSettings.Provider.Anthropic,
                    apiKey = "",
                    model = null,
                    syncEnabled = true,
                ),
            )
            val bucket = buildJsonObject {
                put("config", buildJsonObject {
                    put("provider", "OpenAi")
                    put("apiKey", "sk-secret")
                    put("model", "gpt-4")
                    put("emojiReactEnabled", "false")
                    put("dailyDigestEnabled", "true")
                })
            }
            sync.applyBucket(SettingsSyncImpl.BUCKET_AI_SETTINGS, bucket)

            val cfg = aiSettings.state.value
            assertEquals(AiSettings.Provider.OpenAi, cfg.provider)
            assertEquals("sk-secret", cfg.apiKey)
            assertEquals("gpt-4", cfg.model)
            assertEquals(false, cfg.emojiReactEnabled)
            assertEquals(true, cfg.dailyDigestEnabled)
        }

    @Test
    fun `applyBucket AI_SETTINGS applies feature toggles even when sync is off`() =
        runBlocking {
            // Per-feature toggles always sync — a peer device that
            // turned on emoji-react should propagate to this device
            // regardless of whether this device opted into key sync.
            // The cloud-key fields, however, MUST NOT cross over
            // without local consent (next test pins that).
            // Default syncEnabled is true; flip off explicitly to
            // exercise the gate.
            aiSettings.setSyncEnabled(false)
            val before = aiSettings.state.value
            val bucket = buildJsonObject {
                put("config", buildJsonObject {
                    put("provider", "OpenAi")
                    put("apiKey", "sk-secret")
                    put("emojiReactEnabled", "false")
                    put("topicClustersEnabled", "true")
                })
            }
            sync.applyBucket(SettingsSyncImpl.BUCKET_AI_SETTINGS, bucket)

            val cfg = aiSettings.state.value
            assertEquals(false, cfg.emojiReactEnabled)
            assertEquals(true, cfg.topicClustersEnabled)
            // Cloud-key fields stayed at the prior local values —
            // syncEnabled was off, so the wire's provider/apiKey
            // never cross into local state.
            assertEquals(before.provider, cfg.provider)
            assertEquals(before.apiKey, cfg.apiKey)
        }

    @Test
    fun `applyBucket AI_SETTINGS does not apply cloud-key fields when sync is off`() =
        runBlocking {
            // Sec-relevant invariant: a peer's API key must not be
            // written into this device's local state unless the user
            // explicitly opted into key sync on THIS device.
            // Default syncEnabled is true; flip off to exercise the
            // gate the test is checking.
            aiSettings.setSyncEnabled(false)
            val before = aiSettings.state.value
            assertEquals(false, before.syncEnabled)
            val bucket = buildJsonObject {
                put("config", buildJsonObject {
                    put("provider", "OpenAi")
                    put("apiKey", "sk-secret-from-peer")
                    put("model", "gpt-4")
                    put("baseUrl", "https://example.com")
                })
            }
            sync.applyBucket(SettingsSyncImpl.BUCKET_AI_SETTINGS, bucket)

            val cfg = aiSettings.state.value
            assertEquals(before.provider, cfg.provider)
            assertEquals(before.apiKey, cfg.apiKey)
            assertEquals(before.model, cfg.model)
            assertEquals(before.baseUrl, cfg.baseUrl)
        }

    @Test
    fun `applyBucket AI_SETTINGS preserves the local syncEnabled flag`() = runBlocking {
        // Per the impl: applyRemote uses the local current.syncEnabled
        // rather than whatever is in the bucket — so a fresh-bootstrap
        // device that already opted in stays opted in, and one that
        // hasn't doesn't get silently flipped on.
        aiSettings.applyRemote(
            AiSettings.Config(
                provider = AiSettings.Provider.Anthropic,
                apiKey = "",
                model = null,
                syncEnabled = true,
            ),
        )
        val bucket = buildJsonObject {
            put("config", buildJsonObject {
                put("provider", "Anthropic")
                put("apiKey", "key")
            })
        }
        sync.applyBucket(SettingsSyncImpl.BUCKET_AI_SETTINGS, bucket)

        assertEquals(true, aiSettings.state.value.syncEnabled)
    }

    @Test
    fun `applyBucket AI_SETTINGS with unknown provider keeps cloud-key fields local`() =
        runBlocking {
            // Even with sync opted in, an unparseable provider field
            // means we can't reconstruct a Config — fall back to the
            // local cloud-key state. Feature toggles still apply per
            // the always-sync rule.
            aiSettings.applyRemote(
                AiSettings.Config(
                    provider = AiSettings.Provider.Anthropic,
                    apiKey = "local",
                    model = null,
                    syncEnabled = true,
                ),
            )
            val before = aiSettings.state.value
            val bucket = buildJsonObject {
                put("config", buildJsonObject {
                    put("provider", "NotARealProvider")
                    put("apiKey", "key")
                    put("emojiReactEnabled", "false")
                })
            }
            sync.applyBucket(SettingsSyncImpl.BUCKET_AI_SETTINGS, bucket)

            val cfg = aiSettings.state.value
            assertEquals(before.provider, cfg.provider)
            assertEquals(before.apiKey, cfg.apiKey)
            // Feature toggle from the bucket still landed.
            assertEquals(false, cfg.emojiReactEnabled)
        }

    // ── daily-digest ────────────────────────────────────────────────

    @Test
    fun `applyBucket DAILY_DIGEST forwards to applyRemote and rearms`() = runBlocking {
        val bucket = buildJsonObject {
            put("enabled", true)
            put("hourOfDay", 7)
            put("minuteOfDay", 30)
        }
        sync.applyBucket(SettingsSyncImpl.BUCKET_DAILY_DIGEST, bucket)

        val state = dailyDigest.state.value
        assertEquals(true, state.enabled)
        assertEquals(7, state.hourOfDay)
        assertEquals(30, state.minuteOfDay)
        assertEquals(1, rearmCount, "rearm must run after applyRemote")
    }

    @Test
    fun `applyBucket DAILY_DIGEST falls back to defaults for missing fields`() =
        runBlocking {
            // Empty bucket → enabled=false (default), hour=6, minute=0.
            sync.applyBucket(SettingsSyncImpl.BUCKET_DAILY_DIGEST, buildJsonObject {})
            val state = dailyDigest.state.value
            assertEquals(false, state.enabled)
            assertEquals(6, state.hourOfDay)
            assertEquals(0, state.minuteOfDay)
        }

    // ── applySettingsEvent envelopes ────────────────────────────────

    @Test
    fun `applySettingsEvent put-entry routes to applyEntry on the right bucket`() =
        runBlocking {
            // put-entry on group-orders → upsertRaw, no replace-all.
            val payload = buildJsonObject {
                put("put-entry", buildJsonObject {
                    put("desk", "talon")
                    put("bucket-key", SettingsSyncImpl.BUCKET_GROUP_ORDERS)
                    put("entry-key", "~zod/group")
                    put("value", JsonPrimitive("{\"ordinal\":42}"))
                })
            }
            sync.applySettingsEvent(payload)

            val rows = db.groupOrders().stream().first()
            assertEquals(1, rows.size)
            assertEquals("~zod/group", rows[0].flag)
            assertEquals(42, rows[0].ordinal)
        }

    @Test
    fun `applySettingsEvent del-entry routes to removeEntry`() = runBlocking {
        // Pre-populate, then issue del-entry — the row must disappear.
        db.groupOrders().reorder(listOf("~zod/keep", "~bus/drop"))
        val payload = buildJsonObject {
            put("del-entry", buildJsonObject {
                put("desk", "talon")
                put("bucket-key", SettingsSyncImpl.BUCKET_GROUP_ORDERS)
                put("entry-key", "~bus/drop")
            })
        }
        sync.applySettingsEvent(payload)

        val rows = db.groupOrders().stream().first()
        assertEquals(listOf("~zod/keep"), rows.map { it.flag })
    }

    @Test
    fun `applySettingsEvent put-bucket routes to applyBucket`() = runBlocking {
        db.notifyPrefs().upsert(NotifyPreferenceEntity("~zod", NotifyLevel.NONE))
        val payload = buildJsonObject {
            put("put-bucket", buildJsonObject {
                put("desk", "talon")
                put("bucket-key", SettingsSyncImpl.BUCKET_NOTIFY_PREFS)
                put("bucket", buildJsonObject {
                    put("~bus", buildJsonObject { put("level", NotifyLevel.MENTIONS) })
                })
            })
        }
        sync.applySettingsEvent(payload)

        // Old ~zod row gone (replace-on-apply); ~bus inserted.
        assertNull(db.notifyPrefs().levelFor("~zod"))
        assertEquals(NotifyLevel.MENTIONS, db.notifyPrefs().levelFor("~bus"))
    }

    @Test
    fun `applySettingsEvent del-bucket routes to clearBucketLocally`() = runBlocking {
        db.bookmarks().upsert(BookmarkEntity("~zod", "x.id", 1L))
        val payload = buildJsonObject {
            put("del-bucket", buildJsonObject {
                put("desk", "talon")
                put("bucket-key", SettingsSyncImpl.BUCKET_BOOKMARKS)
            })
        }
        sync.applySettingsEvent(payload)

        assertTrue(db.bookmarks().all().isEmpty())
    }

    @Test
    fun `applySettingsEvent ignores events for foreign desks`() = runBlocking {
        // The desk gate prevents an event for some other %settings desk
        // (e.g. "groups") from accidentally mutating talon's local data.
        db.groupOrders().reorder(listOf("~zod/keep"))
        val payload = buildJsonObject {
            put("del-entry", buildJsonObject {
                put("desk", "groups")
                put("bucket-key", SettingsSyncImpl.BUCKET_GROUP_ORDERS)
                put("entry-key", "~zod/keep")
            })
        }
        sync.applySettingsEvent(payload)

        // ~zod/keep must still be there.
        val rows = db.groupOrders().stream().first()
        assertEquals(listOf("~zod/keep"), rows.map { it.flag })
    }

    @Test
    fun `applySettingsEvent ignores events with no recognized envelope shape`() =
        runBlocking {
            // Pre-populate; an empty payload must be a silent no-op.
            db.groupOrders().reorder(listOf("~zod/keep"))
            sync.applySettingsEvent(buildJsonObject {})
            val rows = db.groupOrders().stream().first()
            assertEquals(listOf("~zod/keep"), rows.map { it.flag })
        }

    // ── setWatchwordExclude routes to the injected callback ─────────

    @Test
    fun `setWatchwordExclude invokes the injected router with whom and excluded`() = runBlocking {
        // Pre-Stage-F app/ DmChatScreen called Watchwords.excludeChat
        // directly. Stage F moved DmChatScreen to commonMain; the call
        // re-routed through SettingsSync.setWatchwordExclude. Verify
        // the override actually fires the router (vs. inheriting the
        // interface's no-op default).
        val captured = mutableListOf<Pair<String, Boolean>>()
        val routedSync = SettingsSyncImpl(
            db = db,
            aiSettings = aiSettings,
            dailyDigestSettings = dailyDigest,
            watchwordExcludeRouter = { whom, excluded ->
                captured += whom to excluded
            },
        )
        routedSync.setWatchwordExclude("~zod", true)
        routedSync.setWatchwordExclude("chat/~host/general", false)
        assertEquals(
            listOf("~zod" to true, "chat/~host/general" to false),
            captured,
        )
    }

    // ── cord-stringified value compatibility ────────────────────────

    @Test
    fun `applyBucket WATCHWORDS accepts cord-stringified entry values`() = runBlocking {
        // After the watchword push paths were aligned with the cord
        // pattern, ship-side values arrive as JsonPrimitive(jsonString)
        // and must be unwrapped before reading the term/notify fields.
        val cordValue = JsonPrimitive(
            "{\"term\":\"alpha\",\"notify\":true,\"createdMs\":1700000000000}",
        )
        val bucket = buildJsonObject { put("k1", cordValue) }
        sync.applyBucket(SettingsSyncImpl.BUCKET_WATCHWORDS, bucket)

        val rows = db.watchwords().streamTerms().first()
        assertEquals(listOf("alpha"), rows.map { it.term })
    }

    @Test
    fun `applyEntry WATCHWORD_EXCLUDES with explicit false skips upsert`() = runBlocking {
        // Defensive: a stale or future writer could put-entry the
        // exclude bucket with `false` value. The legacy semantics
        // were "presence = excluded, removal via del-entry"; honoring
        // an explicit `false` keeps a `false` writer from accidentally
        // creating the row.
        val payload = buildJsonObject {
            put("put-entry", buildJsonObject {
                put("desk", "talon")
                put("bucket-key", SettingsSyncImpl.BUCKET_WATCHWORD_EXCLUDES)
                put("entry-key", "~zod")
                put("value", JsonPrimitive("false"))
            })
        }
        sync.applySettingsEvent(payload)

        assertTrue(
            db.watchwords().excludesAsList().isEmpty(),
            "explicit false must not create the exclude row",
        )
    }

    @Test
    fun `applyEntry WATCHWORD_EXCLUDES with explicit true creates the row`() = runBlocking {
        // Symmetric to the false-skips case — true is the documented
        // shape pushWatchwordExclude emits.
        val payload = buildJsonObject {
            put("put-entry", buildJsonObject {
                put("desk", "talon")
                put("bucket-key", SettingsSyncImpl.BUCKET_WATCHWORD_EXCLUDES)
                put("entry-key", "~zod")
                put("value", JsonPrimitive("true"))
            })
        }
        sync.applySettingsEvent(payload)

        assertEquals(listOf("~zod"), db.watchwords().excludesAsList())
    }

    // ── applyEntry per-key paths for daily-digest ────────────────────

    @Test
    fun `put-entry daily-digest enabled toggles only the enabled field`() = runBlocking {
        // Pre-set hour/minute so we can verify the per-key apply preserves them.
        dailyDigest.applyRemote(enabled = false, hourOfDay = 9, minuteOfDay = 15)
        rearmCount = 0
        val payload = buildJsonObject {
            put("put-entry", buildJsonObject {
                put("desk", "talon")
                put("bucket-key", SettingsSyncImpl.BUCKET_DAILY_DIGEST)
                put("entry-key", "enabled")
                put("value", JsonPrimitive("true"))
            })
        }
        sync.applySettingsEvent(payload)

        val state = dailyDigest.state.value
        assertEquals(true, state.enabled)
        assertEquals(9, state.hourOfDay)
        assertEquals(15, state.minuteOfDay)
        assertEquals(1, rearmCount)
    }

    @Test
    fun `put-entry daily-digest hourOfDay rejects out-of-range values`() = runBlocking {
        dailyDigest.applyRemote(enabled = true, hourOfDay = 6, minuteOfDay = 0)
        rearmCount = 0
        val payload = buildJsonObject {
            put("put-entry", buildJsonObject {
                put("desk", "talon")
                put("bucket-key", SettingsSyncImpl.BUCKET_DAILY_DIGEST)
                put("entry-key", "hourOfDay")
                put("value", JsonPrimitive("99"))
            })
        }
        sync.applySettingsEvent(payload)

        // Out-of-range value rejected → state untouched, no rearm.
        assertEquals(6, dailyDigest.state.value.hourOfDay)
        assertEquals(0, rearmCount)
    }

    @Test
    fun `put-entry daily-digest minuteOfDay rejects out-of-range values`() = runBlocking {
        dailyDigest.applyRemote(enabled = true, hourOfDay = 6, minuteOfDay = 0)
        rearmCount = 0
        val payload = buildJsonObject {
            put("put-entry", buildJsonObject {
                put("desk", "talon")
                put("bucket-key", SettingsSyncImpl.BUCKET_DAILY_DIGEST)
                put("entry-key", "minuteOfDay")
                put("value", JsonPrimitive("60"))
            })
        }
        sync.applySettingsEvent(payload)

        assertEquals(0, dailyDigest.state.value.minuteOfDay)
        assertEquals(0, rearmCount)
    }
}

// ──────────────────────────────────────────────────────────────────
// Test doubles
// ──────────────────────────────────────────────────────────────────

internal class FakeAiSettings : AiSettingsRepository {
    private val _state = MutableStateFlow(
        AiSettings.Config(
            provider = AiSettings.Provider.Anthropic,
            apiKey = "",
            model = null,
        ),
    )
    override val state: StateFlow<AiSettings.Config> = _state.asStateFlow()
    override var onStateChange: ((AiSettings.Config, Boolean) -> Unit)? = null
    override fun update(
        provider: AiSettings.Provider,
        apiKey: String,
        model: String?,
        baseUrl: String?,
    ) { /* unused */ }
    override fun setFeature(feature: AiSettings.Feature, enabled: Boolean) {}
    override fun setSyncEnabled(enabled: Boolean) {
        _state.value = _state.value.copy(syncEnabled = enabled)
    }
    override fun applyRemote(config: AiSettings.Config) {
        _state.value = config
    }
    override fun clear() {
        _state.value = AiSettings.Config(
            provider = AiSettings.Provider.Anthropic,
            apiKey = "",
            model = null,
        )
    }
}

internal class FakeDailyDigest : DailyDigestSettings {
    private val _state = MutableStateFlow(DailyDigestSettings.State())
    override val state: StateFlow<DailyDigestSettings.State> = _state.asStateFlow()
    override var onChange: ((DailyDigestSettings.Change, Boolean) -> Unit)? = null
    override fun setEnabled(enabled: Boolean) {
        _state.value = _state.value.copy(enabled = enabled)
    }
    override fun setTime(hourOfDay: Int, minuteOfDay: Int) {
        _state.value = _state.value.copy(
            hourOfDay = hourOfDay, minuteOfDay = minuteOfDay,
        )
    }
    override fun applyRemote(enabled: Boolean, hourOfDay: Int, minuteOfDay: Int) {
        _state.value = DailyDigestSettings.State(enabled, hourOfDay, minuteOfDay)
    }
    override fun emitSyncToggledOff() {}
}
