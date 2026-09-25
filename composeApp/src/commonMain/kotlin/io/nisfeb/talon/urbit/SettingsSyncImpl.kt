package io.nisfeb.talon.urbit
import io.nisfeb.talon.ai.forSync
import io.nisfeb.talon.ai.switches
import io.nisfeb.talon.ai.keys
import kotlin.concurrent.Volatile
import io.nisfeb.talon.util.nowMs

import io.nisfeb.talon.ai.AiSettings
import io.nisfeb.talon.ai.AiSettingsRepository
import io.nisfeb.talon.util.Log
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.BookmarkEntity
import io.nisfeb.talon.data.BookmarkFolderEntity
import io.nisfeb.talon.data.BookmarkFolderMemberEntity
import io.nisfeb.talon.data.FolderEntity
import io.nisfeb.talon.data.FolderMemberEntity
import io.nisfeb.talon.data.GroupOrderEntity
import io.nisfeb.talon.data.NotifyPreferenceEntity
import io.nisfeb.talon.data.RailItemPrefEntity
import io.nisfeb.talon.ui.RailItem
import io.nisfeb.talon.ui.railItemOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Mirrors local UI-state (pins, group order, folders, notify prefs)
 * to the user's %settings agent on the ship. Source of truth is the
 * ship — on login we scry and replace Room with whatever %settings
 * holds, then pokes flow both ways: local change → poke, remote
 * change → subscription event → Room write.
 *
 * All per-row values are small JSON blobs; we never store message
 * content here.
 */
class SettingsSyncImpl(
    private val db: AppDatabase,
    private val aiSettings: AiSettingsRepository,
    /** Re-arm the loop alarm after a remote-applied loop change (enabled/
     *  interval can shift the next-fire time). commonMain can't reach the
     *  Android Loops facade, so the host injects this. No-op on desktop /
     *  tests (the while-open ticker re-reads loops every tick). */
    private val rearmLoops: () -> Unit = {},
) : SettingsSync {

    companion object {
        private const val TAG = "SettingsSync"

        /**
         * Per-bucket recovery decision used by [bootstrap]. Returns
         * true when the ship's bucket is either missing entirely or
         * present but empty — i.e., when this device should seed the
         * bucket from its local state rather than treat the ship as
         * authoritative. `internal` so tests can pin the rc9 recovery
         * contract that fixed Android's silent-don't-sync AI bucket.
         */
        internal fun bucketIsMissingOrEmpty(bucket: JsonObject?): Boolean =
            bucket == null || bucket.isEmpty()

        const val DESK = "talon"
        const val BUCKET_GROUP_ORDERS = "group-orders"
        const val BUCKET_FOLDERS = "folders"
        const val BUCKET_FOLDER_MEMBERS = "folder-members"
        const val BUCKET_NOTIFY_PREFS = "notify-prefs"
        const val BUCKET_RAIL_ITEMS = "rail-items"
        const val BUCKET_BOOKMARKS = "bookmarks"
        const val BUCKET_BOOKMARK_FOLDERS = "bookmark-folders"
        const val BUCKET_BOOKMARK_FOLDER_MEMBERS = "bookmark-folder-members"
        const val BUCKET_AI_SETTINGS = "ai-settings"
        const val BUCKET_WATCHWORDS = "watchwords"
        const val BUCKET_WATCHWORD_EXCLUDES = "watchword-excludes"
        const val BUCKET_STATUS_SEEN = "status-seen"
        // Cross-device UI preferences that don't warrant a Room table.
        // One entry per pref.
        const val BUCKET_UI_PREFS = "ui-prefs"
        // Retired: it gated word names for every ship, which is no
        // longer a question anyone is asked. Still named here so a
        // peer that has not upgraded cannot have its stale value
        // applied to something it never meant.
        const val ENTRY_MNEMONYM_NAMES = "mnemonym-names"
        const val ENTRY_NON_COMET_NAMES = "non-comet-names"
        const val ENTRY_ALWAYS_PATP = "always-patp"

        /** Entries this file applies by name; everything else in the
         *  bucket goes to the generic [applyUiPref]. */
        val HANDLED_UI_PREFS = setOf(
            ENTRY_MNEMONYM_NAMES, ENTRY_ALWAYS_PATP, ENTRY_NON_COMET_NAMES,
        )
        // User-shaped UI preferences. Screen-shaped ones (density,
        // fontScale, chatPaneListFraction, activeRailTab) are absent on
        // purpose — see SettingsSync.attachUiSettings.
        const val ENTRY_GROUP_CHANNEL_ORDER = "group-channel-order"
        const val ENTRY_FOLDER_ITEM_ORDER = "folder-item-order"
        const val ENTRY_RAIL_ITEM_ORDER = "rail-item-order"
        const val ENTRY_SMART_SEARCH = "smart-search-preferred"
        const val ENTRY_POWER_FEATURES = "power-features"
        const val ENTRY_HIDE_COMPOSER_BUTTONS = "hide-composer-buttons"
        const val ENTRY_ACCENT = "accent"
        const val ENTRY_THEMES = "themes"
        // Assistant history (Stage 2). Conversation metadata + append-only
        // turns, keyed by global id; embeddings stay device-local.
        const val BUCKET_ASSISTANT_CONVERSATIONS = "assistant-conversations"
        const val BUCKET_ASSISTANT_TURNS = "assistant-turns"
        // Loop definitions, keyed by gid. lastRunAt + run history are
        // device-local and never sync — only the definition travels.
        const val BUCKET_LOOPS = "loops"
        // Cross-device leases, keyed by loop gid or by the job's own key
        // (orrery-brief): { holder: <deviceId>, claimedAt: <ms> }. Pure coordination state,
        // not a user pref — it rides the desk scry but no applyBucket /
        // applyEntry branch maps it, so it never touches Room. See claim().
        const val BUCKET_AUTOMATION_CLAIMS = "automation-claims"
        // How long to let concurrent claims settle on the ship before
        // deciding the winner. Load-bearing: too short and two devices can
        // both read themselves as holder and double-fire.
        // ponytail: last-write-wins + settle. If it ever double-fires,
        // upgrade to per-device claim sub-keys (<gid>~<deviceId>, lowest id
        // wins) — deterministic, no settle window.
        /** Buckets whose apply path writes Room tables. [bootstrap]
         *  ship-wins applies these, and the recovery pass below re-seeds
         *  the ones the ship is missing; ai-settings / ui-prefs /
         *  status-seen / assistant / loops apply (and recover) on
         *  their own paths. */
        val ROOM_BACKED_BUCKETS = setOf(
            BUCKET_GROUP_ORDERS, BUCKET_FOLDERS, BUCKET_FOLDER_MEMBERS,
            BUCKET_NOTIFY_PREFS, BUCKET_RAIL_ITEMS,
            BUCKET_BOOKMARKS, BUCKET_BOOKMARK_FOLDERS, BUCKET_BOOKMARK_FOLDER_MEMBERS,
            BUCKET_WATCHWORDS, BUCKET_WATCHWORD_EXCLUDES,
        )

        private const val CLAIM_SETTLE_MS = 3_000L
        // Single-entry bucket: the seen high-water mark is global to the
        // user, not per-anything, so one stable key holds it.
        private const val STATUS_SEEN_ENTRY = "me"
        private const val AI_ENTRY = "config"

        /**
         * Credentials live in their own entry, apart from the
         * preferences, because a put replaces a whole entry and the
         * preferences are pushed by every device on every change. With
         * both in one entry, a device that had no key of its own wiped
         * the ship's copy the moment anybody toggled anything, and the
         * ship stopped being the backup that makes a new install work.
         * Nothing without a key of its own writes this entry.
         */
        internal const val AI_KEYS_ENTRY = "credentials"
        private val REVOKED = kotlinx.serialization.serializer<Map<String, io.nisfeb.talon.ai.KeyMark>>()

        // Wire schema version for the ai-settings entry. v1 (no
        // marker) is everything written before rc33 — treated as
        // legacy because the rc8-era Android push gap silently
        // seeded defaults to the ship that then overwrote post-rc30
        // local default-true values on every fresh install. v2
        // entries are explicit user writes from rc33+ and authoritative.
        private const val AI_SCHEMA_V2 = 2
    }

    @Volatile private var channel: UrbitChannel? = null
    @Volatile private var ui: io.nisfeb.talon.ui.UiSettings? = null
    /** Where a push that an arriving entry calls for runs, off the event stream: the shell's scope. */
    @Volatile private var pushScope: kotlinx.coroutines.CoroutineScope? = null
    private var marksPush: kotlinx.coroutines.Job? = null
    /** A marks push asked for before there was a scope to run it in; it runs once there is. */
    @Volatile private var marksOwed = false
    // Bootstrap can beat the host's attachUiSettings call. Hold what
    // the ship said until there's somewhere to put it.
    @Volatile private var pendingUiPrefs: JsonObject? = null
    // The value each ui-prefs entry last took from the ship (an apply)
    // or sent to it (a push). The watch collectors compare every
    // emission against this to tell a user edit from our own apply
    // coming back through the StateFlow — a flag window used to do
    // that job, but the flag cleared synchronously while the emission
    // arrived a coroutine later, and the just-received remote value
    // bounced straight back to the ship as a fresh poke.
    private val lastSyncedUiPref = HashMap<String, JsonElement>()
    private val lastSyncedUiPrefLock = kotlinx.atomicfu.locks.SynchronizedObject()

    private fun lastSyncedUiPref(entry: String): JsonElement? =
        kotlinx.atomicfu.locks.synchronized(lastSyncedUiPrefLock) { lastSyncedUiPref[entry] }

    private fun noteSyncedUiPref(entry: String, value: JsonElement) =
        kotlinx.atomicfu.locks.synchronized(lastSyncedUiPrefLock) { lastSyncedUiPref[entry] = value }

    private val _statusesSeenMs = MutableStateFlow(0L)
    override val statusesSeenMs: StateFlow<Long> = _statusesSeenMs.asStateFlow()

    /** Monotonic merge: the seen marker is a high-water mark, so a stale
     *  remote value (or the "ship wins" bootstrap) can never move a
     *  device that's already viewed more recently backwards. */
    private fun bumpStatusesSeen(ms: Long) {
        if (ms > _statusesSeenMs.value) _statusesSeenMs.value = ms
    }

    override suspend fun pushStatusesSeen(ms: Long) {
        bumpStatusesSeen(ms)
        pokePutEntry(
            BUCKET_STATUS_SEEN,
            STATUS_SEEN_ENTRY,
            buildJsonObject { put("ms", ms) },
        )
    }

    override suspend fun pushNonCometNames(enabled: Boolean) {
        pokePutEntry(
            BUCKET_UI_PREFS,
            ENTRY_NON_COMET_NAMES,
            buildJsonObject { put("enabled", enabled) },
        )
    }

    override suspend fun pushAlwaysPatp(enabled: Boolean) {
        pokePutEntry(
            BUCKET_UI_PREFS,
            ENTRY_ALWAYS_PATP,
            buildJsonObject { put("enabled", enabled) },
        )
    }

    override fun attach(channel: UrbitChannel) {
        this.channel = channel
    }

    override fun attachUiSettings(
        settings: io.nisfeb.talon.ui.UiSettings,
        scope: kotlinx.coroutines.CoroutineScope,
    ) {
        if (ui != null) return
        ui = settings
        pushScope = scope
        if (marksOwed) pushMarks()
        // Drain anything bootstrap parked before we had a store.
        pendingUiPrefs?.let { parked ->
            pendingUiPrefs = null
            parked.forEach { (key, v) ->
                if (key !in HANDLED_UI_PREFS) {
                    applyUiPref(key, unwrap(v))
                }
            }
        }
        // Watch each synced preference and push changes the user makes
        // here. drop(1) skips the current value — attaching is not an
        // edit — and the last-synced value keeps an incoming change
        // from echoing back out.
        fun <T> watch(flow: Flow<T>, entry: String, encode: (T) -> JsonElement) {
            scope.launch {
                flow.drop(1).collect { v ->
                    val encoded = encode(v)
                    // Our own apply coming back through the StateFlow,
                    // or a duplicate of what we last pushed: not an edit.
                    if (lastSyncedUiPref(entry) == encoded) return@collect
                    noteSyncedUiPref(entry, encoded)
                    runCatching { pokePutEntry(BUCKET_UI_PREFS, entry, encoded) }
                }
            }
        }
        watch(settings.groupChannelOrder, ENTRY_GROUP_CHANNEL_ORDER) { str(it.name) }
        watch(settings.folderItemOrder, ENTRY_FOLDER_ITEM_ORDER) { str(it.name) }
        watch(settings.smartSearchPreferred, ENTRY_SMART_SEARCH) { bool(it) }
        watch(settings.powerFeaturesEnabled, ENTRY_POWER_FEATURES) { bool(it) }
        watch(settings.hideComposerButtons, ENTRY_HIDE_COMPOSER_BUTTONS) { bool(it) }
        watch(settings.railItemOrder, ENTRY_RAIL_ITEM_ORDER, ::encodeRailItemOrder)
        watch(settings.accentSettings, ENTRY_ACCENT, ::encodeAccent)
        watch(settings.themeSettings, ENTRY_THEMES, ::encodeThemes)
    }

    private fun encodeRailItemOrder(order: List<RailItem>): JsonElement =
        buildJsonObject {
            put("order", JsonArray(order.map { JsonPrimitive(it.name) }))
        }

    private fun encodeAccent(a: io.nisfeb.talon.ui.AccentSettings): JsonElement =
        buildJsonObject {
            a.enabled?.let { put("enabled", it) }
            put("mode", a.mode.name)
            a.customHex?.let { put("customHex", it) }
        }

    private fun encodeThemes(t: io.nisfeb.talon.ui.theme.ThemeSettings): JsonElement =
        Json.parseToJsonElement(t.toJson())

    private fun bool(v: Boolean): JsonElement = buildJsonObject { put("enabled", v) }
    private fun str(v: String): JsonElement = buildJsonObject { put("value", v) }

    /** Write a remote ui-prefs entry into the local store without
     *  bouncing it back out as a push: the watch collectors compare
     *  every emission against the value noted here. */
    private inline fun applyLocal(entry: String, value: JsonElement, block: () -> Unit) {
        noteSyncedUiPref(entry, value)
        block()
    }

    /** Apply one ui-prefs entry that lives in [io.nisfeb.talon.ui.UiSettings]. */
    private fun applyUiPref(entry: String, value: JsonElement?) {
        val settings = ui ?: run {
            // Live facts can beat attachUiSettings; park the single
            // entry the way applyBucket parks the whole bucket, or the
            // ship's word is lost. (Values park unwrapped — the drain's
            // unwrap leaves an already-decoded object untouched.)
            val parked = pendingUiPrefs ?: JsonObject(emptyMap())
            pendingUiPrefs = JsonObject(parked + (entry to (value ?: JsonNull)))
            return
        }
        val obj = value as? JsonObject ?: return
        applyLocal(entry, obj) {
            when (entry) {
                ENTRY_GROUP_CHANNEL_ORDER -> obj["value"].asStr()?.let { name ->
                    runCatching { io.nisfeb.talon.ui.GroupChannelOrder.valueOf(name) }
                        .onSuccess { settings.setGroupChannelOrder(it) }
                }
                ENTRY_FOLDER_ITEM_ORDER -> obj["value"].asStr()?.let { name ->
                    runCatching { io.nisfeb.talon.ui.FolderItemOrder.valueOf(name) }
                        .onSuccess { settings.setFolderItemOrder(it) }
                }
                ENTRY_SMART_SEARCH ->
                    obj["enabled"].asBool()?.let { settings.setSmartSearchPreferred(it) }
                ENTRY_POWER_FEATURES ->
                    obj["enabled"].asBool()?.let { settings.setPowerFeaturesEnabled(it) }
                ENTRY_HIDE_COMPOSER_BUTTONS ->
                    obj["enabled"].asBool()?.let { settings.setHideComposerButtons(it) }
                ENTRY_RAIL_ITEM_ORDER -> {
                    val names = (obj["order"] as? JsonArray)?.mapNotNull { it.asStr() }
                        ?: return@applyLocal
                    val items = names.mapNotNull { n ->
                        runCatching { io.nisfeb.talon.ui.RailItem.valueOf(n) }.getOrNull()
                    }
                    if (items.isNotEmpty()) settings.setRailItemOrder(items)
                }
                ENTRY_ACCENT -> settings.setAccentSettings(
                    io.nisfeb.talon.ui.AccentSettings(
                        enabled = obj["enabled"].asBool(),
                        mode = obj["mode"].asStr()
                            ?.let { m ->
                                runCatching { io.nisfeb.talon.ui.AccentMode.valueOf(m) }.getOrNull()
                            }
                            ?: io.nisfeb.talon.ui.AccentMode.Profile,
                        customHex = obj["customHex"].asStr(),
                    ),
                )
                ENTRY_THEMES -> io.nisfeb.talon.ui.theme.ThemeSettings.fromJson(obj.toString())
                    ?.let { settings.setThemeSettings(it) }
            }
        }
    }

    /**
     * On session start: scry `%settings /desk/<DESK>` for everything
     * we care about and overwrite local Room tables with whatever the
     * ship holds. Ship wins — local edits made offline without a poke
     * don't survive this. Subscribe afterward so live changes flow in.
     */
    override suspend fun bootstrap() {
        val ch = channel
        if (ch == null) {
            Log.w(TAG, "bootstrap skipped: channel not attached")
            return
        }
        Log.i(TAG, "bootstrap starting")
        val scried = runCatching { ch.scry("settings", "/desk/$DESK") }
        if (scried.isFailure) {
            // A ship we could not reach is not a ship with no settings:
            // seeding local state now would push this device's values
            // over whatever the ship and other devices hold. The next
            // connect runs bootstrap again.
            Log.w(TAG, "bootstrap scry failed; not seeding", scried.exceptionOrNull())
            return
        }
        val body = scried.getOrNull() as? JsonObject

        // Ship returns { "desk": { <bucket>: { <entry>: <value> } } }
        // or the inner desk map directly. Handle both.
        val deskMap = (body?.get("desk") as? JsonObject) ?: body

        val bucketSummary = deskMap?.entries?.joinToString(",") { (k, v) ->
            val size = (v as? JsonObject)?.size ?: 0
            "$k=$size"
        } ?: "<no desk>"
        Log.i(TAG, "bootstrap scry returned buckets: $bucketSummary")

        val hasAnyBucket = deskMap != null && deskMap.keys.any {
            (deskMap[it] as? JsonObject)?.isNotEmpty() == true
        }

        if (!hasAnyBucket) {
            // Ship has nothing yet for this desk — upload local state
            // so this device's existing pins/folders/etc. survive and
            // become the starting point for cross-device sync.
            Log.i(TAG, "seeding %settings from local state")
            seedFromLocal()
        } else {
            // Ship has state: treat it as authoritative, replace local.
            // Only what the ship has. A missing or empty bucket means this
            // device's copy stands (see bucketIsMissingOrEmpty) and is
            // seeded below; applying it as empty first wiped the rows the
            // seed was about to send, so a bucket new to the ship erased
            // that data here.
            for (bucket in listOf(
                BUCKET_GROUP_ORDERS, BUCKET_FOLDERS, BUCKET_FOLDER_MEMBERS,
                BUCKET_NOTIFY_PREFS, BUCKET_RAIL_ITEMS, BUCKET_BOOKMARKS,
                BUCKET_BOOKMARK_FOLDERS, BUCKET_BOOKMARK_FOLDER_MEMBERS,
                // Per-feature toggles follow the user; applyAiEntry gates
                // the cloud-key fields on local syncEnabled, so the API
                // key only travels with explicit consent.
                BUCKET_AI_SETTINGS,
                // Pulled here too: a preference used to reach only a device
                // connected when it changed, and a fresh login kept its
                // local defaults.
                BUCKET_UI_PREFS,
                // Pulled so a fresh login has the ship's terms.
                BUCKET_WATCHWORDS, BUCKET_WATCHWORD_EXCLUDES,
                BUCKET_STATUS_SEEN,
                // Upserted, not replaced, so conversations made offline here
                // survive; conversations before turns, so turns resolve
                // their convGid (a stub is made either way).
                BUCKET_ASSISTANT_CONVERSATIONS, BUCKET_ASSISTANT_TURNS,
                // Upserted too, so loops made offline survive; lastRunAt is
                // kept per row.
                BUCKET_LOOPS,
            )) {
                val entries = deskMap!![bucket] as? JsonObject
                if (!bucketIsMissingOrEmpty(entries)) applyBucket(bucket, entries)
            }

            // Per-bucket recovery for the Room-backed buckets: any the
            // ship is missing (or holds empty) gets re-seeded from the
            // local tables. Buckets the ship holds were applied above
            // and stay ship-wins. The pushes are idempotent (put-entry
            // is upsert) and only fire on a gap, so we never overwrite
            // a peer device's good state with ours.
            for (bucket in ROOM_BACKED_BUCKETS) {
                if ((deskMap[bucket] as? JsonObject).isNullOrEmpty()) {
                    Log.i(TAG, "ship missing $bucket bucket — seeding from local")
                    runCatching { seedBucketFromLocal(bucket) }
                        .onFailure { Log.w(TAG, "$bucket seed push failed", it) }
                }
            }
            // ui-prefs isn't Room-backed (it lives in the UiSettings
            // flows + name-display singletons), so it recovers through
            // its own push rather than seedBucketFromLocal.
            if ((deskMap[BUCKET_UI_PREFS] as? JsonObject).isNullOrEmpty()) {
                Log.i(TAG, "ship missing ui-prefs bucket — seeding from local")
                runCatching { pushUiPrefsFromLocal() }
                    .onFailure { Log.w(TAG, "ui-prefs seed push failed", it) }
            }
        }

        // ai-settings recovers on its own path (it runs either way:
        // in the seed case above it doubles as the bucket's first
        // push). The 0.11.0-rc8 era ran for months with Android's
        // aiSettings.onStateChange unwired — every phone-side AI
        // feature toggle stayed local, so the ship's ai-settings
        // bucket was empty for affected users even though their
        // group-orders / folders / etc. were populated. Without
        // per-bucket recovery a fresh install would inherit the gap
        // forever. Same idempotent-gap-fill rules as the Room-backed
        // recovery above.
        if ((deskMap?.get(BUCKET_AI_SETTINGS) as? JsonObject).isNullOrEmpty()) {
            Log.i(TAG, "ship missing ai-settings bucket — seeding from local")
            runCatching { pushAiSettings() }
                .onFailure { Log.w(TAG, "ai-settings seed push failed", it) }
        } else {
            // Schema upgrade: if the ship's entry is legacy (no
            // schemaVersion), re-push from local so the bucket gets
            // stamped v2. applyAiEntry already discarded the legacy
            // toggle values and kept local defaults, so this push
            // codifies the just-applied state on the ship.
            // Idempotent — once the entry is v2, this branch is a
            // no-op on every subsequent bootstrap.
            val aiEntry = (deskMap?.get(BUCKET_AI_SETTINGS) as? JsonObject)?.get(AI_ENTRY)
            val legacy = (aiEntry as? JsonObject)?.let {
                (it["schemaVersion"].asInt() ?: 0) < AI_SCHEMA_V2
            } ?: false
            if (legacy) {
                Log.i(TAG, "ship has legacy ai-settings entry — upgrading to schemaVersion=$AI_SCHEMA_V2")
                runCatching { pushAiSettings() }
                    .onFailure { Log.w(TAG, "ai-settings upgrade push failed", it) }
            }
        }
        // A key typed while signed out reached no ship, and nothing
        // pushed it afterwards unless some other setting happened to
        // change. On every connect, a device that has credentials makes
        // sure the ship has them. Idempotent: the same entry again.
        val creds = (deskMap?.get(BUCKET_AI_SETTINGS) as? JsonObject)?.get(AI_KEYS_ENTRY)
        val cfg = aiSettings.state.value
        if (cfg.syncEnabled && cfg.hasCredentials() && creds == null) {
            Log.i(TAG, "ship has no credentials entry — seeding from this device")
            runCatching { pushAiSettings() }
                .onFailure { Log.w(TAG, "credentials seed push failed", it) }
        }

        // Subscribe for live updates from other devices.
        runCatching { ch.subscribe("settings", "/desk/$DESK") }
            .onFailure { Log.w(TAG, "subscribe failed", it) }
        Log.i(TAG, "bootstrap done")
    }

    private fun JsonObject?.isNullOrEmpty(): Boolean = bucketIsMissingOrEmpty(this)

    /** First-time setup: push whatever's local to the ship. */
    private suspend fun seedFromLocal() {
        seedGroupOrders()
        seedFolders()
        seedFolderMembers()
        seedNotifyPrefs()
        seedRailItems()
        seedBookmarks()
        seedBookmarkFolders()
        seedBookmarkFolderMembers()
        // Watchwords key on a sanitized term rather than a Room id, so
        // they ride their own per-entry push path.
        pushAllWatchwords()
        // UI prefs live in the UiSettings flows + name-display
        // singletons, not Room.
        pushUiPrefsFromLocal()
    }

    /** Re-seed one Room-backed bucket from local state — the recovery
     *  counterpart of [seedFromLocal], dispatched per [ROOM_BACKED_BUCKETS]. */
    private suspend fun seedBucketFromLocal(bucket: String) {
        when (bucket) {
            BUCKET_GROUP_ORDERS -> seedGroupOrders()
            BUCKET_FOLDERS -> seedFolders()
            BUCKET_FOLDER_MEMBERS -> seedFolderMembers()
            BUCKET_NOTIFY_PREFS -> seedNotifyPrefs()
            BUCKET_RAIL_ITEMS -> seedRailItems()
            BUCKET_BOOKMARKS -> seedBookmarks()
            BUCKET_BOOKMARK_FOLDERS -> seedBookmarkFolders()
            BUCKET_BOOKMARK_FOLDER_MEMBERS -> seedBookmarkFolderMembers()
            // One flush covers both watchword buckets; put-entry is an
            // upsert, so re-pushing the half the ship already has is
            // harmless.
            BUCKET_WATCHWORDS, BUCKET_WATCHWORD_EXCLUDES -> pushAllWatchwords()
        }
    }

    private suspend fun seedGroupOrders() {
        db.groupOrders().stream().first()
            .takeIf { it.isNotEmpty() }?.let { orders ->
                pokePutBucket(
                    BUCKET_GROUP_ORDERS,
                    buildJsonObject {
                        orders.forEach { o ->
                            put(o.flag, buildJsonObject { put("ordinal", o.ordinal) })
                        }
                    },
                )
            }
    }

    private suspend fun seedFolders() {
        db.folders().streamFolders().first()
            .takeIf { it.isNotEmpty() }?.let { folders ->
                pokePutBucket(
                    BUCKET_FOLDERS,
                    buildJsonObject {
                        folders.forEach { f ->
                            put(f.id.toString(), buildJsonObject {
                                put("name", f.name)
                                put("sortOrder", f.sortOrder)
                            })
                        }
                    },
                )
            }
    }

    private suspend fun seedFolderMembers() {
        db.folders().streamMembers().first()
            .takeIf { it.isNotEmpty() }?.let { members ->
                pokePutBucket(
                    BUCKET_FOLDER_MEMBERS,
                    buildJsonObject {
                        members.forEach { m ->
                            put(folderMemberKey(m.folderId, m.whom), buildJsonObject {
                                put("ordinal", m.ordinal)
                                put("kind", m.kind)
                            })
                        }
                    },
                )
            }
    }

    private suspend fun seedNotifyPrefs() {
        db.notifyPrefs().streamAll().first()
            .takeIf { it.isNotEmpty() }?.let { prefs ->
                pokePutBucket(
                    BUCKET_NOTIFY_PREFS,
                    buildJsonObject {
                        prefs.forEach { p ->
                            put(p.whom, buildJsonObject { put("level", p.level) })
                        }
                    },
                )
            }
    }

    private suspend fun seedRailItems() {
        db.railItemPrefs().streamAll().first()
            .takeIf { it.isNotEmpty() }?.let { rows ->
                pokePutBucket(
                    BUCKET_RAIL_ITEMS,
                    buildJsonObject {
                        rows.forEach { r ->
                            put(r.itemName, buildJsonObject { put("visible", r.visible) })
                        }
                    },
                )
            }
    }

    private suspend fun seedBookmarks() {
        db.bookmarks().streamAll().first()
            .takeIf { it.isNotEmpty() }?.let { bookmarks ->
                pokePutBucket(
                    BUCKET_BOOKMARKS,
                    buildJsonObject {
                        bookmarks.forEach { b ->
                            put(bookmarkKey(b.whom, b.postId), buildJsonObject {
                                put("ts", b.bookmarkedMs)
                            })
                        }
                    },
                )
            }
    }

    private suspend fun seedBookmarkFolders() {
        db.bookmarkFolders().streamFolders().first()
            .takeIf { it.isNotEmpty() }?.let { folders ->
                pokePutBucket(
                    BUCKET_BOOKMARK_FOLDERS,
                    buildJsonObject {
                        folders.forEach { f ->
                            put(f.id.toString(), buildJsonObject {
                                put("name", f.name)
                                put("sortOrder", f.sortOrder)
                            })
                        }
                    },
                )
            }
    }

    private suspend fun seedBookmarkFolderMembers() {
        db.bookmarkFolders().streamMembers().first()
            .takeIf { it.isNotEmpty() }?.let { members ->
                pokePutBucket(
                    BUCKET_BOOKMARK_FOLDER_MEMBERS,
                    buildJsonObject {
                        members.forEach { m ->
                            put(
                                bookmarkFolderMemberKey(m.folderId, m.whom, m.postId),
                                buildJsonObject { put("ordinal", m.ordinal) },
                            )
                        }
                    },
                )
            }
    }

    /**
     * Push the current local UI preferences to %settings — the seed
     * counterpart of the attachUiSettings watchers, which only fire on
     * edits. Reads the flows' current values directly, so it serves
     * both bootstrap's first seed and the missing-bucket recovery.
     * Name-display toggles live on singletons rather than in
     * UiSettings; they push through their existing entry points.
     */
    private suspend fun pushUiPrefsFromLocal() {
        val settings = ui
        if (settings != null) {
            suspend fun push(entry: String, encoded: JsonElement) {
                noteSyncedUiPref(entry, encoded)
                pokePutEntry(BUCKET_UI_PREFS, entry, encoded)
            }
            push(ENTRY_GROUP_CHANNEL_ORDER, str(settings.groupChannelOrder.value.name))
            push(ENTRY_FOLDER_ITEM_ORDER, str(settings.folderItemOrder.value.name))
            push(ENTRY_SMART_SEARCH, bool(settings.smartSearchPreferred.value))
            push(ENTRY_POWER_FEATURES, bool(settings.powerFeaturesEnabled.value))
            push(ENTRY_HIDE_COMPOSER_BUTTONS, bool(settings.hideComposerButtons.value))
            push(ENTRY_RAIL_ITEM_ORDER, encodeRailItemOrder(settings.railItemOrder.value))
            push(ENTRY_ACCENT, encodeAccent(settings.accentSettings.value))
            push(ENTRY_THEMES, encodeThemes(settings.themeSettings.value))
        }
        pushAlwaysPatp(io.nisfeb.talon.ui.ShipNames.alwaysPatp.value)
        pushNonCometNames(io.nisfeb.talon.ui.AzimuthNames.enabled.value)
    }

    /** Apply a %settings SSE fact to the right bucket. */
    override suspend fun applySettingsEvent(wrapper: JsonObject) {
        // %settings wraps the action; tolerate both shapes.
        val payload = (wrapper["settings-event"] as? JsonObject) ?: wrapper
        // Expected shapes (defensively handled):
        //   {put-entry: {desk, bucket-key, entry-key, value}}
        //   {del-entry: {desk, bucket-key, entry-key}}
        //   {put-bucket: {desk, bucket-key, bucket}}
        //   {del-bucket: {desk, bucket-key}}
        (payload["put-entry"] as? JsonObject)?.let { e ->
            if (e.desk() != DESK) return
            val bucket = e.bucketKey() ?: return
            val entry = e.entryKey() ?: return
            val value = e["value"] ?: JsonNull
            applyEntry(bucket, entry, value)
            return
        }
        (payload["del-entry"] as? JsonObject)?.let { e ->
            if (e.desk() != DESK) return
            val bucket = e.bucketKey() ?: return
            val entry = e.entryKey() ?: return
            removeEntry(bucket, entry)
            return
        }
        (payload["put-bucket"] as? JsonObject)?.let { e ->
            if (e.desk() != DESK) return
            val bucket = e.bucketKey() ?: return
            applyBucket(bucket, e["bucket"] as? JsonObject)
            return
        }
        (payload["del-bucket"] as? JsonObject)?.let { e ->
            if (e.desk() != DESK) return
            val bucket = e.bucketKey() ?: return
            clearBucketLocally(bucket)
            return
        }
    }

    // ───────── outbound ─────────

    /**
     * Fast local-only reorder. Use this from a drag `onMove` callback —
     * it writes Room without touching the ship, so the LazyColumn can
     * animate the swap in the same frame. Call [pushGroupOrders] once
     * when the drag ends to sync the final order.
     */
    override suspend fun reorderGroupOrdersLocal(flags: List<String>) {
        db.groupOrders().reorder(flags)
    }

    /** Push the current local group order to %settings in one batch. */
    override suspend fun pushGroupOrders() {
        val flags = db.groupOrders().stream().first()
            .sortedBy { it.ordinal }
            .map { it.flag }
        pokePutBucket(
            BUCKET_GROUP_ORDERS,
            buildJsonObject {
                flags.forEachIndexed { i, f ->
                    put(f, buildJsonObject { put("ordinal", i) })
                }
            },
        )
    }

    /** Combined local+push. Kept for callers that aren't drag-driven. */
    suspend fun reorderGroupOrders(flags: List<String>) {
        reorderGroupOrdersLocal(flags)
        pushGroupOrders()
    }

    override suspend fun createFolder(name: String, sortOrder: Int): Long {
        val id = db.folders().createFolder(FolderEntity(name = name, sortOrder = sortOrder))
        pokePutEntry(
            BUCKET_FOLDERS, id.toString(),
            buildJsonObject {
                put("name", name)
                put("sortOrder", sortOrder)
            },
        )
        return id
    }

    override suspend fun renameFolder(id: Long, name: String) {
        db.folders().rename(id, name)
        // Send the full folder row so remote devices don't regress
        // sortOrder to 0 when they apply the put-entry.
        val sortOrder = db.folders().get(id)?.sortOrder ?: 0
        pokePutEntry(
            BUCKET_FOLDERS, id.toString(),
            buildJsonObject {
                put("name", name)
                put("sortOrder", sortOrder)
            },
        )
    }

    override suspend fun deleteFolder(id: Long) {
        db.folders().deleteMembersOf(id)
        db.folders().delete(id)
        pokeDelEntry(BUCKET_FOLDERS, id.toString())
        // Also clear any folder-members entries keyed by this folder.
        // %settings has no wildcard del — so push a fresh bucket minus
        // anything with this folder id prefix. Cheap because typically
        // few folders.
        clearFolderMembersForFolder(id)
    }

    override suspend fun addFolderMember(folderId: Long, whom: String) {
        val next = db.folders().maxOrdinalIn(folderId) + 1
        db.folders().addMemberRaw(folderId, whom, next, FolderMemberEntity.KIND_WHOM)
        pokePutEntry(
            BUCKET_FOLDER_MEMBERS, folderMemberKey(folderId, whom),
            buildJsonObject {
                put("ordinal", next)
                put("kind", FolderMemberEntity.KIND_WHOM)
            },
        )
    }

    override suspend fun addGroupToFolder(folderId: Long, groupFlag: String) {
        val next = db.folders().maxOrdinalIn(folderId) + 1
        db.folders().addMemberRaw(folderId, groupFlag, next, FolderMemberEntity.KIND_GROUP)
        pokePutEntry(
            BUCKET_FOLDER_MEMBERS, folderMemberKey(folderId, groupFlag),
            buildJsonObject {
                put("ordinal", next)
                put("kind", FolderMemberEntity.KIND_GROUP)
            },
        )
    }

    override suspend fun removeFolderMember(folderId: Long, whom: String) {
        db.folders().removeMember(folderId, whom)
        pokeDelEntry(BUCKET_FOLDER_MEMBERS, folderMemberKey(folderId, whom))
    }

    /** Alias for clarity — groups live in the same table as whoms. */
    override suspend fun removeGroupFromFolder(folderId: Long, groupFlag: String) =
        removeFolderMember(folderId, groupFlag)

    /**
     * Fast local-only reorder — Room write, no ship I/O. Call from a
     * drag's `onMove` so the LazyColumn animates smoothly. Follow up
     * with [pushFolderMembersOrder] when the drag ends.
     */
    override suspend fun reorderFolderMembersLocal(folderId: Long, whoms: List<String>) {
        db.folders().reorderMembers(folderId, whoms)
    }

    /**
     * Push the current folder's member order to %settings. N pokes for
     * N members — kept off the drag-hot-path so it doesn't stall the
     * reorder animation.
     */
    override suspend fun pushFolderMembersOrder(folderId: Long) {
        val members = db.folders().streamMembers().first()
            .filter { it.folderId == folderId }
            .sortedBy { it.ordinal }
        members.forEachIndexed { i, m ->
            pokePutEntry(
                BUCKET_FOLDER_MEMBERS, folderMemberKey(folderId, m.whom),
                buildJsonObject {
                    put("ordinal", i)
                    put("kind", m.kind)
                },
            )
        }
    }

    /** Combined local+push. Kept for non-drag callers. */
    suspend fun reorderFolderMembers(folderId: Long, whoms: List<String>) {
        reorderFolderMembersLocal(folderId, whoms)
        pushFolderMembersOrder(folderId)
    }

    /**
     * The credentials as they go on the wire. They are written to their
     * own entry, and mirrored into the preferences entry for builds
     * before 1.8 that read only that one: a key-less device's preference
     * push drops the mirror, which is the old wipe, but the entry beside
     * it still holds them and nothing this side of 1.8 reads the mirror.
     */
    private fun JsonObjectBuilder.aiCredentials(cfg: AiSettings.Config) {
        put("provider", cfg.provider.name)
        // Only ship a credential we actually have. Emitting "" would
        // make the ship's entry authoritatively key-less, and a
        // later pull (here or on a peer) then blanks a real local
        // key — the "keys not persisted" data loss. Absent ≠ empty.
        // A removal travels as a mark in revokedKeys, below.
        if (cfg.apiKey.isNotBlank()) put("apiKey", cfg.apiKey)
        cfg.model?.let { put("model", it) }
        cfg.baseUrl?.let { put("baseUrl", it) }
        // Brave key rides the same opt-in gate as the LLM key —
        // both are service credentials; same don't-ship-empty rule.
        if (cfg.braveApiKey.isNotBlank()) put("braveApiKey", cfg.braveApiKey)
        // STT key: same don't-ship-empty rule as the other
        // credentials. Shipping "" whenever it was blank let a
        // device that never had the key blank everyone's on its
        // next push of any AI setting. A removal travels as an
        // explicit stamp instead, so peers can tell "removed"
        // from "this device just doesn't have it".
        if (cfg.sttApiKey.isNotBlank()) {
            put("sttApiKey", cfg.sttApiKey)
        } else if (cfg.sttApiKeyRemovedAtMs > 0L) {
            put("sttApiKeyRemovedAtMs", cfg.sttApiKeyRemovedAtMs)
        }
        // The private model. Its address is not a secret but
        // it travels with the key that opens it.
        cfg.privateBaseUrl?.let { put("privateBaseUrl", it) }
        cfg.privateModel?.let { put("privateModel", it) }
        if (cfg.privateApiKey.isNotBlank()) put("privateApiKey", cfg.privateApiKey)
        // The profile's keys, by provider, on the same terms:
        // only those a provider here actually has.
        cfg.savedProfile?.keys()?.takeIf { it.isNotEmpty() }?.let { keys ->
            put("providerKeys", buildJsonObject { keys.forEach { (id, k) -> put(id, k) } })
        }
        // The providers and models, which the frontier and
        // private model fields above always kept to this
        // entry: without keys or model lists.
        cfg.savedProfile?.let {
            put("profile", Json.encodeToJsonElement(io.nisfeb.talon.ai.AiProfile.serializer(), it.forSync()))
        }
        // Which model reads your messages: a fact about the frontier
        // provider, and meaningless without one, so it travels here.
        put("frontierReadsMessages", cfg.frontierReadsMessages)
        // Every key taken out anywhere, so it leaves every device and
        // no device's copy brings it back.
        if (cfg.revokedKeys.isNotEmpty()) put("revokedKeys", Json.encodeToJsonElement(REVOKED, cfg.revokedKeys))
    }

    /**
     * Push the current AI settings to %settings. Per-feature toggles
     * (catchMeUp, smartFeatures, ask-Urbit, the agent) ALWAYS push —
     * they are user preferences with no security cost and should follow
     * the user across devices. Cloud-key fields (provider / apiKey /
     * model / baseUrl) only push when the user has explicitly opted
     * into sync via `syncEnabled`; the API key is service credential
     * material and shipping it without consent leaks it to the ship.
     *
     * Toggling `syncEnabled` from on to off therefore overwrites the
     * ship's entry with a feature-only blob, dropping the cloud-key
     * fields without needing a separate clear path.
     */


    override suspend fun pushAiSettings() {
        val cfg = aiSettings.state.value
        // The credentials, in their own entry, and only from a device
        // that has some. A device with none says nothing about them, so
        // it cannot wipe the ship's copy by saving a preference.
        if (cfg.syncEnabled && cfg.hasCredentials()) {
            pokePutEntry(
                BUCKET_AI_SETTINGS, AI_KEYS_ENTRY,
                buildJsonObject {
                    put("schemaVersion", AI_SCHEMA_V2)
                    aiCredentials(cfg)
                },
            )
        }
        pokePutEntry(
            BUCKET_AI_SETTINGS, AI_ENTRY,
            buildJsonObject {
                // Stamp v2 so applyAiEntry on a peer device knows
                // these toggle values are an explicit write, not a
                // legacy seed from the rc8-era recovery path.
                put("schemaVersion", AI_SCHEMA_V2)
                put("catchMeUpEnabled", cfg.catchMeUpEnabled)
                put("smartFeaturesEnabled", cfg.smartFeaturesEnabled)
                put("askUrbitEnabled", cfg.askUrbitEnabled)
                put("agentEnabled", cfg.agentEnabled)
                // User's custom agent system-prompt parts (blank = built-in
                // default). Preferences, not credentials — always push.
                put("urbitKnowledgePrompt", cfg.urbitKnowledgePrompt)
                put("assistantPrompt", cfg.assistantPrompt)
                put("loopPrompt", cfg.loopPrompt)
                // The profile's switches, which travel like the toggles
                // above; its providers and models ride the credentials.
                cfg.savedProfile?.let { put("switches", it.switches()) }
                if (cfg.syncEnabled && cfg.hasCredentials()) aiCredentials(cfg)
            },
        )
    }

    /**
     * Nuke the ship's AI settings bucket. Used by callers that want
     * to fully clear the user's AI prefs from the ship — pushAiSettings
     * already drops the cloud-key fields on its own when syncEnabled
     * flips off, so this is the harder reset.
     */
    suspend fun clearAiSettingsOnShip() {
        val ch = channel ?: return
        val payload = buildJsonObject {
            put("del-bucket", buildJsonObject {
                put("desk", DESK)
                put("bucket-key", BUCKET_AI_SETTINGS)
            })
        }
        runCatching { ch.poke(app = "settings", mark = "settings-event", payload = payload) }
            .onFailure { Log.w(TAG, "del-bucket ai-settings failed", it) }
    }

    override suspend fun pushAssistantTurn(
        conversation: io.nisfeb.talon.data.AssistantConversationEntity,
        turn: io.nisfeb.talon.data.AssistantHistoryEntity,
    ) {
        if (conversation.gid.isBlank() || turn.gid.isBlank()) return
        pokePutEntry(
            BUCKET_ASSISTANT_CONVERSATIONS, conversation.gid,
            buildJsonObject {
                put("title", conversation.title)
                put("createdAt", conversation.createdAt)
                put("updatedAt", conversation.updatedAt)
                put("turnCount", conversation.turnCount)
            },
        )
        pokePutEntry(
            BUCKET_ASSISTANT_TURNS, turn.gid,
            buildJsonObject {
                // `mode` is intentionally not pushed — it's a vestigial
                // local field; the applier defaults it to "Assistant".
                put("convGid", conversation.gid)
                put("question", turn.question)
                put("answer", turn.answer)
                put("createdAt", turn.createdAt)
            },
        )
    }

    override suspend fun clearAssistantHistoryOnShip() {
        val ch = channel ?: return
        for (bucket in listOf(BUCKET_ASSISTANT_CONVERSATIONS, BUCKET_ASSISTANT_TURNS)) {
            val payload = buildJsonObject {
                put("del-bucket", buildJsonObject {
                    put("desk", DESK)
                    put("bucket-key", bucket)
                })
            }
            runCatching { ch.poke(app = "settings", mark = "settings-event", payload = payload) }
                .onFailure { Log.w(TAG, "del-bucket $bucket failed", it) }
        }
    }

    override suspend fun pushLoop(loop: io.nisfeb.talon.data.LoopEntity) {
        if (loop.gid.isBlank()) return
        pokePutEntry(
            BUCKET_LOOPS, loop.gid,
            buildJsonObject {
                // Definition only. id + lastRunAt + run history stay local;
                // so does writesAuthorized — the unattended-write grant is a
                // per-device decision (see upsertLoop), so we don't ship it.
                put("name", loop.name)
                put("prompt", loop.prompt)
                put("intervalMinutes", loop.intervalMinutes)
                put("enabled", loop.enabled)
                put("createdAt", loop.createdAt)
                put("updatedAt", loop.updatedAt)
                put("scheduleKind", loop.scheduleKind)
                put("atMinuteOfDay", loop.atMinuteOfDay)
                put("daysMask", loop.daysMask)
            },
        )
    }

    override suspend fun deleteLoop(gid: String) {
        if (gid.isBlank()) return
        pokeDelEntry(BUCKET_LOOPS, gid)
        // Drop any lease so a recreated loop with the same gid starts clean.
        pokeDelEntry(BUCKET_AUTOMATION_CLAIMS, gid)
    }

    /**
     * Cross-device lease so exactly one running device performs a scheduled
     * write-loop fire (see [io.nisfeb.talon.ai.LoopWriteCoordinator]). The
     * lease lives in %settings/[BUCKET_AUTOMATION_CLAIMS] keyed by gid; it
     * holds the current runner's device id + when it last claimed.
     *
     * I run if I already hold it (and refresh), or if it's unclaimed/stale
     * (holder went offline ~2 intervals) and I win the contest after a
     * settle. A fresh holder that isn't me → I skip. No channel, no gid, or
     * any ship error → I do NOT run: a write loop needs the ship anyway, so
     * a device that can't coordinate can't safely fire.
     */
    override fun canCoordinate(): Boolean = channel != null

    override suspend fun claim(loop: io.nisfeb.talon.data.LoopEntity): Boolean {
        if (channel == null) return false
        if (loop.gid.isBlank()) return true // unsynced loop: single-device, nothing to race
        if (aiSettings.state.value.deviceId.isBlank()) return true // no id (not a real platform store) — don't block
        // Stale after ~2 missed fires, clamped so sub-hour loops still fail
        // over reasonably and long loops don't pin a dead holder for days.
        return claimKey(loop.gid, (loop.intervalMinutes.toLong() * 2).coerceIn(30, 720) * 60_000L, CLAIM_SETTLE_MS)
    }

    override suspend fun claimKey(key: String, staleMs: Long, settleMs: Long): Boolean {
        val ch = channel ?: return false
        val me = aiSettings.state.value.deviceId
        if (me.isBlank()) return false
        val now = nowMs()
        return when (io.nisfeb.talon.ai.decideClaim(readClaim(ch, key), me, now, staleMs)) {
            io.nisfeb.talon.ai.ClaimDecision.RUN -> { writeClaim(key, me, now); true }
            io.nisfeb.talon.ai.ClaimDecision.SKIP -> false
            io.nisfeb.talon.ai.ClaimDecision.CONTEST -> {
                // Stake a claim, let concurrent claimants settle on the ship,
                // then run only if I'm still the holder.
                writeClaim(key, me, now)
                kotlinx.coroutines.delay(settleMs)
                readClaim(ch, key)?.first == me
            }
        }
    }

    /** Read the lease for [gid] → (holder deviceId, claimedAt ms), or null
     *  if unclaimed / unreadable. */
    private suspend fun readClaim(ch: UrbitChannel, gid: String): Pair<String, Long>? {
        val body = runCatching { ch.scry("settings", "/desk/$DESK") }.getOrNull() as? JsonObject
        val deskMap = (body?.get("desk") as? JsonObject) ?: body
        val claims = deskMap?.get(BUCKET_AUTOMATION_CLAIMS) as? JsonObject ?: return null
        val obj = unwrap(claims[gid]) as? JsonObject ?: return null
        val holder = obj["holder"].asStr()?.takeIf { it.isNotBlank() } ?: return null
        return holder to (obj["claimedAt"].asLong() ?: 0L)
    }

    private suspend fun writeClaim(gid: String, holder: String, at: Long) {
        pokePutEntry(
            BUCKET_AUTOMATION_CLAIMS, gid,
            buildJsonObject {
                put("holder", holder)
                put("claimedAt", at)
            },
        )
    }

    private fun applyAiEntry(obj: JsonObject) {
        val current = aiSettings.state.value
        fun bool(key: String, default: Boolean) =
            obj[key].asText()?.toBooleanStrictOrNull() ?: default

        // Schema version gate. Entries written by rc33+ carry
        // `schemaVersion: 2`; everything before that is legacy data
        // that may be stale (the rc8-era bug had Android's
        // `aiSettings.onStateChange` unwired, so feature-toggle
        // pushes silently dropped, and the bucket-empty recovery
        // path then seeded defaults — which were FALSE for the four
        // on-device features pre-rc30 — to the ship). Every fresh
        // install since has been pulling those stale defaults back
        // down and overwriting the rc30 default-true local state.
        // For legacy entries we ignore feature toggles entirely
        // (local defaults win) and trust only the explicit
        // schemaVersion=2 writes a user has made on a post-rc33
        // device. Cloud-key fields stay on the same wire-or-local
        // path because they're already gated on local syncEnabled.
        val schemaVersion = obj["schemaVersion"].asInt() ?: 0
        // Redact the service credentials before logging — the toggle values
        // are the useful part of this debug line; apiKey/braveApiKey are
        // secrets and the log sink (unlike the EncryptedSharedPreferences
        // store) isn't encrypted.
        val redacted = JsonObject(
            obj.mapValues { (k, v) ->
                if (k == "apiKey" || k == "braveApiKey" || k == "sttApiKey" || k == "privateApiKey" || k == "providerKeys") JsonPrimitive("***") else v
            },
        )
        Log.i(TAG, "applyAiEntry schemaVersion=$schemaVersion obj=$redacted")

        val features = if (schemaVersion >= AI_SCHEMA_V2) {
            current.copy(
                catchMeUpEnabled = bool("catchMeUpEnabled", current.catchMeUpEnabled),
                smartFeaturesEnabled = bool("smartFeaturesEnabled", current.smartFeaturesEnabled),
                askUrbitEnabled = bool("askUrbitEnabled", current.askUrbitEnabled),
                agentEnabled = bool("agentEnabled", current.agentEnabled),
                // Absent → keep local; present (even blank) → adopt, so a
                // peer's reset-to-default ("") propagates too.
                urbitKnowledgePrompt = obj["urbitKnowledgePrompt"].asStr() ?: current.urbitKnowledgePrompt,
                assistantPrompt = obj["assistantPrompt"].asStr() ?: current.assistantPrompt,
                loopPrompt = obj["loopPrompt"].asStr() ?: current.loopPrompt,
            )
        } else {
            Log.i(TAG, "applyAiEntry ignoring legacy feature toggles; keeping local defaults")
            current
        }

        // Cloud-key fields only apply when local sync is opted in —
        // otherwise we respect the device's local key (or no-key).
        // syncEnabled itself stays at the local value so the user's
        // explicit consent on this device is the gate, not whatever
        // a peer device wrote into the bucket.
        // The transcription key: a real one wins; an absent one keeps ours;
        // a removal stamp newer than our own last removal clears ours.
        val remoteRemovedAt = obj["sttApiKeyRemovedAtMs"].asLong() ?: 0L
        // Keys taken out on any device. The store keeps the marks of
        // both sides and takes every marked key out of what it keeps,
        // whichever side it came from (keepingCredentials).
        val marks = (obj["revokedKeys"] as? JsonObject)
            ?.let { runCatching { Json.decodeFromJsonElement(REVOKED, it) }.getOrNull() }.orEmpty()
        val remoteStt: String? = obj["sttApiKey"].asStr()?.takeIf { it.isNotBlank() }
            ?: if (remoteRemovedAt > current.sttApiKeyRemovedAtMs) "" else null
        val merged = if (current.syncEnabled) {
            val providerStr = obj["provider"].asStr()
            val provider = providerStr?.let {
                runCatching { AiSettings.Provider.valueOf(it) }.getOrNull()
            }
            // A remote entry that carries no key of its own says
            // nothing about which provider to use or what to call the
            // model: an absent field used to read as "set it to null",
            // so one launch of a key-less profile build turned a working
            // OpenRouter setup into Anthropic with no model name and a
            // key that then answered 401.
            val carries = obj["apiKey"].asStr()?.isNotBlank() == true ||
                obj["braveApiKey"].asStr()?.isNotBlank() == true ||
                obj["sttApiKey"].asStr() != null || remoteRemovedAt > 0L
            if (provider != null && carries) {
                features.copy(
                    provider = provider,
                    // Only overwrite the key when the entry actually
                    // carries one — a peer push with syncEnabled=false
                    // omits apiKey, and orEmpty() would blank a good
                    // local key (data loss → silently disables AI).
                    // Treat present-but-empty ("") as absent too: asStr()
                    // returns "" (non-null) for an empty string, so the
                    // ?: guard alone wouldn't catch a ship entry that was
                    // seeded with apiKey:"" by an older client.
                    apiKey = obj["apiKey"].asStr()?.takeIf { it.isNotBlank() } ?: current.apiKey,
                    // Absent keeps what this device has, the way the
                    // keys do. Only a blank string is a real erasure.
                    model = obj["model"].asStr() ?: current.model,
                    baseUrl = obj["baseUrl"].asStr() ?: current.baseUrl,
                    // Same "only overwrite when present and non-empty" guard.
                    braveApiKey = obj["braveApiKey"].asStr()?.takeIf { it.isNotBlank() } ?: current.braveApiKey,
                    // The private model, on the same absent-keeps-local terms.
                    privateBaseUrl = obj["privateBaseUrl"].asStr() ?: current.privateBaseUrl,
                    privateModel = obj["privateModel"].asStr() ?: current.privateModel,
                    privateApiKey = obj["privateApiKey"].asStr()?.takeIf { it.isNotBlank() } ?: current.privateApiKey,
                    // Unlike apiKey, present-but-empty here means the user
                    // cleared the key on a peer — nothing ever seeded
                    // sttApiKey:"" — so adopt "" and let the removal
                    // propagate. Absent still means a syncEnabled=false push
                    // and keeps the local key.
                    sttApiKey = remoteStt ?: current.sttApiKey,
                    sttApiKeyRemovedAtMs = if (remoteStt == "") remoteRemovedAt else if (remoteStt != null) 0L else current.sttApiKeyRemovedAtMs,
                )
            } else features
        } else features

        // Which model reads your messages rides the credentials: the
        // model it names is only here on a device that syncs them, and a
        // device that keeps its messages off the cloud is not told
        // otherwise by a peer.
        val gated =
            if (current.syncEnabled) merged.copy(frontierReadsMessages = bool("frontierReadsMessages", merged.frontierReadsMessages), revokedKeys = marks)
            else merged

        // The profile: a new install's arrives whole, an old install's
        // write changes what its fields describe, keys come with the
        // credentials. applyRemote's keepingCredentials holds the rest.
        val withProfile = gated.copy(savedProfile = io.nisfeb.talon.ai.profileAfterEntry(obj, current, gated))
        aiSettings.applyRemote(withProfile)
        // A peer that had not heard of a removal yet wrote its
        // credentials over the ones that carried it. Said again, once:
        // the peer takes the marks from this push, and this device's
        // next apply of its own entry finds nothing missing. Off the
        // event stream, which two pokes waiting on acks held up, and
        // one at a time, since an older build's every write lacks them.
        val kept = aiSettings.state.value.revokedKeys
        if (current.syncEnabled && obj.containsKey("provider") && io.nisfeb.talon.ai.mergedMarks(marks, kept) != marks) pushMarks()
        // applyRemote deliberately bypasses onStateChange (anti-pingpong),
        // which is also the only rearm-on-key-change hook — so a key or
        // feature toggle arriving via sync must re-arm the loop scheduler
        // here, or a device whose alarm was disarmed for lack of a key
        // stays disarmed until restart even though loops can now run.
        rearmLoops()
    }

    /**
     * The AI settings pushed again for their revoked-key marks, off the
     * event stream and one at a time. Owed until there is a scope: an
     * entry applied at bootstrap, before the shell attached, lost it.
     */
    private fun pushMarks() {
        if (marksPush?.isActive == true) return
        val scope = pushScope ?: run { marksOwed = true; return }
        marksOwed = false
        marksPush = scope.launch {
            try {
                pushAiSettings()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "revoked keys push failed", e)
            }
        }
    }

    override suspend fun addBookmark(whom: String, postId: String, ts: Long) {
        db.bookmarks().upsert(BookmarkEntity(whom, postId, ts))
        pokePutEntry(
            BUCKET_BOOKMARKS, bookmarkKey(whom, postId),
            buildJsonObject { put("ts", ts) },
        )
    }

    override suspend fun removeBookmark(whom: String, postId: String) {
        db.bookmarks().remove(whom, postId)
        pokeDelEntry(BUCKET_BOOKMARKS, bookmarkKey(whom, postId))
    }

    // ───────── bookmark folder CRUD ───────────────────────────

    /** Create a new bookmark folder; returns its newly assigned id. */
    override suspend fun createBookmarkFolder(name: String, sortOrder: Int): Long {
        val id = db.bookmarkFolders().createFolder(
            BookmarkFolderEntity(name = name, sortOrder = sortOrder),
        )
        pokePutEntry(
            BUCKET_BOOKMARK_FOLDERS, id.toString(),
            buildJsonObject {
                put("name", name)
                put("sortOrder", sortOrder)
            },
        )
        return id
    }

    override suspend fun renameBookmarkFolder(id: Long, name: String) {
        val current = db.bookmarkFolders().get(id) ?: return
        db.bookmarkFolders().rename(id, name)
        pokePutEntry(
            BUCKET_BOOKMARK_FOLDERS, id.toString(),
            buildJsonObject {
                put("name", name)
                put("sortOrder", current.sortOrder)
            },
        )
    }

    override suspend fun deleteBookmarkFolder(id: Long) {
        db.bookmarkFolders().deleteMembersOf(id)
        db.bookmarkFolders().delete(id)
        pokeDelEntry(BUCKET_BOOKMARK_FOLDERS, id.toString())
    }

    override suspend fun addBookmarkToFolder(folderId: Long, whom: String, postId: String) {
        db.bookmarkFolders().addMember(folderId, whom, postId)
        // The DAO transaction picks the next ordinal; mirror that to
        // the ship by re-reading.
        val ordinal = db.bookmarkFolders().maxOrdinalIn(folderId)
        pokePutEntry(
            BUCKET_BOOKMARK_FOLDER_MEMBERS,
            bookmarkFolderMemberKey(folderId, whom, postId),
            buildJsonObject { put("ordinal", ordinal) },
        )
    }

    override suspend fun removeBookmarkFromFolder(folderId: Long, whom: String, postId: String) {
        db.bookmarkFolders().removeMember(folderId, whom, postId)
        pokeDelEntry(
            BUCKET_BOOKMARK_FOLDER_MEMBERS,
            bookmarkFolderMemberKey(folderId, whom, postId),
        )
    }

    /** Local-only: rewrite a folder's ordering. Caller pushes the
     *  resulting ordinals to %settings via [pushBookmarkFolderOrder]
     *  on drag-stop, mirroring the conversation-folder pattern. */
    suspend fun reorderBookmarkFolderMembersLocal(
        folderId: Long,
        items: List<Pair<String, String>>,
    ) {
        db.bookmarkFolders().reorderMembers(folderId, items)
    }

    suspend fun pushBookmarkFolderOrder(folderId: Long) {
        val members = db.bookmarkFolders().streamMembers().first()
            .filter { it.folderId == folderId }
        members.forEach { m ->
            pokePutEntry(
                BUCKET_BOOKMARK_FOLDER_MEMBERS,
                bookmarkFolderMemberKey(folderId, m.whom, m.postId),
                buildJsonObject { put("ordinal", m.ordinal) },
            )
        }
    }

    override suspend fun setNotifyLevel(whom: String, level: String) {
        db.notifyPrefs().upsert(NotifyPreferenceEntity(whom, level))
        pokePutEntry(
            BUCKET_NOTIFY_PREFS, whom,
            buildJsonObject { put("level", level) },
        )
    }

    override suspend fun setRailItemVisibility(item: RailItem, visible: Boolean) {
        if (visible) {
            // Default-visible items are absent from the table + bucket;
            // deleting restores the default.
            db.railItemPrefs().delete(item.name)
            pokeDelEntry(BUCKET_RAIL_ITEMS, item.name)
        } else {
            db.railItemPrefs().upsert(RailItemPrefEntity(item.name, visible = false))
            pokePutEntry(
                BUCKET_RAIL_ITEMS, item.name,
                buildJsonObject { put("visible", false) },
            )
        }
    }

    override suspend fun mirrorWatchword(change: io.nisfeb.talon.ai.WatchwordChange) {
        when (change) {
            is io.nisfeb.talon.ai.WatchwordChange.Upsert -> pushWatchwordEntry(change.term)
            is io.nisfeb.talon.ai.WatchwordChange.Remove -> deleteWatchwordEntry(change.termText)
            is io.nisfeb.talon.ai.WatchwordChange.Exclude -> pushWatchwordExclude(change.whom)
            is io.nisfeb.talon.ai.WatchwordChange.Unexclude -> deleteWatchwordExclude(change.whom)
            is io.nisfeb.talon.ai.WatchwordChange.SyncToggled ->
                if (change.on) pushAllWatchwords() else clearWatchwordsOnShip()
        }
    }

    /** Mirror one watchword term to the ship's settings. */
    suspend fun pushWatchwordEntry(term: io.nisfeb.talon.data.WatchwordEntity) {
        val key = io.nisfeb.talon.ai.sanitizeTerm(term.term)
        if (key.isEmpty()) return
        // Route through pokePutEntry so the value is stringified to a
        // cord like every other bucket — %settings's mark dejs expects
        // the cord shape; bypassing it caused cross-device sync to
        // silently no-op on stricter dejs builds.
        pokePutEntry(
            BUCKET_WATCHWORDS, key,
            buildJsonObject {
                put("term", term.term)
                put("notify", term.notify)
                put("createdMs", term.createdMs)
            },
        )
    }

    suspend fun deleteWatchwordEntry(termText: String) {
        val key = io.nisfeb.talon.ai.sanitizeTerm(termText)
        if (key.isEmpty()) return
        pokeDelEntry(BUCKET_WATCHWORDS, key)
    }

    suspend fun pushWatchwordExclude(whom: String) {
        // Cord-stringified value, matching every other bucket. The
        // value itself is unused on the apply side (presence-of-key is
        // the signal); we send `true` for parity.
        pokePutEntry(BUCKET_WATCHWORD_EXCLUDES, whom, JsonPrimitive(true))
    }

    suspend fun deleteWatchwordExclude(whom: String) {
        pokeDelEntry(BUCKET_WATCHWORD_EXCLUDES, whom)
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



    // ───────── inbound appliers ─────────

    /**
     * %settings stores each entry's value as a cord; Tlon wraps
     * structured values as JSON strings. Un-stringify so downstream
     * handlers can use the normal `asJsonObject` path. Falls through
     * for non-string values in case some ship version still serves
     * raw JSON.
     */
    private fun unwrap(v: JsonElement?): JsonElement? {
        if (v == null) return null
        if (v is JsonPrimitive && v.isString) {
            return runCatching { Json.parseToJsonElement(v.content) }.getOrNull()
                ?: v
        }
        return v
    }

    // `internal` so the desktopTest source set can drive applyBucket
    // directly without the full bootstrap+UrbitChannel scaffolding.
    // Kept in lockstep with production app/'s SettingsSync.kt — when
    // that copy is retired in Stage F the visibility can stay internal.
    /**
     * A bucket whose entries are rows in a table.
     *
     * Most of them are: an entry is one row, keyed by its own id, and
     * sync does four things with it. Written out longhand that was a
     * branch in each of four `when`s, so a new bucket meant four edits
     * and a forgotten one meant a bucket that applied but never
     * cleared. Here each bucket says how an entry reads and what to do
     * with it, once.
     */
    private class Rows<T>(
        val decode: (key: String, obj: JsonObject) -> T?,
        val replaceAll: suspend (List<T>) -> Unit,
        val upsert: suspend (T) -> Unit,
        val remove: suspend (key: String) -> Unit,
        /** True when an entry means there should be no local row at all. */
        val drop: (JsonObject) -> Boolean = { false },
    ) {
        /** The whole bucket, which is the ship's word: anything not in it goes. */
        suspend fun applyAll(entries: Map<String, JsonObject>) =
            replaceAll(entries.mapNotNull { (key, obj) -> if (drop(obj)) null else decode(key, obj) })

        suspend fun applyOne(key: String, obj: JsonObject) {
            if (drop(obj)) remove(key) else decode(key, obj)?.let { upsert(it) }
        }

        suspend fun clear() = replaceAll(emptyList())
    }

    /** Every bucket that is just rows, and how each one reads. */
    private val rowBuckets: Map<String, Rows<*>> by lazy {
        mapOf(
            BUCKET_GROUP_ORDERS to Rows(
                decode = { key, obj -> obj["ordinal"].asInt()?.let { GroupOrderEntity(flag = key, ordinal = it) } },
                replaceAll = { db.groupOrders().replaceAll(it) },
                upsert = { db.groupOrders().upsertRaw(it.flag, it.ordinal) },
                remove = { db.groupOrders().remove(it) },
            ),
            BUCKET_FOLDERS to Rows(
                decode = { key, obj ->
                    val id = key.toLongOrNull()
                    val name = obj["name"].asStr()
                    if (id == null || name == null) null
                    else FolderEntity(id = id, name = name, sortOrder = obj["sortOrder"].asInt() ?: 0)
                },
                replaceAll = { db.folders().replaceAll(it) },
                upsert = { db.folders().upsert(it) },
                remove = { key ->
                    key.toLongOrNull()?.let { id ->
                        db.folders().deleteMembersOf(id)
                        db.folders().delete(id)
                    }
                },
            ),
            BUCKET_FOLDER_MEMBERS to Rows(
                decode = { key, obj ->
                    parseFolderMemberKey(key)?.let { (folderId, whom) ->
                        FolderMemberEntity(
                            folderId = folderId,
                            whom = whom,
                            ordinal = obj["ordinal"].asInt() ?: 0,
                            kind = obj["kind"].asStr() ?: FolderMemberEntity.KIND_WHOM,
                        )
                    }
                },
                replaceAll = { db.folders().replaceAllMembers(it) },
                upsert = { db.folders().addMemberRaw(it.folderId, it.whom, it.ordinal, it.kind) },
                remove = { key ->
                    parseFolderMemberKey(key)?.let { (folderId, whom) -> db.folders().removeMember(folderId, whom) }
                },
            ),
            BUCKET_NOTIFY_PREFS to Rows(
                decode = { key, obj -> obj["level"].asStr()?.let { NotifyPreferenceEntity(whom = key, level = it) } },
                replaceAll = { db.notifyPrefs().replaceAll(it) },
                upsert = { db.notifyPrefs().upsert(it) },
                remove = { db.notifyPrefs().clear(it) },
            ),
            BUCKET_RAIL_ITEMS to Rows(
                // Absence is the default, so a visible item is no row at
                // all: an explicit `true` would drift the read site.
                decode = { key, obj ->
                    if (obj["visible"].asBool() == null) null
                    else railItemOrNull(key)?.let { RailItemPrefEntity(it.name, visible = false) }
                },
                replaceAll = { db.railItemPrefs().replaceAll(it) },
                upsert = { db.railItemPrefs().upsert(it) },
                remove = { db.railItemPrefs().delete(it) },
                drop = { it["visible"].asBool() == true },
            ),
            BUCKET_BOOKMARKS to Rows(
                decode = { key, obj ->
                    parseBookmarkKey(key)?.let { (whom, postId) ->
                        BookmarkEntity(whom = whom, postId = postId, bookmarkedMs = obj["ts"].asLong() ?: 0L)
                    }
                },
                replaceAll = { db.bookmarks().replaceAll(it) },
                upsert = { db.bookmarks().upsert(it) },
                remove = { key -> parseBookmarkKey(key)?.let { (whom, postId) -> db.bookmarks().remove(whom, postId) } },
            ),
            BUCKET_BOOKMARK_FOLDERS to Rows(
                decode = { key, obj ->
                    val id = key.toLongOrNull()
                    val name = obj["name"].asStr()
                    if (id == null || name == null) null
                    else BookmarkFolderEntity(id = id, name = name, sortOrder = obj["sortOrder"].asInt() ?: 0)
                },
                replaceAll = { db.bookmarkFolders().replaceAll(it) },
                upsert = { db.bookmarkFolders().upsert(it) },
                remove = { key ->
                    key.toLongOrNull()?.let { id ->
                        db.bookmarkFolders().deleteMembersOf(id)
                        db.bookmarkFolders().delete(id)
                    }
                },
            ),
            BUCKET_BOOKMARK_FOLDER_MEMBERS to Rows(
                decode = { key, obj ->
                    parseBookmarkFolderMemberKey(key)?.let { (folderId, whom, postId) ->
                        BookmarkFolderMemberEntity(
                            folderId = folderId,
                            whom = whom,
                            postId = postId,
                            ordinal = obj["ordinal"].asInt() ?: 0,
                        )
                    }
                },
                replaceAll = { db.bookmarkFolders().replaceAllMembers(it) },
                upsert = { db.bookmarkFolders().addMemberRaw(it.folderId, it.whom, it.postId, it.ordinal) },
                remove = { key ->
                    parseBookmarkFolderMemberKey(key)?.let { (folderId, whom, postId) ->
                        db.bookmarkFolders().removeMember(folderId, whom, postId)
                    }
                },
            ),
        )
    }

    /** One bucket's entries, unwrapped and kept only where they are objects. */
    private fun objects(entries: JsonObject?): Map<String, JsonObject> =
        entries.orEmpty().mapNotNull { (k, v) -> (unwrap(v) as? JsonObject)?.let { k to it } }.toMap()

    internal suspend fun applyBucket(bucket: String, entries: JsonObject?) {
        // Replace-on-apply: any local row not in the incoming bucket
        // will be wiped. For bucket reorders this is the right call.
        rowBuckets[bucket]?.let { return it.applyAll(objects(entries)) }
        when (bucket) {
            BUCKET_AI_SETTINGS -> {
                // Preferences first, then the credentials, which are an
                // entry of their own so that saving one cannot erase the
                // other. An older ship has only the first.
                (unwrap(entries?.get(AI_ENTRY)) as? JsonObject)?.let { applyAiEntry(it) }
                (unwrap(entries?.get(AI_KEYS_ENTRY)) as? JsonObject)?.let { applyAiEntry(it) }
            }
            BUCKET_WATCHWORDS -> {
                // Apply each entry; we don't have a "deleteAllTerms" since
                // the local watchwords table also feeds the live runtime.
                // Per-entry upsert + drop-if-not-in-bucket.
                val incoming = entries?.entries?.mapNotNull { (key, value) ->
                    // Values arrive as cord-stringified JsonObject (see
                    // pokePutEntry stringification). unwrap parses the
                    // cord back into the inner JsonObject.
                    val obj = unwrap(value) as? JsonObject ?: return@mapNotNull null
                    val termText = obj["term"].asStr() ?: return@mapNotNull null
                    val notify = (obj["notify"] as? JsonPrimitive)?.booleanOrNull ?: true
                    val createdMs = (obj["createdMs"] as? JsonPrimitive)?.longOrNull
                        ?: nowMs()
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
            BUCKET_STATUS_SEEN -> {
                val v = entries?.get(STATUS_SEEN_ENTRY) ?: return
                val ms = (unwrap(v) as? JsonObject)?.get("ms").asLong() ?: return
                bumpStatusesSeen(ms)
            }
            BUCKET_UI_PREFS -> {
                entries?.get(ENTRY_ALWAYS_PATP)?.let { v ->
                    (unwrap(v) as? JsonObject)?.get("enabled").asBool()?.let {
                        io.nisfeb.talon.ui.ShipNames.setAlwaysPatp(it)
                    }
                }
                entries?.get(ENTRY_NON_COMET_NAMES)?.let { v ->
                    (unwrap(v) as? JsonObject)?.get("enabled").asBool()?.let {
                        io.nisfeb.talon.ui.AzimuthNames.setEnabled(it)
                    }
                }
                if (ui == null) {
                    pendingUiPrefs = entries
                } else {
                    entries?.forEach { (key, v) ->
                        if (key !in HANDLED_UI_PREFS) {
                            applyUiPref(key, unwrap(v))
                        }
                    }
                }
            }
            BUCKET_ASSISTANT_CONVERSATIONS -> {
                entries?.forEach { (gid, v) ->
                    (unwrap(v) as? JsonObject)?.let { upsertAssistantConversation(gid, it) }
                }
            }
            BUCKET_ASSISTANT_TURNS -> {
                // The ship's bucket is an append-only superset of what we
                // keep locally, and bootstrap replays it on EVERY connect.
                // Only the newest KEEP turns can survive the trim below, so
                // skip everything below the horizon up front — or a
                // long-lived ship pays O(bucket) insert+count+delete cycles
                // per reconnect (and a fresh device pays it on first sync)
                // for zero net change. The horizon is the stricter of:
                //  - the oldest LOCAL turn once we're at cap (locals are
                //    never deleted mid-apply, so anything older loses);
                //  - the KEEP-th newest createdAt among the bucket entries
                //    themselves (covers the fresh-device bootstrap, where
                //    there is no local horizon yet).
                // Strict '<' — ties at the boundary must not be skipped.
                val turnDao = db.assistantHistory()
                val keep = io.nisfeb.talon.data.ASSISTANT_HISTORY_KEEP
                val localHorizon = if (turnDao.count() >= keep) {
                    turnDao.oldestCreatedAt() ?: 0L
                } else 0L
                val bucketHorizon = entries?.values
                    ?.mapNotNull { (unwrap(it) as? JsonObject)?.get("createdAt").asLong() }
                    ?.sortedDescending()?.getOrNull(keep - 1) ?: 0L
                val horizon = maxOf(localHorizon, bucketHorizon)
                val touched = mutableSetOf<Long>()
                entries?.forEach { (gid, v) ->
                    val obj = unwrap(v) as? JsonObject ?: return@forEach
                    if ((obj["createdAt"].asLong() ?: 0L) < horizon) return@forEach
                    upsertAssistantTurn(gid, obj)?.let(touched::add)
                }
                // Re-apply the caps or they're a no-op after the first
                // sync and the tables grow to ship size forever. Recount
                // AFTER the trims so turnCount reflects what survived —
                // once per touched conversation, not once per insert.
                turnDao.trim(keep)
                db.assistantConversations().trim(io.nisfeb.talon.data.ASSISTANT_CONV_KEEP)
                touched.forEach { recountConversation(it) }
            }
            BUCKET_LOOPS -> {
                entries?.forEach { (gid, v) ->
                    (unwrap(v) as? JsonObject)?.let { upsertLoop(gid, it) }
                }
            }
        }
    }

    internal suspend fun applyEntry(bucket: String, entry: String, value: JsonElement) {
        val unwrapped = unwrap(value)
        val obj = unwrapped as? JsonObject
        rowBuckets[bucket]?.let { rows ->
            if (obj != null) rows.applyOne(entry, obj)
            return
        }
        when (bucket) {
            BUCKET_AI_SETTINGS -> {
                if (entry == AI_ENTRY || entry == AI_KEYS_ENTRY) {
                    (unwrapped as? JsonObject)?.let { applyAiEntry(it) }
                }
            }
            BUCKET_WATCHWORDS -> {
                val obj = unwrapped as? JsonObject ?: return
                val termText = obj["term"].asStr() ?: return
                val notify = (obj["notify"] as? JsonPrimitive)?.booleanOrNull ?: true
                val createdMs = (obj["createdMs"] as? JsonPrimitive)?.longOrNull
                    ?: nowMs()
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
                // Honor the value: explicit `false` means "not excluded"
                // and shouldn't create the row. Treat any other shape
                // (true, missing, non-boolean) as the legacy "presence
                // == excluded" semantics. Removal still routes through
                // del-entry, but defending against a stale `false`
                // writer keeps the row from being recreated.
                val v = (unwrapped as? JsonPrimitive)?.booleanOrNull
                if (v == false) return
                db.watchwords().upsertExclude(
                    io.nisfeb.talon.data.WatchwordChatExcludeEntity(entry)
                )
            }
            BUCKET_STATUS_SEEN -> {
                val ms = (unwrapped as? JsonObject)?.get("ms").asLong() ?: return
                bumpStatusesSeen(ms)
            }
            BUCKET_UI_PREFS -> {
                when (entry) {
                    ENTRY_ALWAYS_PATP ->
                        (unwrapped as? JsonObject)?.get("enabled").asBool()
                            ?.let { io.nisfeb.talon.ui.ShipNames.setAlwaysPatp(it) }
                    ENTRY_NON_COMET_NAMES ->
                        (unwrapped as? JsonObject)?.get("enabled").asBool()
                            ?.let { io.nisfeb.talon.ui.AzimuthNames.setEnabled(it) }
                    // Retired; a peer still sending it is ignored.
                    ENTRY_MNEMONYM_NAMES -> Unit
                    else -> applyUiPref(entry, unwrapped)
                }
            }
            // Live put-entry facts must enforce the same caps applyBucket
            // does, or a device that stays connected grows the tables one
            // row per peer event until the next reconnect's bucket replay.
            BUCKET_ASSISTANT_CONVERSATIONS -> {
                (unwrapped as? JsonObject)?.let { upsertAssistantConversation(entry, it) }
                db.assistantConversations().trim(io.nisfeb.talon.data.ASSISTANT_CONV_KEEP)
            }
            BUCKET_ASSISTANT_TURNS -> {
                val convId = (unwrapped as? JsonObject)?.let { upsertAssistantTurn(entry, it) }
                db.assistantHistory().trim(io.nisfeb.talon.data.ASSISTANT_HISTORY_KEEP)
                convId?.let { recountConversation(it) }
            }
            BUCKET_LOOPS -> {
                (unwrapped as? JsonObject)?.let { upsertLoop(entry, it) }
            }
        }
    }

    internal suspend fun removeEntry(bucket: String, entry: String) {
        rowBuckets[bucket]?.let { return it.remove(entry) }
        when (bucket) {
            BUCKET_UI_PREFS -> {
                // Entry deleted on the ship → back to that entry's default.
                when (entry) {
                    ENTRY_ALWAYS_PATP -> io.nisfeb.talon.ui.ShipNames.setAlwaysPatp(false)
                    ENTRY_NON_COMET_NAMES -> io.nisfeb.talon.ui.AzimuthNames.setEnabled(false)
                }
            }
            BUCKET_AI_SETTINGS -> {
                // Only the credentials entry going means the credentials
                // went, and only the credentials go with it. Anything
                // else vanishing is not a reason to wipe a device's
                // provider, its prompts and its toggles as well.
                val cfg = aiSettings.state.value
                if (entry == AI_KEYS_ENTRY && cfg.syncEnabled) {
                    aiSettings.applyRemote(
                        cfg.copy(apiKey = "", braveApiKey = "", sttApiKey = "", sttApiKeyRemovedAtMs = 0L),
                    )
                }
            }
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
            BUCKET_ASSISTANT_CONVERSATIONS -> {
                // Deleting a conversation cascades to its turns locally.
                db.assistantConversations().getByGid(entry)?.let {
                    db.assistantHistory().deleteForConversation(it.id)
                }
                db.assistantConversations().deleteByGid(entry)
            }
            BUCKET_ASSISTANT_TURNS -> db.assistantHistory().deleteByGid(entry)
            BUCKET_LOOPS -> db.loops().getByGid(entry)?.let {
                db.loops().delete(it.id)
                db.loopRuns().deleteForLoop(it.id)
                // Re-arm like upsertLoop/clearBucketLocally do — otherwise a
                // stale alarm for the deleted loop stays armed (or, if it was
                // the only loop, an alarm that should be cancelled survives).
                rearmLoops()
            }
        }
    }

    // ───────── assistant history upsert (sync apply) ─────────
    // Upsert, not replace — a peer's bucket is merged into local state so
    // conversations created offline here survive.

    private suspend fun upsertAssistantConversation(gid: String, obj: JsonObject) {
        if (gid.isBlank()) return
        val dao = db.assistantConversations()
        val title = obj["title"].asStr() ?: ""
        val createdAt = obj["createdAt"].asLong() ?: 0L
        val updatedAt = obj["updatedAt"].asLong() ?: createdAt
        val existing = dao.getByGid(gid)
        if (existing != null) {
            // Preserve centroid/dim (device-local, not on the wire) AND
            // turnCount (derived from the turns THIS device actually holds,
            // maintained in upsertAssistantTurn) — the wire's turnCount
            // counts a peer's turns that may not have synced here yet, so
            // trusting it would claim more turns than exist locally.
            dao.update(existing.copy(title = title, updatedAt = updatedAt))
        } else {
            // turnCount starts at 0; each synced turn bumps it so it always
            // matches the local row count, never the peer's claim.
            dao.insert(
                io.nisfeb.talon.data.AssistantConversationEntity(
                    gid = gid, title = title, createdAt = createdAt,
                    updatedAt = updatedAt, centroid = ByteArray(0), dim = 0, turnCount = 0,
                ),
            )
        }
    }

    private suspend fun upsertLoop(gid: String, obj: JsonObject) {
        if (gid.isBlank()) return
        val dao = db.loops()
        val existing = dao.getByGid(gid)
        // Preserve the local id (so @Upsert updates rather than inserts a
        // dup) and lastRunAt (device-local schedule state, never on the
        // wire). Everything else comes from the synced definition — EXCEPT
        // writesAuthorized: that grant gives a loop unattended write access
        // to the ship, so it's a per-device decision the local user must
        // make here. We never honor it from the wire (a peer or anyone with
        // ship access could otherwise flip a read-only loop into one that
        // posts unattended). A freshly-synced loop arrives read-only; an
        // existing one keeps whatever this device granted. Mirrors how AI
        // cloud-key fields gate on local syncEnabled rather than crossing
        // over from a peer.
        dao.upsert(
            io.nisfeb.talon.data.LoopEntity(
                id = existing?.id ?: 0L,
                gid = gid,
                name = obj["name"].asStr() ?: "",
                prompt = obj["prompt"].asStr() ?: "",
                intervalMinutes = obj["intervalMinutes"].asInt() ?: 60,
                enabled = (obj["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true,
                writesAuthorized = existing?.writesAuthorized ?: false,
                createdAt = obj["createdAt"].asLong() ?: 0L,
                updatedAt = obj["updatedAt"].asLong() ?: 0L,
                // First arrival on this device: stamp lastRunAt=now so the
                // first run is one interval out — matching the local create
                // path. Inheriting 0 would make the loop due since 1970 and
                // fire within a minute on EVERY peer device the definition
                // syncs to (read-only loops don't take the write lease, so
                // nothing would dedupe those surprise runs).
                lastRunAt = existing?.lastRunAt ?: nowMs(),
                // Schedule shape travels with the definition; absent fields
                // (a legacy peer) default to the interval behavior.
                scheduleKind = obj["scheduleKind"].asStr() ?: "interval",
                atMinuteOfDay = obj["atMinuteOfDay"].asInt() ?: 0,
                daysMask = obj["daysMask"].asInt() ?: 0,
            ),
        )
        rearmLoops()
    }

    /** Insert a synced turn; returns the local conversation id when a row
     *  was actually inserted (so the caller can recount turnCount once per
     *  conversation AFTER trimming), or null when the turn was skipped. */
    private suspend fun upsertAssistantTurn(gid: String, obj: JsonObject): Long? {
        if (gid.isBlank()) return null
        val turnDao = db.assistantHistory()
        if (turnDao.getByGid(gid) != null) return null // append-only → idempotent
        val convGid = obj["convGid"].asStr()
        if (convGid == null) {
            Log.w(TAG, "assistant turn $gid missing convGid; dropped")
            return null
        }
        val question = obj["question"].asStr() ?: ""
        val answer = obj["answer"].asStr() ?: ""
        val mode = obj["mode"].asStr() ?: "Assistant"
        val createdAt = obj["createdAt"].asLong() ?: 0L
        val convDao = db.assistantConversations()
        // The turn may arrive before its conversation's metadata — stub a
        // conversation (turnCount=0) so the turn always has a home; the
        // real metadata upserts (preserving this id) when it arrives.
        val conv = convDao.getByGid(convGid)
        val localConvId = conv?.id ?: convDao.insert(
            io.nisfeb.talon.data.AssistantConversationEntity(
                gid = convGid, title = question.take(60).trim(), createdAt = createdAt,
                updatedAt = createdAt, centroid = ByteArray(0), dim = 0, turnCount = 0,
            ),
        )
        turnDao.insert(
            io.nisfeb.talon.data.AssistantHistoryEntity(
                gid = gid, mode = mode, question = question, answer = answer,
                createdAt = createdAt, conversationId = localConvId, convGid = convGid,
            ),
        )
        return localConvId
    }

    /** Set turnCount to the rows this device actually holds. Counting (not
     *  incrementing) keeps re-applied ship supersets from inflating it
     *  without bound and freezing the grouping centroid, which weights new
     *  vectors by 1/turnCount. Run AFTER any trim so the count reflects
     *  what survived. */
    private suspend fun recountConversation(convId: Long) {
        val convDao = db.assistantConversations()
        convDao.get(convId)?.let {
            convDao.update(it.copy(turnCount = db.assistantHistory().countForConversation(convId)))
        }
    }

    internal suspend fun clearBucketLocally(bucket: String) {
        rowBuckets[bucket]?.let { return it.clear() }
        when (bucket) {
            BUCKET_UI_PREFS -> {
                io.nisfeb.talon.ui.ShipNames.setAlwaysPatp(false)
                io.nisfeb.talon.ui.AzimuthNames.setEnabled(false)
            }
            // Only a device switching watchword sync off deletes these
            // buckets: it takes the ship's copy away, not anyone's terms.
            // Mirroring it here erased every other device's terms and
            // excludes the moment one of them opted out.
            BUCKET_WATCHWORDS, BUCKET_WATCHWORD_EXCLUDES -> Unit
            // A peer cleared assistant history (del-bucket) — mirror it
            // locally. Either bucket's del-bucket wipes both tables; turns
            // can't outlive their conversations.
            BUCKET_ASSISTANT_CONVERSATIONS, BUCKET_ASSISTANT_TURNS -> {
                db.assistantHistory().clearAll()
                db.assistantConversations().clearAll()
            }
            // A peer cleared all loops (del-bucket) — wipe definitions and
            // their local run history, then re-arm (the scheduler should
            // disarm when nothing is left).
            BUCKET_LOOPS -> {
                db.loops().clearAll()
                db.loopRuns().clearAll()
                rearmLoops()
            }
        }
    }

    private suspend fun clearFolderMembersForFolder(folderId: Long) {
        db.folders().deleteMembersOf(folderId)
        // No wildcard settings del — best-effort: we'd re-push bucket.
        // Skipped for v1; drift tolerated because the folder itself
        // was deleted so its members are orphaned and filtered out.
    }

    // ───────── poke helpers ─────────

    private suspend fun pokePutEntry(bucket: String, entry: String, value: JsonElement) {
        val ch = channel ?: return
        val payload = buildJsonObject {
            put("put-entry", buildJsonObject {
                put("desk", DESK)
                put("bucket-key", bucket)
                put("entry-key", entry)
                // %settings val is a cord — Tlon serializes complex
                // values via JSON.stringify. Match that so the mark
                // dejs accepts our pokes.
                put("value", JsonPrimitive(Json.encodeToString(JsonElement.serializer(), value)))
            })
        }
        runCatching { ch.poke(app = "settings", mark = "settings-event", payload = payload) }
            .onFailure { Log.w(TAG, "put-entry $bucket/$entry failed", it) }
    }

    private suspend fun pokeDelEntry(bucket: String, entry: String) {
        val ch = channel ?: return
        val payload = buildJsonObject {
            put("del-entry", buildJsonObject {
                put("desk", DESK)
                put("bucket-key", bucket)
                put("entry-key", entry)
            })
        }
        runCatching { ch.poke(app = "settings", mark = "settings-event", payload = payload) }
            .onFailure { Log.w(TAG, "del-entry $bucket/$entry failed", it) }
    }

    private suspend fun pokePutBucket(bucket: String, entries: JsonObject) {
        val ch = channel ?: return
        // Each entry's value must be sent as a JSON string — see
        // pokePutEntry for the reason.
        val stringified = buildJsonObject {
            entries.forEach { (k, v) ->
                put(k, JsonPrimitive(Json.encodeToString(JsonElement.serializer(), v)))
            }
        }
        val payload = buildJsonObject {
            put("put-bucket", buildJsonObject {
                put("desk", DESK)
                put("bucket-key", bucket)
                put("bucket", stringified)
            })
        }
        runCatching { ch.poke(app = "settings", mark = "settings-event", payload = payload) }
            .onFailure { Log.w(TAG, "put-bucket $bucket failed", it) }
    }

    // ───────── helpers ─────────

    private fun folderMemberKey(folderId: Long, whom: String) = "$folderId:$whom"
    private fun parseFolderMemberKey(key: String): Pair<Long, String>? {
        val colon = key.indexOf(':')
        if (colon <= 0) return null
        val id = key.substring(0, colon).toLongOrNull() ?: return null
        val whom = key.substring(colon + 1)
        return id to whom
    }

    // Bookmark keys use `|` as the separator because both whom and
    // postId can contain `:` (e.g. `chat/~host/name` whoms, or club
    // ids with dotted structure).
    private fun bookmarkKey(whom: String, postId: String) = "$whom|$postId"
    private fun parseBookmarkKey(key: String): Pair<String, String>? {
        val pipe = key.indexOf('|')
        if (pipe <= 0 || pipe >= key.length - 1) return null
        return key.substring(0, pipe) to key.substring(pipe + 1)
    }

    // Bookmark-folder-member key: `<folderId>|<whom>|<postId>`. Same
    // `|` separator since none of the components can carry a pipe.
    // Folder id is a positive Long.
    private fun bookmarkFolderMemberKey(folderId: Long, whom: String, postId: String) =
        "$folderId|$whom|$postId"

    private fun JsonObject.desk(): String? =
        this["desk"].asStr()
    private fun JsonObject.bucketKey(): String? =
        this["bucket-key"].asStr()
    private fun JsonObject.entryKey(): String? =
        this["entry-key"].asStr()

    private fun parseBookmarkFolderMemberKey(key: String): Triple<Long, String, String>? =
        io.nisfeb.talon.urbit.parseBookmarkFolderMemberKey(key)
}

/**
 * Parse `<folderId>|<whom>|<postId>` — pure helper exposed at file
 * scope so tests can lock the encoding without instantiating
 * SettingsSync. Returns null when the key isn't well-formed; callers
 * silently drop bad rows on inbound %settings sync rather than
 * crash.
 */
internal fun parseBookmarkFolderMemberKey(key: String): Triple<Long, String, String>? {
    val parts = key.split("|", limit = 3)
    if (parts.size != 3) return null
    val folderId = parts[0].toLongOrNull() ?: return null
    if (parts[1].isEmpty() || parts[2].isEmpty()) return null
    return Triple(folderId, parts[1], parts[2])
}
