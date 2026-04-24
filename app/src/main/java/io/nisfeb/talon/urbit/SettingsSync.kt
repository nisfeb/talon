package io.nisfeb.talon.urbit

import android.util.Log
import io.nisfeb.talon.ai.AiSettings
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.BookmarkEntity
import io.nisfeb.talon.data.FolderEntity
import io.nisfeb.talon.data.FolderMemberEntity
import io.nisfeb.talon.data.GroupOrderEntity
import io.nisfeb.talon.data.NotifyPreferenceEntity
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
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
class SettingsSync(
    private val db: AppDatabase,
    private val aiSettings: AiSettings,
) {

    companion object {
        private const val TAG = "SettingsSync"
        const val DESK = "talon"
        const val BUCKET_GROUP_ORDERS = "group-orders"
        const val BUCKET_FOLDERS = "folders"
        const val BUCKET_FOLDER_MEMBERS = "folder-members"
        const val BUCKET_NOTIFY_PREFS = "notify-prefs"
        const val BUCKET_BOOKMARKS = "bookmarks"
        const val BUCKET_AI_SETTINGS = "ai-settings"
        private const val AI_ENTRY = "config"
    }

    @Volatile private var channel: UrbitChannel? = null

    fun attach(channel: UrbitChannel) {
        this.channel = channel
    }

    /**
     * On session start: scry `%settings /desk/<DESK>` for everything
     * we care about and overwrite local Room tables with whatever the
     * ship holds. Ship wins — local edits made offline without a poke
     * don't survive this. Subscribe afterward so live changes flow in.
     */
    suspend fun bootstrap() {
        val ch = channel ?: return
        val body = runCatching { ch.scry("settings", "/desk/$DESK") }
            .onFailure { Log.w(TAG, "bootstrap scry failed", it) }
            .getOrNull() as? JsonObject

        // Ship returns { "desk": { <bucket>: { <entry>: <value> } } }
        // or the inner desk map directly. Handle both.
        val deskMap = (body?.get("desk") as? JsonObject) ?: body

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
            applyBucket(BUCKET_GROUP_ORDERS, deskMap!![BUCKET_GROUP_ORDERS] as? JsonObject)
            applyBucket(BUCKET_FOLDERS, deskMap[BUCKET_FOLDERS] as? JsonObject)
            applyBucket(BUCKET_FOLDER_MEMBERS, deskMap[BUCKET_FOLDER_MEMBERS] as? JsonObject)
            applyBucket(BUCKET_NOTIFY_PREFS, deskMap[BUCKET_NOTIFY_PREFS] as? JsonObject)
            applyBucket(BUCKET_BOOKMARKS, deskMap[BUCKET_BOOKMARKS] as? JsonObject)
            // AI settings: only applied if the local device has opted
            // into sync — otherwise we respect the device's local key.
            if (aiSettings.state.value.syncEnabled) {
                applyBucket(BUCKET_AI_SETTINGS, deskMap[BUCKET_AI_SETTINGS] as? JsonObject)
            }
        }

        // Subscribe for live updates from other devices.
        runCatching { ch.subscribe("settings", "/desk/$DESK") }
            .onFailure { Log.w(TAG, "subscribe failed", it) }
    }

    /** First-time setup: push whatever's in Room to the ship. */
    private suspend fun seedFromLocal() {
        // Group orders
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
        // Folders
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
        // Folder members
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
        // Notify prefs aren't accessible by bulk stream in current DAO.
        // Future: add a streamAll() if we need to seed them on first run.
        // Bookmarks
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

    /** Apply a %settings SSE fact to the right bucket. */
    suspend fun applySettingsEvent(payload: JsonObject) {
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
    suspend fun reorderGroupOrdersLocal(flags: List<String>) {
        db.groupOrders().reorder(flags)
    }

    /** Push the current local group order to %settings in one batch. */
    suspend fun pushGroupOrders() {
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

    suspend fun createFolder(name: String, sortOrder: Int): Long {
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

    suspend fun renameFolder(id: Long, name: String) {
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

    suspend fun deleteFolder(id: Long) {
        db.folders().deleteMembersOf(id)
        db.folders().delete(id)
        pokeDelEntry(BUCKET_FOLDERS, id.toString())
        // Also clear any folder-members entries keyed by this folder.
        // %settings has no wildcard del — so push a fresh bucket minus
        // anything with this folder id prefix. Cheap because typically
        // few folders.
        clearFolderMembersForFolder(id)
    }

    suspend fun addFolderMember(folderId: Long, whom: String) {
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

    suspend fun addGroupToFolder(folderId: Long, groupFlag: String) {
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

    suspend fun removeFolderMember(folderId: Long, whom: String) {
        db.folders().removeMember(folderId, whom)
        pokeDelEntry(BUCKET_FOLDER_MEMBERS, folderMemberKey(folderId, whom))
    }

    /** Alias for clarity — groups live in the same table as whoms. */
    suspend fun removeGroupFromFolder(folderId: Long, groupFlag: String) =
        removeFolderMember(folderId, groupFlag)

    /**
     * Fast local-only reorder — Room write, no ship I/O. Call from a
     * drag's `onMove` so the LazyColumn animates smoothly. Follow up
     * with [pushFolderMembersOrder] when the drag ends.
     */
    suspend fun reorderFolderMembersLocal(folderId: Long, whoms: List<String>) {
        db.folders().reorderMembers(folderId, whoms)
    }

    /**
     * Push the current folder's member order to %settings. N pokes for
     * N members — kept off the drag-hot-path so it doesn't stall the
     * reorder animation.
     */
    suspend fun pushFolderMembersOrder(folderId: Long) {
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
     * Push the current AI settings to %settings. Call when user has
     * opted into sync and we want to mirror provider/model/key/toggles
     * to the ship.
     */
    suspend fun pushAiSettings() {
        val cfg = aiSettings.state.value
        pokePutEntry(
            BUCKET_AI_SETTINGS, AI_ENTRY,
            buildJsonObject {
                put("provider", cfg.provider.name)
                put("apiKey", cfg.apiKey)
                cfg.model?.let { put("model", it) }
                put("catchMeUpEnabled", cfg.catchMeUpEnabled)
                put("emojiReactEnabled", cfg.emojiReactEnabled)
            },
        )
    }

    /** Nuke the ship's AI settings bucket — when user turns sync off. */
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

    private fun applyAiEntry(obj: JsonObject) {
        val providerStr = obj["provider"]?.jsonPrimitive?.contentIfStr() ?: return
        val provider = runCatching { AiSettings.Provider.valueOf(providerStr) }
            .getOrNull() ?: return
        val apiKey = obj["apiKey"]?.jsonPrimitive?.contentIfStr().orEmpty()
        val model = obj["model"]?.jsonPrimitive?.contentIfStr()
        fun bool(key: String, default: Boolean) =
            obj[key]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: default
        aiSettings.applyRemote(
            provider = provider,
            apiKey = apiKey,
            model = model,
            catchMeUpEnabled = bool("catchMeUpEnabled", true),
            emojiReactEnabled = bool("emojiReactEnabled", true),
        )
    }

    suspend fun addBookmark(whom: String, postId: String, ts: Long) {
        db.bookmarks().upsert(BookmarkEntity(whom, postId, ts))
        pokePutEntry(
            BUCKET_BOOKMARKS, bookmarkKey(whom, postId),
            buildJsonObject { put("ts", ts) },
        )
    }

    suspend fun removeBookmark(whom: String, postId: String) {
        db.bookmarks().remove(whom, postId)
        pokeDelEntry(BUCKET_BOOKMARKS, bookmarkKey(whom, postId))
    }

    suspend fun setNotifyLevel(whom: String, level: String) {
        db.notifyPrefs().upsert(NotifyPreferenceEntity(whom, level))
        pokePutEntry(
            BUCKET_NOTIFY_PREFS, whom,
            buildJsonObject { put("level", level) },
        )
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

    private suspend fun applyBucket(bucket: String, entries: JsonObject?) {
        // Replace-on-apply: any local row not in the incoming bucket
        // will be wiped. For bucket reorders this is the right call.
        when (bucket) {
            BUCKET_GROUP_ORDERS -> {
                val list = entries.orEmpty().mapNotNull { (k, v) ->
                    val ordinal = (unwrap(v) as? JsonObject)?.get("ordinal")
                        ?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                    GroupOrderEntity(flag = k, ordinal = ordinal)
                }
                db.groupOrders().replaceAll(list)
            }
            BUCKET_FOLDERS -> {
                val list = entries.orEmpty().mapNotNull { (k, v) ->
                    val id = k.toLongOrNull() ?: return@mapNotNull null
                    val obj = unwrap(v) as? JsonObject ?: return@mapNotNull null
                    val name = obj["name"]?.jsonPrimitive?.contentIfStr() ?: return@mapNotNull null
                    val sortOrder = obj["sortOrder"]?.jsonPrimitive?.intOrNull ?: 0
                    FolderEntity(id = id, name = name, sortOrder = sortOrder)
                }
                db.folders().replaceAll(list)
            }
            BUCKET_FOLDER_MEMBERS -> {
                val list = entries.orEmpty().mapNotNull { (k, v) ->
                    val (folderId, whom) = parseFolderMemberKey(k) ?: return@mapNotNull null
                    val obj = unwrap(v) as? JsonObject
                    val ordinal = obj?.get("ordinal")?.jsonPrimitive?.intOrNull ?: 0
                    val kind = obj?.get("kind")?.jsonPrimitive?.contentIfStr()
                        ?: FolderMemberEntity.KIND_WHOM
                    FolderMemberEntity(
                        folderId = folderId,
                        whom = whom,
                        ordinal = ordinal,
                        kind = kind,
                    )
                }
                db.folders().replaceAllMembers(list)
            }
            BUCKET_NOTIFY_PREFS -> {
                val list = entries.orEmpty().mapNotNull { (k, v) ->
                    val level = (unwrap(v) as? JsonObject)?.get("level")
                        ?.jsonPrimitive?.contentIfStr() ?: return@mapNotNull null
                    NotifyPreferenceEntity(whom = k, level = level)
                }
                db.notifyPrefs().replaceAll(list)
            }
            BUCKET_BOOKMARKS -> {
                val list = entries.orEmpty().mapNotNull { (k, v) ->
                    val (whom, postId) = parseBookmarkKey(k) ?: return@mapNotNull null
                    val ts = (unwrap(v) as? JsonObject)?.get("ts")
                        ?.jsonPrimitive?.longOrNull ?: 0L
                    BookmarkEntity(whom = whom, postId = postId, bookmarkedMs = ts)
                }
                db.bookmarks().replaceAll(list)
            }
            BUCKET_AI_SETTINGS -> {
                val entry = unwrap(entries?.get(AI_ENTRY)) as? JsonObject ?: return
                applyAiEntry(entry)
            }
        }
    }

    private suspend fun applyEntry(bucket: String, entry: String, value: JsonElement) {
        val unwrapped = unwrap(value)
        when (bucket) {
            BUCKET_GROUP_ORDERS -> {
                val ordinal = (unwrapped as? JsonObject)?.get("ordinal")
                    ?.jsonPrimitive?.intOrNull ?: return
                db.groupOrders().upsertRaw(entry, ordinal)
            }
            BUCKET_FOLDERS -> {
                val id = entry.toLongOrNull() ?: return
                val obj = unwrapped as? JsonObject ?: return
                val name = obj["name"]?.jsonPrimitive?.contentIfStr() ?: return
                val sortOrder = obj["sortOrder"]?.jsonPrimitive?.intOrNull ?: 0
                db.folders().upsert(FolderEntity(id, name, sortOrder))
            }
            BUCKET_FOLDER_MEMBERS -> {
                val (folderId, whom) = parseFolderMemberKey(entry) ?: return
                val obj = unwrapped as? JsonObject
                val ordinal = obj?.get("ordinal")?.jsonPrimitive?.intOrNull ?: 0
                val kind = obj?.get("kind")?.jsonPrimitive?.contentIfStr()
                    ?: FolderMemberEntity.KIND_WHOM
                db.folders().addMemberRaw(folderId, whom, ordinal, kind)
            }
            BUCKET_NOTIFY_PREFS -> {
                val level = (unwrapped as? JsonObject)?.get("level")
                    ?.jsonPrimitive?.contentIfStr() ?: return
                db.notifyPrefs().upsert(NotifyPreferenceEntity(entry, level))
            }
            BUCKET_BOOKMARKS -> {
                val (whom, postId) = parseBookmarkKey(entry) ?: return
                val ts = (unwrapped as? JsonObject)?.get("ts")
                    ?.jsonPrimitive?.longOrNull ?: 0L
                db.bookmarks().upsert(BookmarkEntity(whom, postId, ts))
            }
            BUCKET_AI_SETTINGS -> {
                if (entry == AI_ENTRY) {
                    (unwrapped as? JsonObject)?.let(::applyAiEntry)
                }
            }
        }
    }

    private suspend fun removeEntry(bucket: String, entry: String) {
        when (bucket) {
            BUCKET_GROUP_ORDERS -> db.groupOrders().remove(entry)
            BUCKET_FOLDERS -> {
                val id = entry.toLongOrNull() ?: return
                db.folders().deleteMembersOf(id)
                db.folders().delete(id)
            }
            BUCKET_FOLDER_MEMBERS -> {
                val (folderId, whom) = parseFolderMemberKey(entry) ?: return
                db.folders().removeMember(folderId, whom)
            }
            BUCKET_NOTIFY_PREFS -> db.notifyPrefs().clear(entry)
            BUCKET_BOOKMARKS -> {
                val (whom, postId) = parseBookmarkKey(entry) ?: return
                db.bookmarks().remove(whom, postId)
            }
            BUCKET_AI_SETTINGS -> {
                // Ship removed config — clear local AI settings
                // (only if local is in sync mode so we don't wipe a
                // device that didn't opt in).
                if (aiSettings.state.value.syncEnabled) aiSettings.clear()
            }
        }
    }

    private suspend fun clearBucketLocally(bucket: String) {
        when (bucket) {
            BUCKET_GROUP_ORDERS -> db.groupOrders().replaceAll(emptyList())
            BUCKET_FOLDERS -> db.folders().replaceAll(emptyList())
            BUCKET_FOLDER_MEMBERS -> db.folders().replaceAllMembers(emptyList())
            BUCKET_NOTIFY_PREFS -> db.notifyPrefs().replaceAll(emptyList())
            BUCKET_BOOKMARKS -> db.bookmarks().replaceAll(emptyList())
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

    private fun JsonObject.desk(): String? =
        this["desk"]?.jsonPrimitive?.contentIfStr()
    private fun JsonObject.bucketKey(): String? =
        this["bucket-key"]?.jsonPrimitive?.contentIfStr()
    private fun JsonObject.entryKey(): String? =
        this["entry-key"]?.jsonPrimitive?.contentIfStr()

    private fun JsonPrimitive.contentIfStr(): String? =
        if (isString) content else null
}
