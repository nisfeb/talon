package io.nisfeb.talon.urbit

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.ai.AiSettings
import io.nisfeb.talon.ai.AiSettingsRepository
import io.nisfeb.talon.ai.DailyDigestSettings
import io.nisfeb.talon.data.AppDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * Property-style fuzzing for SettingsSyncImpl's wire-shape parsers.
 * Goal: any JSON payload that arrives over %settings — even malformed
 * or hostile — must not crash applySettingsEvent. Local DB state is
 * allowed to be silently inconsistent for malformed payloads, but a
 * thrown exception would kill the SSE event loop.
 *
 * We also probe applyBucket and applyEntry directly so individual
 * bucket-key paths can be exercised without faking the envelope.
 */
class SettingsSyncFuzzTest {
    private val ITERATIONS = 1_000
    private val SEED = 1_000L

    private lateinit var tmpDir: File
    private lateinit var db: AppDatabase
    private lateinit var sync: SettingsSyncImpl

    private val knownBuckets = listOf(
        SettingsSyncImpl.BUCKET_GROUP_ORDERS,
        SettingsSyncImpl.BUCKET_FOLDERS,
        SettingsSyncImpl.BUCKET_FOLDER_MEMBERS,
        SettingsSyncImpl.BUCKET_NOTIFY_PREFS,
        SettingsSyncImpl.BUCKET_BOOKMARKS,
        SettingsSyncImpl.BUCKET_BOOKMARK_FOLDERS,
        SettingsSyncImpl.BUCKET_BOOKMARK_FOLDER_MEMBERS,
        SettingsSyncImpl.BUCKET_AI_SETTINGS,
        SettingsSyncImpl.BUCKET_WATCHWORDS,
        SettingsSyncImpl.BUCKET_WATCHWORD_EXCLUDES,
        SettingsSyncImpl.BUCKET_DAILY_DIGEST,
        // unknown buckets — must also be a no-op rather than a throw
        "unknown-bucket",
        "",
    )

    @Before
    fun setUp() {
        tmpDir = createTempDirectory(prefix = "talon-syncfuzz-").toFile()
        val dbFile = File(tmpDir, "fuzz.db")
        db = Room.databaseBuilder<AppDatabase>(name = dbFile.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        sync = SettingsSyncImpl(
            db = db,
            aiSettings = NoopAiSettings(),
            dailyDigestSettings = NoopDailyDigest(),
        )
    }

    @After
    fun tearDown() {
        runCatching { db.close() }
        tmpDir.deleteRecursively()
    }

    // ─── applySettingsEvent never throws ───────────────────────────

    @Test
    fun `applySettingsEvent never throws on arbitrary JSON envelopes`() {
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            // Bias half the inputs toward something that LOOKS like a
            // valid envelope so we exercise the dispatch paths, not just
            // the unrecognized-shape fallthrough.
            val payload = if (rnd.nextBoolean()) {
                buildJsonObject {
                    val verb = listOf(
                        "put-entry", "del-entry", "put-bucket", "del-bucket",
                    ).random(rnd)
                    put(verb, buildJsonObject {
                        put("desk", listOf("talon", "groups", "").random(rnd))
                        put("bucket-key", knownBuckets.random(rnd))
                        put("entry-key", Fuzz.randomString(rnd, 30))
                        put("value", Fuzz.randomJson(rnd, depth = 3))
                        put("bucket", Fuzz.randomJsonObject(rnd, depth = 2))
                    })
                }
            } else {
                Fuzz.randomJsonObject(rnd, depth = 4)
            }
            // Fuzz.run takes a non-suspend body, so each iteration drives
            // its own runBlocking. Slower than one outer runBlocking but
            // sidesteps the suspension-context restriction.
            runBlocking { sync.applySettingsEvent(payload) }
        }
    }

    // ─── applyBucket never throws on arbitrary entries ─────────────

    @Test
    fun `applyBucket never throws across all known buckets and arbitrary entries`() {
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            val bucket = knownBuckets.random(rnd)
            val entries: JsonObject? = when (rnd.nextInt(4)) {
                0 -> null
                else -> buildJsonObject {
                    repeat(rnd.nextInt(0, 6)) {
                        put(
                            Fuzz.randomString(rnd, 30),
                            Fuzz.randomJson(rnd, depth = 2),
                        )
                    }
                }
            }
            runBlocking { sync.applyBucket(bucket, entries) }
        }
    }

    // ─── unwrap stringified-cord values is symmetric ───────────────

    @Test
    fun `applyBucket WATCHWORDS tolerates both raw JsonObject and stringified cord`() {
        // Pre-Stage-F push paths sent raw JsonObject values; the
        // post-fix paths stringify into cords. The apply path now
        // calls unwrap() so both shapes work. Exercises that
        // tolerance with a mix of inputs.
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            val term = Fuzz.randomString(rnd, 20).ifBlank { "x" }
            val raw = buildJsonObject {
                put("term", term)
                put("notify", rnd.nextBoolean())
                put("createdMs", rnd.nextLong(0L, Long.MAX_VALUE / 2))
            }
            val asCord = kotlinx.serialization.json.JsonPrimitive(
                kotlinx.serialization.json.Json.encodeToString(
                    kotlinx.serialization.json.JsonElement.serializer(), raw,
                ),
            )
            val bucket = buildJsonObject {
                val k = Fuzz.randomString(rnd, 10).ifBlank { "k" }
                put(k, if (rnd.nextBoolean()) raw else asCord)
            }
            runBlocking {
                sync.applyBucket(SettingsSyncImpl.BUCKET_WATCHWORDS, bucket)
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────
// Test doubles. Settings sync exercises aiSettings + dailyDigest only
// in the AI / daily-digest bucket paths; mostly null-safe shims here.
// ──────────────────────────────────────────────────────────────────

private class NoopAiSettings : AiSettingsRepository {
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
    ) {}
    override fun setFeature(feature: AiSettings.Feature, enabled: Boolean) {}
    override fun setSyncEnabled(enabled: Boolean) {}
    override fun applyRemote(config: AiSettings.Config) { _state.value = config }
    override fun clear() {}
}

private class NoopDailyDigest : DailyDigestSettings {
    private val _state = MutableStateFlow(DailyDigestSettings.State())
    override val state: StateFlow<DailyDigestSettings.State> = _state.asStateFlow()
    override var onChange: ((DailyDigestSettings.Change, Boolean) -> Unit)? = null
    override fun setEnabled(enabled: Boolean) {}
    override fun setTime(hourOfDay: Int, minuteOfDay: Int) {}
    override fun applyRemote(enabled: Boolean, hourOfDay: Int, minuteOfDay: Int) {
        _state.value = DailyDigestSettings.State(enabled, hourOfDay, minuteOfDay)
    }
    override fun emitSyncToggledOff() {}
}
