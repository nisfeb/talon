package io.nisfeb.talon.urbit

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.ai.AiSettings
import io.nisfeb.talon.ai.AiSettingsRepository
import io.nisfeb.talon.ai.DailyDigestSettings
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.WatchwordChatExcludeEntity
import io.nisfeb.talon.data.WatchwordEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins SettingsSync.applyBucket's watchwords + watchword-excludes
 * paths against the database. Both buckets are critical to fresh-
 * login cross-device sync — bootstrap recently regressed (the calls
 * were missing) and a unit test would have caught it.
 *
 * applyBucket runs against the live Room database; using a real
 * tmp-file SQLite db here is meaningfully better than a mock because
 * the contract under test includes "the upserts and deletes touch
 * the right rows", which only a real DAO exercise can guarantee.
 *
 * UrbitChannel + the bootstrap() orchestration are NOT exercised
 * here — that's a separate test surface that needs an HTTP fake.
 * applyBucket is what the protocol-level fix actually changed
 * behavior in; testing it is the highest-value coverage available
 * without that scaffolding.
 */
class SettingsSyncBucketTest {
    private lateinit var tmpDir: File
    private lateinit var db: AppDatabase
    private lateinit var sync: SettingsSyncImpl

    @Before
    fun setUp() {
        tmpDir = createTempDirectory(prefix = "talon-settingssync-test-").toFile()
        val dbFile = File(tmpDir, "test.db")
        db = Room.databaseBuilder<AppDatabase>(name = dbFile.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        sync = SettingsSyncImpl(
            db = db,
            aiSettings = FakeAiSettingsRepository(),
            dailyDigestSettings = FakeDailyDigestSettings(),
        )
    }

    @After
    fun tearDown() {
        runCatching { db.close() }
        tmpDir.deleteRecursively()
    }

    private fun watchwordEntry(
        term: String,
        notify: Boolean = true,
        createdMs: Long = 1_700_000_000_000L,
    ): JsonObject = buildJsonObject {
        put("term", term)
        put("notify", notify)
        put("createdMs", createdMs)
    }

    @Test
    fun `applyBucket WATCHWORDS upserts new terms into an empty table`() = runBlocking {
        val bucket = buildJsonObject {
            put("k1", watchwordEntry("hello"))
            put("k2", watchwordEntry("world", notify = false))
        }
        sync.applyBucket(SettingsSyncImpl.BUCKET_WATCHWORDS, bucket)

        val rows = db.watchwords().streamTerms().first()
        assertEquals(2, rows.size)
        val byTerm = rows.associateBy { it.term }
        assertEquals(true, byTerm["hello"]?.notify)
        assertEquals(false, byTerm["world"]?.notify)
    }

    @Test
    fun `applyBucket WATCHWORDS leaves existing matching term untouched, updates notify`() =
        runBlocking {
            // Local already has "alpha" with notify=true.
            db.watchwords().upsertTerm(
                WatchwordEntity(term = "alpha", notify = true, createdMs = 1L)
            )
            // Incoming bucket says alpha should be notify=false.
            val bucket = buildJsonObject {
                put("k", watchwordEntry("alpha", notify = false))
            }
            sync.applyBucket(SettingsSyncImpl.BUCKET_WATCHWORDS, bucket)

            val rows = db.watchwords().streamTerms().first()
            assertEquals(1, rows.size, "no duplicate row created")
            assertEquals("alpha", rows[0].term)
            assertEquals(false, rows[0].notify, "notify must be updated to bucket value")
        }

    @Test
    fun `applyBucket WATCHWORDS drops local terms not present in bucket`() = runBlocking {
        // Local has both "keep" and "drop"; incoming only mentions "keep".
        // The replace-on-apply contract says "drop" must disappear.
        db.watchwords().upsertTerm(
            WatchwordEntity(term = "keep", notify = true, createdMs = 1L)
        )
        db.watchwords().upsertTerm(
            WatchwordEntity(term = "drop", notify = true, createdMs = 2L)
        )

        val bucket = buildJsonObject {
            put("k", watchwordEntry("keep"))
        }
        sync.applyBucket(SettingsSyncImpl.BUCKET_WATCHWORDS, bucket)

        val rows = db.watchwords().streamTerms().first()
        assertEquals(listOf("keep"), rows.map { it.term })
    }

    @Test
    fun `applyBucket WATCHWORDS with null entries clears all local terms`() = runBlocking {
        // null entries means "no incoming" → drop everything local.
        // This is what %settings sends when the user clears the bucket
        // server-side.
        db.watchwords().upsertTerm(
            WatchwordEntity(term = "before", notify = true, createdMs = 1L)
        )
        sync.applyBucket(SettingsSyncImpl.BUCKET_WATCHWORDS, null)

        val rows = db.watchwords().streamTerms().first()
        assertTrue(rows.isEmpty(), "null bucket must mean clear local")
    }

    @Test
    fun `applyBucket WATCHWORDS skips entries missing the term field`() = runBlocking {
        // Garbage in the bucket (object with no `term`) should be
        // silently skipped, not crash. Real %settings can in theory
        // contain malformed entries from older clients.
        val bucket = buildJsonObject {
            put("k1", buildJsonObject { put("notify", true) /* no term */ })
            put("k2", watchwordEntry("ok"))
        }
        sync.applyBucket(SettingsSyncImpl.BUCKET_WATCHWORDS, bucket)

        val rows = db.watchwords().streamTerms().first()
        assertEquals(listOf("ok"), rows.map { it.term })
    }

    @Test
    fun `applyBucket WATCHWORD_EXCLUDES inserts new excludes`() = runBlocking {
        val bucket = buildJsonObject {
            put("~zod", buildJsonObject { /* value shape ignored — keys are whoms */ })
            put("chat/~host/general", buildJsonObject {})
        }
        sync.applyBucket(SettingsSyncImpl.BUCKET_WATCHWORD_EXCLUDES, bucket)

        val whoms = db.watchwords().excludesAsList().toSet()
        assertEquals(setOf("~zod", "chat/~host/general"), whoms)
    }

    @Test
    fun `applyBucket WATCHWORD_EXCLUDES drops local excludes not in bucket`() = runBlocking {
        db.watchwords().upsertExclude(WatchwordChatExcludeEntity("~zod"))
        db.watchwords().upsertExclude(WatchwordChatExcludeEntity("~bus"))

        val bucket = buildJsonObject {
            put("~zod", buildJsonObject {})
        }
        sync.applyBucket(SettingsSyncImpl.BUCKET_WATCHWORD_EXCLUDES, bucket)

        val whoms = db.watchwords().excludesAsList().toSet()
        assertEquals(setOf("~zod"), whoms)
    }

    @Test
    fun `applyBucket WATCHWORD_EXCLUDES with null bucket clears all excludes`() = runBlocking {
        db.watchwords().upsertExclude(WatchwordChatExcludeEntity("~zod"))
        sync.applyBucket(SettingsSyncImpl.BUCKET_WATCHWORD_EXCLUDES, null)
        assertTrue(db.watchwords().excludesAsList().isEmpty())
    }

    @Test
    fun `applyBucket WATCHWORD_EXCLUDES is idempotent`() = runBlocking {
        // Applying the same bucket twice must yield the same state.
        val bucket = buildJsonObject {
            put("~zod", buildJsonObject {})
        }
        sync.applyBucket(SettingsSyncImpl.BUCKET_WATCHWORD_EXCLUDES, bucket)
        sync.applyBucket(SettingsSyncImpl.BUCKET_WATCHWORD_EXCLUDES, bucket)
        assertEquals(listOf("~zod"), db.watchwords().excludesAsList())
    }
}

// ──────────────────────────────────────────────────────────────────
// Minimal in-memory test doubles. SettingsSyncImpl reads aiSettings
// state during AI-bucket apply (gated by syncEnabled) and calls
// applyRemote on dailyDigestSettings during the daily-digest bucket
// path; the watchwords paths under test don't touch either, but the
// constructor still requires non-null values.
// ──────────────────────────────────────────────────────────────────

private class FakeAiSettingsRepository : AiSettingsRepository {
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
    ) { /* unused in these tests */ }
    override fun setFeature(feature: AiSettings.Feature, enabled: Boolean) {}
    override fun setSyncEnabled(enabled: Boolean) {}
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

private class FakeDailyDigestSettings : DailyDigestSettings {
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
