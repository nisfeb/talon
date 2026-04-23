package io.nisfeb.talon.urbit

import android.util.Log
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.ChannelGroupEntity
import io.nisfeb.talon.data.ClubEntity
import io.nisfeb.talon.data.ContactEntity
import io.nisfeb.talon.data.GroupEntity
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.data.ReactionEntity
import io.nisfeb.talon.data.UnreadEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Single-session owner of Urbit traffic + Room writes.
 *
 * start(session) is called once after login. It:
 *   1. Opens a UrbitChannel and stashes it for sends.
 *   2. Scries groups-ui/init-posts for the initial snapshot.
 *   3. Subscribes to %chat /v4 and %channels /v4 for live deltas.
 *   4. Drains the SSE stream forever.
 *
 * Also exposes imperative send/react/delete/edit methods that poke the
 * appropriate agent and optimistically update Room.
 */
class TlonChatRepo(private val db: AppDatabase) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var started = false
    @Volatile private var channel: UrbitChannel? = null
    @Volatile private var http: OkHttpClient? = null
    @Volatile private var ourPatp: String = ""
    @Volatile private var sessionJob: Job? = null
    @Volatile private var lastEventMs: Long = 0L

    /**
     * Mirrors UI-organizational state (pins, group order, folders,
     * notify prefs) to the user's %settings agent so it survives app
     * reinstall and syncs across devices. Exposed so UI layers can
     * call the sync-aware mutations instead of the raw DAOs.
     */
    val settingsSync: SettingsSync = SettingsSync(db)

    /**
     * Called once per incoming message delta from another author, after
     * the row has been written to Room. UI layers wire this to their
     * notification / in-app banner logic. `replyToUs` is true when the
     * delivered entity is a reply whose parent was authored by us — the
     * mentions-only filter uses that flag to still surface direct
     * replies even if the body doesn't contain our patp.
     */
    @Volatile var messageListener: ((MessageEntity, Boolean) -> Unit)? = null

    fun start(session: UrbitSession) {
        if (started) return
        started = true
        ourPatp = session.ourPatp
        http = session.http
        scope.launch { runSessionLoop(session) }
    }

    /**
     * Forever-loop the SSE session. Each iteration opens a fresh
     * UrbitChannel, subscribes, re-scries recent state, then drains
     * events until the stream errors or ends. On failure we wait with
     * exponential backoff (capped) and reconnect — handles doze-wake,
     * network blips, and server-side channel timeouts transparently.
     */
    private suspend fun runSessionLoop(session: UrbitSession) {
        var backoffMs = 2_000L
        var firstRun = true
        while (scope.isActive && started) {
            val ok = runCatching { runSessionOnce(session, firstRun) }
                .onFailure { Log.w(TAG, "session iteration ended", it) }
                .isSuccess
            if (!scope.isActive || !started) break
            firstRun = false
            // Back off exponentially after failures; reset on a normal
            // "stream completed" exit (rare, but polite).
            backoffMs = if (ok) 2_000L else (backoffMs * 2).coerceAtMost(60_000L)
            delay(backoffMs)
        }
    }

    private suspend fun runSessionOnce(session: UrbitSession, firstRun: Boolean) = coroutineScope {
        Log.i(TAG, "opening channel (firstRun=$firstRun)")
        val ch = session.openChannel()
        channel = ch
        lastEventMs = System.currentTimeMillis()

        // Subscribe to all streams we care about. Failures here don't
        // skip the event-loop — a partial subscribe set still delivers
        // whatever the server accepted.
        runCatching { ch.subscribe("chat", "/v4") }
            .onFailure { Log.e(TAG, "chat subscribe failed", it) }
        runCatching { ch.subscribe("channels", "/v4") }
            .onFailure { Log.e(TAG, "channels subscribe failed", it) }
        runCatching { ch.subscribe("activity", "/v4") }
            .onFailure { Log.e(TAG, "activity subscribe failed", it) }
        runCatching { ch.subscribe("contacts", "/v1/news") }
            .onFailure { Log.e(TAG, "contacts subscribe failed", it) }

        // Re-scry init-posts + activity every reconnect so we catch up on
        // anything that landed while the stream was down.
        runCatching { bootstrap(ch) }
            .onFailure { Log.e(TAG, "initPosts scry failed", it) }
        runCatching { bootstrapActivity(ch) }
            .onFailure { Log.e(TAG, "activity scry failed", it) }
        // Contacts / clubs / groups rarely churn — only scry them on
        // first run so reconnect storms don't pound the ship.
        if (firstRun) {
            runCatching { bootstrapContacts(ch) }
                .onFailure { Log.e(TAG, "contacts scry failed", it) }
            runCatching { bootstrapClubs(ch) }
                .onFailure { Log.e(TAG, "clubs scry failed", it) }
            runCatching { bootstrapGroups(ch) }
                .onFailure { Log.e(TAG, "groups scry failed", it) }
        }

        // Settings sync — scries our desk and also subscribes so any
        // other device's changes stream in. Only need to do this on
        // firstRun; the subscription re-establishes automatically once
        // we attach the new channel instance below.
        settingsSync.attach(ch)
        if (firstRun) {
            runCatching { settingsSync.bootstrap() }
                .onFailure { Log.e(TAG, "settings bootstrap failed", it) }
        } else {
            runCatching { ch.subscribe("settings", "/desk/${SettingsSync.DESK}") }
                .onFailure { Log.e(TAG, "settings subscribe failed", it) }
        }

        // Watchdog: if we don't see an event for 90s, the SSE is likely
        // a zombie (doze-frozen or server-side dropped). Cancel the
        // collect job so runSessionLoop reconnects.
        val collectJob = launch {
            ch.events().collect { event ->
                lastEventMs = System.currentTimeMillis()
                runCatching { applyEvent(event.body) }
                    .onFailure { Log.w(TAG, "apply event failed", it) }
                event.id?.let { runCatching { ch.ack(it) } }
            }
            Log.w(TAG, "event stream completed; will reconnect")
        }
        sessionJob = collectJob
        val watchdogJob = launch {
            while (isActive && collectJob.isActive) {
                delay(30_000L)
                val idleMs = System.currentTimeMillis() - lastEventMs
                if (idleMs > 90_000L) {
                    Log.w(TAG, "watchdog: ${idleMs}ms without event; force-reconnect")
                    collectJob.cancel()
                    break
                }
            }
        }
        collectJob.join()
        watchdogJob.cancel()
    }

    /**
     * Immediately tear down the current SSE stream. The session loop
     * will observe the collect job completing and reconnect with fresh
     * subscribes + bootstrap scries. Safe to call from any thread.
     */
    fun forceReconnect() {
        Log.i(TAG, "forceReconnect requested")
        sessionJob?.cancel()
    }

    /**
     * Re-scry init-posts + activity without reopening the channel. Cheap
     * enough to call every time the app comes to the foreground — closes
     * the "messages came in while I was away" gap that doze sometimes
     * opens up even when the service stayed alive.
     */
    fun catchUp() {
        val ch = channel ?: return
        scope.launch {
            runCatching { bootstrap(ch) }
                .onFailure { Log.w(TAG, "catchUp bootstrap failed", it) }
            runCatching { bootstrapActivity(ch) }
                .onFailure { Log.w(TAG, "catchUp activity failed", it) }
        }
    }

    fun stop() {
        started = false
        channel = null
        scope.cancel()
    }

    // ───────── sends ─────────

    /** Plain text message. Routes by whom prefix. Returns minted post id. */
    suspend fun send(whom: String, text: String): String =
        postContent(whom, textToStory(text))

    /**
     * Send an image message. `src` is the hosted URL returned by
     * uploadImage; width/height are the image's natural dimensions (0 if
     * unknown); alt is a short description (often the filename).
     */
    suspend fun sendImage(
        whom: String,
        src: String,
        width: Int,
        height: Int,
        alt: String,
    ): String {
        val content = buildJsonArray {
            add(buildJsonObject {
                put("block", buildJsonObject {
                    put("image", buildJsonObject {
                        put("src", src)
                        put("width", width)
                        put("height", height)
                        put("alt", alt)
                    })
                })
            })
        }
        return postContent(whom, content)
    }

    /** Routes content → appropriate chat / club / channel poke + DB write. */
    private suspend fun postContent(whom: String, content: JsonArray): String {
        val ch = channel ?: error("not connected")
        val sent = System.currentTimeMillis()
        val da = UrbitTime.unixMsToDa(sent)
        // %channels mints post ids with its own entropy (not purely a
        // function of essay.sent), so we can't predict the server id
        // locally. Use a sentinel "local_<da>" id for the optimistic
        // insert and reap it when the SSE echo's server-id row arrives
        // for the same (whom, author, sentMs).
        val id = if (whom.startsWith("chat/")) "local_${da}"
        else UrbitTime.formatPostId(ourPatp, da)
        val essay = buildEssay(content, sent)
        val addDelta = buildJsonObject {
            put("add", buildJsonObject {
                put("essay", essay)
                put("time", JsonNull)
            })
        }
        when {
            whom.startsWith("~") -> ch.poke(
                app = "chat", mark = "chat-dm-action-2",
                payload = dmAction(whom, id, addDelta),
            )
            whom.startsWith("0v") -> ch.poke(
                app = "chat", mark = "chat-club-action-2",
                payload = clubAction(whom, id, addDelta),
            )
            whom.startsWith("chat/") -> ch.poke(
                app = "channels", mark = "channel-action-2",
                payload = channelAction(whom, buildJsonObject {
                    put("post", buildJsonObject { put("add", essay) })
                }),
            )
            else -> error("unsupported whom: $whom")
        }
        db.messages().upsert(toEntity(whom, id, essay))
        return id
    }

    /**
     * Update our own contact card. Any field passed as null is left
     * untouched on the server; pass the empty string to clear a field.
     *
     * Uses the classic `contact-action` mark (edit-list form) rather than
     * `contact-action-1` so older ships on mixed-version networks still
     * accept the poke.
     */
    suspend fun updateProfile(
        nickname: String? = null,
        bio: String? = null,
        avatarUrl: String? = null,
        status: String? = null,
        /** Color as `#RRGGBB`; null = don't change, empty = clear. */
        color: String? = null,
    ) {
        val ch = channel ?: error("not connected")
        val edits = buildJsonArray {
            nickname?.let { add(buildJsonObject { put("nickname", it) }) }
            bio?.let { add(buildJsonObject { put("bio", it) }) }
            avatarUrl?.let { add(buildJsonObject { put("avatar", it) }) }
            status?.let { add(buildJsonObject { put("status", it) }) }
            color?.let { add(buildJsonObject { put("color", toUrbitHexColor(it)) }) }
        }
        if (edits.isEmpty()) return
        ch.poke(
            app = "contacts",
            mark = "contact-action",
            payload = buildJsonObject { put("edit", edits) },
        )
        // Optimistic local update. Merge with any existing row so fields
        // the caller didn't supply stay intact. Stamp statusUpdatedMs
        // when the status actually changes so the feed reorders.
        val current = db.contacts().get(ourPatp)
        val newStatus = status?.takeIf { it.isNotBlank() } ?: current?.status
        val statusChanged = status != null && status != current?.status.orEmpty()
        db.contacts().upsert(
            ContactEntity(
                ship = ourPatp,
                nickname = nickname?.takeIf { it.isNotBlank() } ?: current?.nickname,
                bio = bio?.takeIf { it.isNotBlank() } ?: current?.bio,
                avatarUrl = avatarUrl?.takeIf { it.isNotBlank() } ?: current?.avatarUrl,
                status = newStatus,
                statusUpdatedMs = if (statusChanged) System.currentTimeMillis()
                    else current?.statusUpdatedMs,
                color = color?.takeIf { it.isNotBlank() } ?: current?.color,
            )
        )
    }

    /** `#FF5050` → `0xff.5050` (Urbit @ux form with dot separator). */
    private fun toUrbitHexColor(hex: String): String {
        val stripped = hex.trim().removePrefix("#").lowercase()
        val padded = stripped.padStart(6, '0').takeLast(6)
        return "0x" + padded.substring(0, 2) + "." + padded.substring(2, 6)
    }

    /**
     * One row for the Activity feed screen. Best-effort parse of the
     * heterogeneous ActivityEvent shapes — we surface the fields UI
     * actually renders and ignore events we don't recognize.
     */
    data class ActivityFeedItem(
        val kind: String,          // "Mentioned you", "Replied to you", etc.
        val author: String?,       // ~patp who did the thing, if known
        val whom: String?,         // conversation to open on tap, if known
        val contentJson: String?,  // story JSON for preview rendering
        val sentMs: Long,          // event time for sorting + display
        val title: String,         // human label for the source convo
    )

    suspend fun fetchActivityFeed(): List<ActivityFeedItem> {
        val ch = channel ?: error("not connected")
        val body = ch.scry("activity", "/v5/feed/init/30") as? JsonObject
            ?: return emptyList()
        val all = body["all"] as? JsonArray ?: return emptyList()
        val items = mutableListOf<ActivityFeedItem>()
        for (bundleEl in all) {
            val bundle = bundleEl as? JsonObject ?: continue
            val sourceKey = bundle["source-key"]?.jsonPrimitive?.contentIfString()
            val sourceWhom = sourceKey?.let { sourceKeyToWhom(it) }
            val title = when {
                sourceWhom == null -> sourceKey ?: "activity"
                sourceWhom.startsWith("~") -> sourceWhom
                sourceWhom.startsWith("chat/") -> "#" + sourceWhom.substringAfterLast('/')
                else -> sourceWhom
            }
            val events = bundle["events"] as? JsonArray ?: continue
            for (e in events) {
                val wrap = e as? JsonObject ?: continue
                val inner = wrap["event"] as? JsonObject ?: continue
                val tag = inner.keys.firstOrNull() ?: continue
                val eventObj = inner[tag] as? JsonObject ?: continue

                val label = when (tag) {
                    "post-mention", "dm-post-mention" -> "Mentioned you"
                    "reply-mention", "dm-reply-mention" -> "Mentioned you in a reply"
                    "reply", "dm-reply" -> "Replied"
                    "post", "dm-post" -> "Posted"
                    "dm-invite" -> "Invited you to a DM"
                    "group-ask" -> "Requested group access"
                    "group-invite" -> "Invited you to a group"
                    else -> tag
                }
                val author = eventObj["mention-author"]?.jsonPrimitive?.contentIfString()
                    ?: eventObj["author"]?.jsonPrimitive?.contentIfString()
                    ?: (eventObj["key"] as? JsonObject)?.get("id")?.jsonPrimitive
                        ?.contentIfString()?.substringBefore('/')
                val content = eventObj["content"]?.let { it.toString() }
                val timeStr = wrap["time"]?.jsonPrimitive?.contentIfString() ?: ""
                val sentMs = parseEventTimeMs(timeStr, eventObj)

                items.add(
                    ActivityFeedItem(
                        kind = label,
                        author = author,
                        whom = sourceWhom,
                        contentJson = content,
                        sentMs = sentMs,
                        title = title,
                    )
                )
            }
        }
        return items.sortedByDescending { it.sentMs }
    }

    /** Try a couple of sources to get a unix-ms timestamp for an event. */
    private fun parseEventTimeMs(timeStr: String, eventObj: JsonObject): Long {
        // Events sometimes carry a `key.time` or just `time` as a dotted
        // urbit numeric. Fall back to 0 so the sort at least doesn't crash.
        val keyTime = (eventObj["key"] as? JsonObject)?.get("time")?.jsonPrimitive
            ?.contentIfString()
        val raw = keyTime ?: timeStr
        return runCatching {
            val digits = raw.replace(".", "")
            if (digits.all { it.isDigit() }) digits.toLong() else 0L
        }.getOrDefault(0L)
    }

    /**
     * Scry a channel for a single post by @da — used to resolve chan
     * cites whose target isn't already in our local window. Upserts
     * the result into Room so later renders just hit the cache.
     */
    suspend fun fetchCitePost(nest: String, postDa: String): MessageEntity? {
        val ch = channel ?: return null
        val body = runCatching { ch.scry("channels", "/v5/$nest/posts/post/$postDa") }
            .getOrNull() as? JsonObject ?: return null
        val seal = body["seal"] as? JsonObject ?: return null
        val id = seal["id"]?.jsonPrimitive?.contentIfString() ?: return null
        val essay = body["essay"] as? JsonObject ?: return null
        val entity = toEntity(nest, id, essay)
        db.messages().upsert(entity)
        (seal["reacts"] as? JsonObject)?.let { reacts ->
            db.reactions().clearForPost(nest, id)
            val rx = reacts.entries.mapNotNull { (author, emoji) ->
                val e = emoji.jsonPrimitive.contentIfString() ?: return@mapNotNull null
                ReactionEntity(nest, id, author, e)
            }
            if (rx.isNotEmpty()) db.reactions().upsertAll(rx)
        }
        return entity
    }

    /**
     * Scry a reply under a channel post. v4 mark-declaration is the
     * one that actually works; v5 is misaligned on current Tlon ships.
     */
    suspend fun fetchCiteReply(nest: String, parentDa: String, replyDa: String): MessageEntity? {
        val ch = channel ?: return null
        val path = "/v4/$nest/posts/post/id/$parentDa/replies/reply/id/$replyDa"
        val body = runCatching { ch.scry("channels", path) }
            .getOrNull() as? JsonObject ?: return null
        val seal = body["seal"] as? JsonObject ?: return null
        val id = seal["id"]?.jsonPrimitive?.contentIfString() ?: return null
        val parentId = seal["parent-id"]?.jsonPrimitive?.contentIfString()
            ?: seal["parent"]?.jsonPrimitive?.contentIfString()
            ?: return null
        val essay = body["reply-essay"] as? JsonObject ?: return null
        val entity = toReplyEntity(nest, parentId, id, essay)
        db.messages().upsert(entity)
        return entity
    }

    /**
     * Fill holes in a conversation by re-fetching the last `count` posts
     * ending at the current newest known post. Uses the same older-than-
     * cursor scries as pagination, so anything that should have arrived
     * via SSE but got dropped (e.g. pre-buffer-fix events that were ACK'd
     * but never applied) gets backfilled. Idempotent upsert — no dupes.
     */
    suspend fun refreshConversation(whom: String, count: Int = 100) {
        val ch = channel ?: run {
            Log.w(TAG, "refreshConversation($whom): no channel")
            return
        }
        val newest = db.messages().newestIdFor(whom)
        // In path form Urbit @ud atoms need dotted-decimal (3-digit groups).
        val dottedCursor = newest?.let {
            runCatching { UrbitTime.daToUd(java.math.BigInteger(it)) }.getOrNull()
        }
        val app = when {
            whom.startsWith("~") -> "chat"
            whom.startsWith("0v") -> "chat"
            whom.startsWith("chat/") -> "channels"
            else -> return
        }
        val postsKey = if (app == "channels") "posts" else "writs"

        // Build a probe list: try known path versions + two shapes
        // (cursor-based `older` and no-cursor `newest`) for each.
        //
        // For channels we prefer `/post` (full shape with reference/cite
        // blocks intact) over `/outline` (lightweight: outline strips
        // file-reference blocks, which is what hid shared-file posts).
        val paths = buildList {
            val versions = listOf("v4", "v3", "v2", "v1", "v5")
            val channelMarks = listOf("post", "outline")
            val writMarks = listOf("heavy", "light")
            for (v in versions) {
                when (app) {
                    "chat" -> for (mark in writMarks) {
                        when {
                            whom.startsWith("~") -> add("/$v/dm/$whom/writs/newest/$count/$mark")
                            whom.startsWith("0v") -> add("/$v/club/$whom/writs/newest/$count/$mark")
                        }
                    }
                    "channels" -> for (mark in channelMarks) {
                        add("/$v/$whom/posts/newest/$count/$mark")
                    }
                }
                if (dottedCursor != null) {
                    when (app) {
                        "chat" -> for (mark in writMarks) {
                            when {
                                whom.startsWith("~") -> add("/$v/dm/$whom/writs/older/$dottedCursor/$count/$mark")
                                whom.startsWith("0v") -> add("/$v/club/$whom/writs/older/$dottedCursor/$count/$mark")
                            }
                        }
                        "channels" -> for (mark in channelMarks) {
                            add("/$v/$whom/posts/older/$dottedCursor/$count/$mark")
                        }
                    }
                }
            }
        }

        var body: JsonElement? = null
        var lastErr: Throwable? = null
        var winningPath: String? = null
        for (path in paths) {
            val attempt = runCatching { ch.scry(app, path) }
            if (attempt.isSuccess) {
                body = attempt.getOrNull()
                winningPath = path
                break
            } else {
                lastErr = attempt.exceptionOrNull()
            }
        }
        if (body == null) {
            Log.w(TAG, "refreshConversation($whom) all ${paths.size} probes failed; last err: ${lastErr?.message}")
            return
        }
        Log.i(TAG, "refreshConversation($whom): winning path $app $winningPath")
        val obj = body as? JsonObject
        if (obj == null) {
            Log.w(TAG, "refreshConversation($whom): scry body not object: ${body::class.simpleName}")
            return
        }
        val posts = obj[postsKey] as? JsonObject
        if (posts == null) {
            Log.w(TAG, "refreshConversation($whom): no '$postsKey' key; keys=${obj.keys}")
            return
        }
        val messages = mutableListOf<MessageEntity>()
        val reactions = mutableListOf<ReactionEntity>()
        posts.forEach { (_, post) -> ingestPost(whom, post, messages, reactions) }
        if (messages.isNotEmpty()) db.messages().upsertAll(messages)
        if (reactions.isNotEmpty()) db.reactions().upsertAll(reactions)
        // Clean up stale optimistic-insert rows for channels whose id
        // format we'd gotten wrong in earlier builds. One-shot; no-op
        // once all such ghosts are gone.
        val purgedBefore = if (whom.startsWith("chat/")) {
            val before = db.messages().countFor(whom)
            db.messages().purgeStaleLocalIds(whom)
            val after = db.messages().countFor(whom)
            before - after
        } else 0
        Log.i(TAG, "refreshConversation($whom): ${posts.size} posts ingested (purged $purgedBefore stale ~ ids)")
        // DEBUG: dump our 3 most recent posts to spot any remaining dupe.
        val mine = db.messages().debugByAuthor(whom, ourPatp)
        mine.take(3).forEach { r ->
            Log.i(TAG, "  mine [sent=${r.sentMs}] id=${r.id}: ${r.contentPreview.take(120)}")
        }
    }

    /**
     * Fetch older posts for a conversation and upsert them. Returns true
     * if the server claims more history is available below what we just
     * loaded; false if we've hit the bottom (or the scry errored, in
     * which case callers should stop asking).
     *
     *   DM:      %chat  /v4/dm/~peer/writs/older/{cursor}/{count}/light
     *   Club:    %chat  /v4/club/0v.../writs/older/{cursor}/{count}/light
     *   Channel: %channels /v5/chat/~host/name/posts/older/{cursor}/{count}/outline
     */
    suspend fun loadOlder(whom: String, count: Int = 30): Boolean {
        val ch = channel ?: return false
        if (paginationExhausted.contains(whom)) return false
        val cursor = db.messages().oldestIdFor(whom) ?: return false

        val dotted = runCatching { UrbitTime.daToUd(java.math.BigInteger(cursor)) }
            .getOrNull() ?: cursor
        val (app, paths, postsKey) = when {
            whom.startsWith("~") -> Triple(
                "chat",
                buildList<String> {
                    for (v in listOf("v4", "v3", "v2", "v1")) {
                        for (mark in listOf("heavy", "light")) {
                            add("/$v/dm/$whom/writs/older/$dotted/$count/$mark")
                        }
                    }
                },
                "writs",
            )
            whom.startsWith("0v") -> Triple(
                "chat",
                buildList<String> {
                    for (v in listOf("v4", "v3", "v2", "v1")) {
                        for (mark in listOf("heavy", "light")) {
                            add("/$v/club/$whom/writs/older/$dotted/$count/$mark")
                        }
                    }
                },
                "writs",
            )
            whom.startsWith("chat/") -> Triple(
                "channels",
                buildList<String> {
                    for (v in listOf("v4", "v3", "v2", "v1", "v5")) {
                        for (mark in listOf("post", "outline")) {
                            add("/$v/$whom/posts/older/$dotted/$count/$mark")
                        }
                    }
                },
                "posts",
            )
            else -> return false
        }

        var body: JsonObject? = null
        var lastErr: Throwable? = null
        for (p in paths) {
            val attempt = runCatching { ch.scry(app, p) }
            if (attempt.isSuccess) {
                body = attempt.getOrNull() as? JsonObject
                break
            } else {
                lastErr = attempt.exceptionOrNull()
            }
        }
        if (body == null) {
            Log.w(TAG, "loadOlder $whom all versions failed; last err: ${lastErr?.message}")
            return false
        }

        val posts = body[postsKey] as? JsonObject
        if (posts != null) {
            val messages = mutableListOf<MessageEntity>()
            val reactions = mutableListOf<ReactionEntity>()
            posts.forEach { (_, post) -> ingestPost(whom, post, messages, reactions) }
            if (messages.isNotEmpty()) db.messages().upsertAll(messages)
            if (reactions.isNotEmpty()) db.reactions().upsertAll(reactions)
        }

        val hasMore = body["older"].let { it != null && it !is JsonNull }
        if (!hasMore) paginationExhausted.add(whom)
        return hasMore
    }

    private val paginationExhausted = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Upload an image. Tries memex first (Tlon-hosted ships with %genuine
     * installed), then falls back to the ship's %storage S3 credentials.
     * Self-hosted ships configure their own bucket in Landscape's Storage
     * settings; we scry those creds and do a direct SigV4 PUT.
     */
    suspend fun uploadImage(
        bytes: ByteArray,
        contentType: String,
        fileName: String,
    ): String = withContext(Dispatchers.IO) {
        val ch = channel ?: error("not connected")
        val client = http ?: error("not connected")

        val memexErr = runCatching {
            uploadViaMemex(ch, client, bytes, contentType, fileName)
        }.onSuccess { return@withContext it }.exceptionOrNull()

        val storageErr = runCatching {
            uploadViaStorage(ch, client, bytes, contentType, fileName)
        }.onSuccess { return@withContext it }.exceptionOrNull()

        error(
            "image upload failed: memex=${memexErr?.message}; " +
                "storage=${storageErr?.message}"
        )
    }

    private suspend fun uploadViaMemex(
        ch: UrbitChannel,
        client: OkHttpClient,
        bytes: ByteArray,
        contentType: String,
        fileName: String,
    ): String {
        val tokenElement = ch.scry("genuine", "/secret")
        val token = (tokenElement as? JsonPrimitive)?.contentIfString()
            ?: error("no memex token")

        val bareShip = ourPatp.removePrefix("~")
        val memexBody = buildJsonObject {
            put("token", token)
            put("contentLength", bytes.size.toLong())
            put("contentType", contentType)
            put("fileName", fileName)
        }.toString()

        val memexReq = Request.Builder()
            .url("https://memex.tlon.network/v1/$bareShip/upload")
            .put(memexBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val (hostedUrl, uploadUrl) = client.newCall(memexReq).execute().use { resp ->
            if (!resp.isSuccessful) error("memex upload-url failed: HTTP ${resp.code}")
            val body = resp.body?.string() ?: error("empty memex response")
            val obj = Json.parseToJsonElement(body).jsonObject
            val hosted = obj["hostedUrl"]?.jsonPrimitive?.contentIfString()
                ?: error("no hostedUrl in memex response")
            val upload = obj["uploadUrl"]?.jsonPrimitive?.contentIfString()
                ?: error("no uploadUrl in memex response")
            hosted to upload
        }

        val uploadReq = Request.Builder()
            .url(uploadUrl)
            .put(bytes.toRequestBody(contentType.toMediaType()))
            .header("Cache-Control", "public, max-age=3600")
            .build()
        client.newCall(uploadReq).execute().use { resp ->
            if (!resp.isSuccessful) error("memex PUT failed: HTTP ${resp.code}")
        }
        return hostedUrl
    }

    private suspend fun uploadViaStorage(
        ch: UrbitChannel,
        client: OkHttpClient,
        bytes: ByteArray,
        contentType: String,
        fileName: String,
    ): String {
        val credsElement = ch.scry("storage", "/credentials")
        val configElement = ch.scry("storage", "/configuration")

        val creds = parseStorageCredentials(credsElement)
            ?: error("no %storage credentials on this ship")
        val config = parseStorageConfiguration(configElement)
            ?: error("no %storage configuration on this ship")
        if (!config.service.isNullOrEmpty() && config.service != "credentials") {
            error("%storage is in '${config.service}' mode; only 'credentials' is supported")
        }
        if (creds.accessKeyId.isBlank() || creds.secretAccessKey.isBlank() ||
            creds.endpoint.isBlank() || config.bucket.isBlank()
        ) {
            error("%storage credentials not fully configured")
        }

        val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val key = "talon/${UrbitTime.unixMsToDa(System.currentTimeMillis())}-$safeName"

        return S3Uploader.put(
            http = client,
            creds = S3Uploader.Credentials(
                endpoint = creds.endpoint,
                accessKeyId = creds.accessKeyId,
                secretAccessKey = creds.secretAccessKey,
            ),
            config = S3Uploader.Configuration(
                bucket = config.bucket,
                region = config.region.ifBlank { "us-east-1" },
                publicUrlBase = config.publicUrlBase,
            ),
            key = key,
            bytes = bytes,
            contentType = contentType,
        )
    }

    private data class StorageCreds(
        val endpoint: String,
        val accessKeyId: String,
        val secretAccessKey: String,
    )

    private data class StorageConfig(
        val bucket: String,
        val region: String,
        val publicUrlBase: String?,
        val service: String?,
    )

    private fun parseStorageCredentials(body: JsonElement?): StorageCreds? {
        // %storage returns {"storage-update": {"credentials": {...}}}; field
        // names may be kebab-case or camelCase depending on the agent version.
        val obj = (body as? JsonObject) ?: return null
        val inner = (obj["storage-update"] as? JsonObject)?.get("credentials") as? JsonObject
            ?: (obj["credentials"] as? JsonObject)
            ?: obj
        val endpoint = inner["endpoint"]?.jsonPrimitive?.contentIfString() ?: ""
        val accessKeyId = inner["access-key-id"]?.jsonPrimitive?.contentIfString()
            ?: inner["accessKeyId"]?.jsonPrimitive?.contentIfString()
            ?: ""
        val secretAccessKey = inner["secret-access-key"]?.jsonPrimitive?.contentIfString()
            ?: inner["secretAccessKey"]?.jsonPrimitive?.contentIfString()
            ?: ""
        return StorageCreds(endpoint, accessKeyId, secretAccessKey)
    }

    private fun parseStorageConfiguration(body: JsonElement?): StorageConfig? {
        val obj = (body as? JsonObject) ?: return null
        val inner = (obj["storage-update"] as? JsonObject)?.get("configuration") as? JsonObject
            ?: (obj["configuration"] as? JsonObject)
            ?: obj
        val bucket = inner["current-bucket"]?.jsonPrimitive?.contentIfString()
            ?: inner["currentBucket"]?.jsonPrimitive?.contentIfString()
            ?: ""
        val region = inner["region"]?.jsonPrimitive?.contentIfString() ?: ""
        val publicUrlBase = inner["public-url-base"]?.jsonPrimitive?.contentIfString()
            ?: inner["publicUrlBase"]?.jsonPrimitive?.contentIfString()
        val service = inner["service"]?.jsonPrimitive?.contentIfString()
        return StorageConfig(bucket, region, publicUrlBase, service)
    }

    /** Add or replace our reaction on a post. `emoji` is a raw shortcode. */
    suspend fun react(whom: String, postId: String, emoji: String) {
        val ch = channel ?: error("not connected")
        val delta = buildJsonObject {
            put("add-react", buildJsonObject {
                put("author", ourPatp)
                put("react", emoji)
            })
        }
        when {
            whom.startsWith("~") -> ch.poke(
                app = "chat", mark = "chat-dm-action-2",
                payload = dmAction(whom, postId, delta),
            )
            whom.startsWith("0v") -> ch.poke(
                app = "chat", mark = "chat-club-action-2",
                payload = clubAction(whom, postId, delta),
            )
            whom.startsWith("chat/") -> ch.poke(
                app = "channels", mark = "channel-action-2",
                payload = channelAction(whom, buildJsonObject {
                    put("post", buildJsonObject {
                        put("add-react", buildJsonObject {
                            put("id", postId)
                            put("author", ourPatp)
                            put("react", emoji)
                        })
                    })
                }),
            )
            else -> error("unsupported whom: $whom")
        }
        db.reactions().upsert(ReactionEntity(whom, postId, ourPatp, emoji))
    }

    /** Remove our reaction from a post. */
    suspend fun unreact(whom: String, postId: String) {
        val ch = channel ?: error("not connected")
        val delta = buildJsonObject { put("del-react", ourPatp) }
        when {
            whom.startsWith("~") -> ch.poke(
                app = "chat", mark = "chat-dm-action-2",
                payload = dmAction(whom, postId, delta),
            )
            whom.startsWith("0v") -> ch.poke(
                app = "chat", mark = "chat-club-action-2",
                payload = clubAction(whom, postId, delta),
            )
            whom.startsWith("chat/") -> ch.poke(
                app = "channels", mark = "channel-action-2",
                payload = channelAction(whom, buildJsonObject {
                    put("post", buildJsonObject {
                        put("del-react", buildJsonObject {
                            put("id", postId)
                            put("author", ourPatp)
                        })
                    })
                }),
            )
            else -> error("unsupported whom: $whom")
        }
        db.reactions().delete(whom, postId, ourPatp)
    }

    /** Delete a message. Author-only on the server. */
    suspend fun delete(whom: String, postId: String) {
        val ch = channel ?: error("not connected")
        val delta = buildJsonObject { put("del", JsonNull) }
        when {
            whom.startsWith("~") -> ch.poke(
                app = "chat", mark = "chat-dm-action-2",
                payload = dmAction(whom, postId, delta),
            )
            whom.startsWith("0v") -> ch.poke(
                app = "chat", mark = "chat-club-action-2",
                payload = clubAction(whom, postId, delta),
            )
            whom.startsWith("chat/") -> ch.poke(
                app = "channels", mark = "channel-action-2",
                payload = channelAction(whom, buildJsonObject {
                    // channel-action-2's `%post` action takes an id + a
                    // u-post update. Deleting = setting the post to ~.
                    put("post", buildJsonObject {
                        put("id", JsonPrimitive(postId))
                        put("u-post", buildJsonObject {
                            put("set", JsonNull)
                        })
                    })
                }),
            )
            else -> error("unsupported whom: $whom")
        }
        // Do NOT soft-delete locally — wait for the server's SSE echo
        // (applyChatDelta / applyChannelDelta handle the actual delete)
        // so the row stays visible if the poke is rejected.
    }

    /**
     * Reply to a top-level post. `parentId` is the post being replied to.
     * Returns the minted reply id so callers can match the echo.
     */
    suspend fun reply(whom: String, parentId: String, text: String): String {
        val ch = channel ?: error("not connected")
        val sent = System.currentTimeMillis()
        val da = UrbitTime.unixMsToDa(sent)
        // Same local-sentinel id rule as postContent — %channels assigns
        // unpredictable post ids so we can't pre-compute them.
        val replyId = if (whom.startsWith("chat/")) "local_${da}"
        else UrbitTime.formatPostId(ourPatp, da)
        val replyEssay = buildJsonObject {
            put("content", textToStory(text))
            put("author", ourPatp)
            put("sent", sent)
            put("blob", JsonNull)
        }

        when {
            whom.startsWith("~") -> {
                ch.poke(
                    app = "chat", mark = "chat-dm-action-2",
                    payload = dmAction(whom, parentId, replyDelta(replyId, replyEssay)),
                )
                db.messages().upsert(
                    toReplyEntity(whom, parentId, replyId, replyEssay)
                )
            }
            whom.startsWith("0v") -> {
                ch.poke(
                    app = "chat", mark = "chat-club-action-2",
                    payload = clubAction(whom, parentId, replyDelta(replyId, replyEssay)),
                )
                db.messages().upsert(
                    toReplyEntity(whom, parentId, replyId, replyEssay)
                )
            }
            whom.startsWith("chat/") -> {
                // channels c-reply shape nests under action:, not c-reply:
                ch.poke(
                    app = "channels", mark = "channel-action-2",
                    payload = channelAction(whom, buildJsonObject {
                        put("post", buildJsonObject {
                            put("reply", buildJsonObject {
                                put("id", parentId)
                                put("action", buildJsonObject {
                                    put("add", replyEssay)
                                })
                            })
                        })
                    }),
                )
                db.messages().upsert(
                    toReplyEntity(whom, parentId, replyId, replyEssay)
                )
            }
            else -> error("unsupported whom: $whom")
        }
        return replyId
    }

    /**
     * Edit a message's text content. Channels only — %chat doesn't expose
     * an edit action. Replaces the essay with a fresh one that keeps the
     * original post id.
     */
    suspend fun edit(whom: String, postId: String, text: String) {
        val ch = channel ?: error("not connected")
        if (!whom.startsWith("chat/")) error("edit only supported on channel chats")
        val sent = System.currentTimeMillis()
        val essay = buildEssay(textToStory(text), sent)
        ch.poke(
            app = "channels",
            mark = "channel-action-2",
            payload = channelAction(whom, buildJsonObject {
                put("post", buildJsonObject {
                    put("edit", buildJsonObject {
                        put("id", postId)
                        put("essay", essay)
                    })
                })
            }),
        )
        // Server will echo via channel-response-5 with the new essay.
    }

    // ───────── ingest ─────────

    private suspend fun bootstrap(channel: UrbitChannel) {
        val body = channel.scry("groups-ui", "/v6/init-posts/50/50")
        val obj = body as? JsonObject ?: return

        val messages = mutableListOf<MessageEntity>()
        val reactions = mutableListOf<ReactionEntity>()

        (obj["chat"] as? JsonObject)?.forEach { (peer, posts) ->
            (posts as? JsonObject)?.forEach { (_, post) ->
                ingestPost(peer, post, messages, reactions)
            }
        }
        (obj["channels"] as? JsonObject)?.forEach { (nest, posts) ->
            (posts as? JsonObject)?.forEach { (_, post) ->
                ingestPost(nest, post, messages, reactions)
            }
        }

        if (messages.isNotEmpty()) db.messages().upsertAll(messages)
        if (reactions.isNotEmpty()) db.reactions().upsertAll(reactions)
    }

    private suspend fun applyEvent(event: JsonElement) {
        val outer = event as? JsonObject ?: return
        // Urbit's /~/channel/ SSE wraps each fact as:
        //   { id: N, response: "diff"|"poke"|"subscribe", mark: "...", json: {…} }
        // The actual per-agent payload lives in obj["json"]. Acks and
        // subscribe-replies have no json and can be skipped.
        if (outer["response"]?.jsonPrimitive?.contentIfString() != "diff") return
        val payload = outer["json"] as? JsonObject ?: return

        // %chat writ-response-4: { whom, id, response }
        val whom = payload["whom"]?.jsonPrimitive?.contentIfString()
        val directId = payload["id"]?.jsonPrimitive?.contentIfString()
        if (whom != null && directId != null) {
            applyChatDelta(whom, directId, payload["response"] as? JsonObject ?: return)
            return
        }

        // %channels r-channels-5: { nest, response }
        val nest = payload["nest"]?.jsonPrimitive?.contentIfString()
        val channelResponse = payload["response"] as? JsonObject
        if (nest != null && channelResponse != null) {
            applyChannelDelta(nest, channelResponse)
            return
        }

        // %activity update — variants keyed by "activity"/"read"/"del"/etc.
        if (payload.containsKey("activity") || payload.containsKey("read") || payload.containsKey("del")) {
            applyActivityUpdate(payload)
            return
        }

        // %contacts /v1/news — {page}, {peer}, or {wipe} envelope.
        if (payload.containsKey("page") || payload.containsKey("peer")) {
            applyContactsNews(payload)
            return
        }

        // %settings events — wrapped as {put-entry|del-entry|put-bucket|…}.
        if (
            payload.containsKey("put-entry") ||
            payload.containsKey("del-entry") ||
            payload.containsKey("put-bucket") ||
            payload.containsKey("del-bucket")
        ) {
            settingsSync.applySettingsEvent(payload)
            return
        }
    }

    private suspend fun applyChatDelta(
        whom: String,
        id: String,
        response: JsonObject,
    ) {
        (response["add"] as? JsonObject)?.let { add ->
            val essay = add["essay"] as? JsonObject ?: return@let
            val entity = toEntity(whom, id, essay)
            db.messages().upsert(entity)
            if (entity.author != ourPatp) messageListener?.invoke(entity, false)
            return
        }
        response["del"]?.let {
            db.messages().softDelete(whom, id)
            db.reactions().clearForPost(whom, id)
            return
        }
        (response["add-react"] as? JsonObject)?.let { ar ->
            val author = ar["author"]?.jsonPrimitive?.contentIfString() ?: return@let
            val react = ar["react"]?.jsonPrimitive?.contentIfString() ?: return@let
            db.reactions().upsert(ReactionEntity(whom, id, author, react))
            return
        }
        response["del-react"]?.jsonPrimitive?.contentIfString()?.let { author ->
            db.reactions().delete(whom, id, author)
            return
        }
        (response["reply"] as? JsonObject)?.let { reply ->
            applyChatReplyDelta(whom, parentId = id, reply)
            return
        }
    }

    private suspend fun applyChatReplyDelta(
        whom: String,
        parentId: String,
        reply: JsonObject,
    ) {
        val replyId = reply["id"]?.jsonPrimitive?.contentIfString() ?: return
        val delta = reply["delta"] as? JsonObject ?: return

        (delta["add"] as? JsonObject)?.let { add ->
            val replyEssay = add["reply-essay"] as? JsonObject ?: return@let
            val entity = toReplyEntity(whom, parentId, replyId, replyEssay)
            db.messages().upsert(entity)
            if (entity.author != ourPatp) {
                val parent = db.messages().getOne(whom, parentId)
                val replyToUs = parent?.author == ourPatp
                messageListener?.invoke(entity, replyToUs)
            }
            return
        }
        delta["del"]?.let {
            db.messages().softDelete(whom, replyId)
            db.reactions().clearForPost(whom, replyId)
            return
        }
        (delta["add-react"] as? JsonObject)?.let { ar ->
            val author = ar["author"]?.jsonPrimitive?.contentIfString() ?: return@let
            val react = ar["react"]?.jsonPrimitive?.contentIfString() ?: return@let
            db.reactions().upsert(ReactionEntity(whom, replyId, author, react))
            return
        }
        delta["del-react"]?.jsonPrimitive?.contentIfString()?.let { author ->
            db.reactions().delete(whom, replyId, author)
        }
    }

    private suspend fun applyChannelDelta(nest: String, response: JsonObject) {
        (response["posts"] as? JsonObject)?.let { posts ->
            posts.keys.firstOrNull()?.let { firstKey ->
                Log.i(TAG, "applyChannelDelta $nest server-echoed id=$firstKey")
            }
            val messages = mutableListOf<MessageEntity>()
            val reactions = mutableListOf<ReactionEntity>()
            posts.forEach { (_, post) -> ingestPost(nest, post, messages, reactions) }
            if (messages.isNotEmpty()) db.messages().upsertAll(messages)
            if (reactions.isNotEmpty()) db.reactions().upsertAll(reactions)
            return
        }
        (response["post"] as? JsonObject)?.let { wrap ->
            val id = wrap["id"]?.jsonPrimitive?.contentIfString() ?: return@let
            Log.i(TAG, "applyChannelDelta $nest r-post id=$id")
            val rPost = wrap["r-post"] as? JsonObject ?: return@let

            (rPost["set"] as? JsonObject)?.let { post ->
                val msgs = mutableListOf<MessageEntity>()
                val rx = mutableListOf<ReactionEntity>()
                ingestPost(nest, post, msgs, rx)
                db.messages().upsertAll(msgs)
                if (rx.isNotEmpty()) {
                    db.reactions().clearForPost(nest, id)
                    db.reactions().upsertAll(rx)
                }
                // Reap any optimistic-local row for this same post so
                // our own sends stop showing as duplicates.
                msgs.firstOrNull { it.id == id && it.author == ourPatp }?.let { mine ->
                    db.messages().reapLocalTwin(nest, ourPatp, mine.sentMs)
                }
                // Only top-level posts from peers trigger a notification;
                // reply inserts come via the reply variant below.
                msgs.firstOrNull { it.id == id && it.parentId == null }
                    ?.takeIf { it.author != ourPatp }
                    ?.let { messageListener?.invoke(it, false) }
                return
            }
            if (rPost["set"] is JsonNull) {
                db.messages().softDelete(nest, id)
                db.reactions().clearForPost(nest, id)
                return
            }
            (rPost["reacts"] as? JsonObject)?.let { reacts ->
                db.reactions().clearForPost(nest, id)
                val rx = reacts.entries.mapNotNull { (author, emoji) ->
                    val e = emoji.jsonPrimitive.contentIfString() ?: return@mapNotNull null
                    ReactionEntity(nest, id, author, e)
                }
                if (rx.isNotEmpty()) db.reactions().upsertAll(rx)
                return
            }
            (rPost["essay"] as? JsonObject)?.let { essay ->
                val entity = toEntity(nest, id, essay)
                db.messages().upsert(entity)
                // Edits don't need a notification; only fresh posts should.
                return
            }
            (rPost["reply"] as? JsonObject)?.let { reply ->
                applyChannelReplyDelta(nest, parentId = id, reply)
                return
            }
        }
    }

    private suspend fun applyChannelReplyDelta(
        whom: String,
        parentId: String,
        reply: JsonObject,
    ) {
        val replyId = reply["id"]?.jsonPrimitive?.contentIfString() ?: return
        val rReply = reply["r-reply"] as? JsonObject ?: return

        (rReply["set"] as? JsonObject)?.let { full ->
            val essay = full["reply-essay"] as? JsonObject ?: return@let
            val entity = toReplyEntity(whom, parentId, replyId, essay)
            db.messages().upsert(entity)
            if (entity.author != ourPatp) {
                val parent = db.messages().getOne(whom, parentId)
                val replyToUs = parent?.author == ourPatp
                messageListener?.invoke(entity, replyToUs)
            }
            (full["seal"] as? JsonObject)?.get("reacts")?.let { reacts ->
                val obj = reacts as? JsonObject ?: return@let
                db.reactions().clearForPost(whom, replyId)
                val rx = obj.entries.mapNotNull { (author, emoji) ->
                    val e = emoji.jsonPrimitive.contentIfString() ?: return@mapNotNull null
                    ReactionEntity(whom, replyId, author, e)
                }
                if (rx.isNotEmpty()) db.reactions().upsertAll(rx)
            }
            return
        }
        if (rReply["set"] is JsonNull) {
            db.messages().softDelete(whom, replyId)
            db.reactions().clearForPost(whom, replyId)
            return
        }
        (rReply["reacts"] as? JsonObject)?.let { reacts ->
            db.reactions().clearForPost(whom, replyId)
            val rx = reacts.entries.mapNotNull { (author, emoji) ->
                val e = emoji.jsonPrimitive.contentIfString() ?: return@mapNotNull null
                ReactionEntity(whom, replyId, author, e)
            }
            if (rx.isNotEmpty()) db.reactions().upsertAll(rx)
        }
    }

    // ───────── activity / unreads ─────────

    private suspend fun bootstrapActivity(channel: UrbitChannel) {
        val body = channel.scry("activity", "/v4/activity")
        val obj = body as? JsonObject ?: return
        val rows = obj.entries.mapNotNull { (sourceKey, summary) ->
            toUnread(sourceKey, summary as? JsonObject ?: return@mapNotNull null)
        }
        if (rows.isNotEmpty()) db.unreads().upsertAll(rows)
    }

    private suspend fun applyActivityUpdate(obj: JsonObject) {
        (obj["activity"] as? JsonObject)?.let { map ->
            val rows = map.entries.mapNotNull { (key, summary) ->
                toUnread(key, summary as? JsonObject ?: return@mapNotNull null)
            }
            if (rows.isNotEmpty()) db.unreads().upsertAll(rows)
            return
        }
        (obj["read"] as? JsonObject)?.let { read ->
            val source = read["source"] as? JsonObject ?: return@let
            val summary = read["activity"] as? JsonObject ?: return@let
            val whom = sourceToWhom(source) ?: return@let
            toUnread(sourceKey = null, summary = summary, overrideWhom = whom)
                ?.let { db.unreads().upsert(it) }
            return
        }
        (obj["del"] as? JsonObject)?.let { source ->
            sourceToWhom(source)?.let { db.unreads().delete(it) }
            return
        }
    }

    /**
     * Convert a source-key string like "ship/~peer" / "club/0v..." /
     * "channel/chat/~host/name" + ActivitySummary JSON into an
     * UnreadEntity. Returns null for source kinds we don't surface
     * (groups, threads, base).
     */
    private fun toUnread(
        sourceKey: String?,
        summary: JsonObject,
        overrideWhom: String? = null,
    ): UnreadEntity? {
        val whom = overrideWhom
            ?: sourceKeyToWhom(sourceKey ?: return null)
            ?: return null
        val count = summary["count"]?.jsonPrimitive?.longOrNull?.toInt() ?: 0
        val notifyCount = summary["notify-count"]?.jsonPrimitive?.longOrNull?.toInt() ?: 0
        val recency = summary["recency"]?.jsonPrimitive?.longOrNull ?: 0L
        return UnreadEntity(
            whom = whom,
            count = count,
            notifyCount = notifyCount,
            recencyMs = recency,
        )
    }

    private fun sourceKeyToWhom(key: String): String? = when {
        key.startsWith("ship/") -> key.removePrefix("ship/")
        key.startsWith("club/") -> key.removePrefix("club/")
        key.startsWith("channel/") -> key.removePrefix("channel/")
        else -> null
    }

    private fun sourceToWhom(source: JsonObject): String? {
        (source["dm"] as? JsonObject)?.let { dm ->
            dm["ship"]?.jsonPrimitive?.contentIfString()?.let { return it }
            dm["club"]?.jsonPrimitive?.contentIfString()?.let { return it }
        }
        (source["channel"] as? JsonObject)?.let { ch ->
            ch["nest"]?.jsonPrimitive?.contentIfString()?.let { return it }
        }
        return null
    }

    /**
     * Poke %activity to mark a conversation read. Channels require the
     * enclosing group flag; for v1 we only mark DMs (ship / club).
     */
    suspend fun markRead(whom: String) {
        val ch = channel ?: return

        // Clear the badge locally immediately so the list flips the moment
        // the user enters the conversation. The server fact will confirm.
        db.unreads().upsert(
            UnreadEntity(
                whom = whom,
                count = 0,
                notifyCount = 0,
                recencyMs = System.currentTimeMillis(),
            )
        )

        val source = when {
            whom.startsWith("~") -> buildJsonObject {
                put("dm", buildJsonObject { put("ship", whom) })
            }
            whom.startsWith("0v") -> buildJsonObject {
                put("dm", buildJsonObject { put("club", whom) })
            }
            whom.startsWith("chat/") -> {
                val groupFlag = db.groups().channelGroupFor(whom)?.groupFlag
                if (groupFlag == null) {
                    Log.w(TAG, "markRead: no group flag for $whom; skipping poke")
                    return
                }
                buildJsonObject {
                    put("channel", buildJsonObject {
                        put("nest", whom)
                        put("group", groupFlag)
                    })
                }
            }
            else -> return
        }
        runCatching {
            ch.poke(
                app = "activity",
                mark = "activity-action",
                payload = buildJsonObject {
                    put("read", buildJsonObject {
                        put("source", source)
                        put("action", buildJsonObject {
                            put("all", buildJsonObject {
                                put("time", JsonNull)
                                put("deep", false)
                            })
                        })
                    })
                },
            )
        }.onFailure { Log.w(TAG, "markRead poke failed for $whom", it) }
    }

    // ───────── contacts ─────────

    /**
     * Load peer contact directory + self profile. Tlon's /v1/all scry
     * returns Record<ship, ContactFields> where fields are typed values
     * like `{type: 'text', value: 'Alice'}`. We pluck just nickname, bio
     * and avatar for the UI.
     */
    private suspend fun bootstrapContacts(channel: UrbitChannel) {
        val fresh = mutableListOf<ContactEntity>()

        runCatching { channel.scry("contacts", "/v1/all") }.getOrNull()?.let { body ->
            (body as? JsonObject)?.forEach { (ship, fields) ->
                (fields as? JsonObject)?.let { f ->
                    fresh.add(parseContact(ship, f))
                }
            }
        }

        runCatching { channel.scry("contacts", "/v1/self") }.getOrNull()?.let { body ->
            (body as? JsonObject)?.let { fresh.add(parseContact(ourPatp, it)) }
        }

        if (fresh.isNotEmpty()) {
            val merged = fresh.map { mergeContact(it) }
            db.contacts().upsertAll(merged)
        }
    }

    /**
     * Apply a single peer update from %contacts /v1/news. Handles the
     * `page` and `peer` response shapes; `wipe` (contact removal) isn't
     * surfaced locally for v1.
     */
    private suspend fun applyContactsNews(event: JsonObject) {
        (event["page"] as? JsonObject)?.let { page ->
            val kip = page["kip"]?.jsonPrimitive?.contentIfString() ?: return
            if (!kip.startsWith("~")) return
            val contact = page["contact"] as? JsonObject ?: return
            db.contacts().upsert(mergeContact(parseContact(kip, contact)))
            return
        }
        (event["peer"] as? JsonObject)?.let { peer ->
            val who = peer["who"]?.jsonPrimitive?.contentIfString() ?: return
            if (!who.startsWith("~")) return
            val contact = peer["contact"] as? JsonObject ?: return
            db.contacts().upsert(mergeContact(parseContact(who, contact)))
            return
        }
    }

    /**
     * Preserve `statusUpdatedMs` when the status hasn't changed; stamp
     * it to now when it has. Also merges avatar/bio/nickname with the
     * existing row so a partial update doesn't clear unrelated fields.
     */
    private suspend fun mergeContact(incoming: ContactEntity): ContactEntity {
        val existing = db.contacts().get(incoming.ship)
        val statusChanged = incoming.status.orEmpty() != existing?.status.orEmpty()
        return incoming.copy(
            nickname = incoming.nickname ?: existing?.nickname,
            bio = incoming.bio ?: existing?.bio,
            avatarUrl = incoming.avatarUrl ?: existing?.avatarUrl,
            statusUpdatedMs = when {
                incoming.status.isNullOrBlank() -> null
                statusChanged -> System.currentTimeMillis()
                else -> existing?.statusUpdatedMs
            },
        )
    }

    private fun parseContact(ship: String, fields: JsonObject): ContactEntity {
        fun textField(name: String): String? {
            val field = fields[name] as? JsonObject ?: return null
            if (field["type"]?.jsonPrimitive?.contentIfString() != "text") return null
            return field["value"]?.jsonPrimitive?.contentIfString()?.takeIf { it.isNotBlank() }
        }
        // Colors may arrive as either a plain text field ("#ff5050") or a
        // typed "color" field whose value is Urbit @ux hex ("0xff.5050").
        fun colorField(name: String): String? {
            val field = fields[name] as? JsonObject ?: return null
            val raw = field["value"]?.jsonPrimitive?.contentIfString()
                ?: return null
            return normalizeHexColor(raw)
        }
        return ContactEntity(
            ship = ship,
            nickname = textField("nickname"),
            bio = textField("bio"),
            avatarUrl = textField("avatar"),
            status = textField("status"),
            color = colorField("color"),
        )
    }

    /**
     * Normalizes various color encodings to `#RRGGBB` uppercase.
     *  - "#ff5050"     → "#FF5050"
     *  - "ff5050"      → "#FF5050"
     *  - "0xff.5050"   → "#FF5050"   (Urbit @ux)
     *  - "0xf.f505"    → "#0FF505"   (zero-padded to 6 hex digits)
     * Returns null if it can't make sense of the input.
     */
    private fun normalizeHexColor(raw: String): String? {
        val trimmed = raw.trim()
        val hex = trimmed
            .removePrefix("#")
            .removePrefix("0x")
            .replace(".", "")
            .lowercase()
        if (hex.isEmpty() || !hex.all { it in '0'..'9' || it in 'a'..'f' }) return null
        val padded = hex.padStart(6, '0').takeLast(6)
        return "#" + padded.uppercase()
    }

    // ───────── groups ─────────

    /**
     * Load group metadata + channel→group mapping. %groups /v2/groups
     * returns Record<flag, {meta?, channels?: Record<nest, {meta?}>}>.
     * We store title + image for each group and a nest→flag index so
     * list rows can pluck the enclosing group's image in O(1).
     */
    private suspend fun bootstrapGroups(channel: UrbitChannel) {
        val body = channel.scry("groups", "/v2/groups")
        val obj = body as? JsonObject ?: return

        val groups = mutableListOf<GroupEntity>()
        val channelGroups = mutableListOf<ChannelGroupEntity>()

        for ((flag, group) in obj) {
            val groupObj = group as? JsonObject ?: continue
            val meta = groupObj["meta"] as? JsonObject
            groups += GroupEntity(
                flag = flag,
                title = meta?.get("title")?.jsonPrimitive?.contentIfString()
                    ?.takeIf { it.isNotBlank() },
                image = meta?.get("image")?.jsonPrimitive?.contentIfString()
                    ?.takeIf { it.isNotBlank() },
            )
            val channels = groupObj["channels"] as? JsonObject ?: continue
            for ((nest, channel) in channels) {
                val channelObj = channel as? JsonObject
                val channelMeta = channelObj?.get("meta") as? JsonObject
                val channelTitle = channelMeta?.get("title")
                    ?.jsonPrimitive?.contentIfString()
                    ?.takeIf { it.isNotBlank() }
                channelGroups += ChannelGroupEntity(
                    nest = nest,
                    groupFlag = flag,
                    title = channelTitle,
                )
            }
        }
        if (groups.isNotEmpty()) db.groups().upsertGroups(groups)
        if (channelGroups.isNotEmpty()) db.groups().upsertChannelGroups(channelGroups)
    }

    // ───────── clubs ─────────

    /**
     * Load group-DM (club) metadata. %chat /clubs scry returns
     * Record<clubId, {hive, team, meta: {title, description, image, cover}}>.
     * We only surface the title; everything else lives server-side.
     */
    private suspend fun bootstrapClubs(channel: UrbitChannel) {
        val body = channel.scry("chat", "/clubs")
        val obj = body as? JsonObject ?: return
        val rows = obj.mapNotNull { (id, club) ->
            val clubObj = club as? JsonObject ?: return@mapNotNull null
            val meta = clubObj["meta"] as? JsonObject
            val title = meta?.get("title")?.jsonPrimitive?.contentIfString()
                ?.takeIf { it.isNotBlank() }
            ClubEntity(id = id, title = title)
        }
        if (rows.isNotEmpty()) db.clubs().upsertAll(rows)
    }

    // ───────── post ingest ─────────

    /**
     * Walks one post shape (seal + essay + replies) and appends to caller's
     * lists. The seal carries reactions on the parent, and a `replies` map
     * whose values are Reply shapes with their own seal + reply-essay.
     */
    private fun ingestPost(
        whom: String,
        post: JsonElement,
        messagesOut: MutableList<MessageEntity>,
        reactionsOut: MutableList<ReactionEntity>,
    ) {
        val obj = post as? JsonObject ?: return
        val seal = obj["seal"] as? JsonObject ?: return
        val id = seal["id"]?.jsonPrimitive?.contentIfString() ?: return
        val essay = obj["essay"] as? JsonObject ?: return
        messagesOut.add(toEntity(whom, id, essay))

        (seal["reacts"] as? JsonObject)?.forEach { (author, emoji) ->
            val e = emoji.jsonPrimitive.contentIfString() ?: return@forEach
            reactionsOut.add(ReactionEntity(whom, id, author, e))
        }

        (seal["replies"] as? JsonObject)?.forEach { (_, replyEl) ->
            val reply = replyEl as? JsonObject ?: return@forEach
            val replySeal = reply["seal"] as? JsonObject ?: return@forEach
            val replyId = replySeal["id"]?.jsonPrimitive?.contentIfString() ?: return@forEach
            val replyEssay = reply["reply-essay"] as? JsonObject ?: return@forEach
            messagesOut.add(toReplyEntity(whom, id, replyId, replyEssay))
            (replySeal["reacts"] as? JsonObject)?.forEach { (author, emoji) ->
                val e = emoji.jsonPrimitive.contentIfString() ?: return@forEach
                reactionsOut.add(ReactionEntity(whom, replyId, author, e))
            }
        }
    }

    private fun toEntity(whom: String, id: String, essay: JsonObject): MessageEntity {
        val author = essay["author"]?.jsonPrimitive?.contentIfString() ?: ""
        val sent = essay["sent"]?.jsonPrimitive?.longOrNull ?: 0L
        val kind = essay["kind"]?.jsonPrimitive?.contentIfString() ?: "/chat"
        val content = essay["content"] ?: JsonArray(emptyList())
        val merged = mergeBlobIntoContent(content, essay["blob"])
        val json = merged.toString()
        // Parse the story ahead of UI time. First-paint of a message in a
        // LazyColumn is what drives visible scroll jank — doing the
        // JSON→AnnotatedString conversion now (on Dispatchers.IO) means
        // StoryCache.partsFor is an instant map hit when the row first
        // composes.
        StoryCache.partsFor(id, json)
        return MessageEntity(
            whom = whom,
            id = id,
            author = author,
            sentMs = sent,
            contentJson = json,
            kind = kind,
        )
    }

    /**
     * Tlon stores file attachments in `essay.blob` — a JSON-string whose
     * contents decode to an array of `{type, version, fileUri, name,
     * mimeType, size}` entries. None of the normal story block types
     * exist for these yet, so we hoist each file entry into a synthetic
     * `{"block": {"file": {…}}}` prepended to the content. The story
     * renderer's unknown-block fallback picks this up and shows it as a
     * tappable LinkPreview with filename + size.
     */
    private fun mergeBlobIntoContent(
        content: JsonElement,
        blob: JsonElement?,
    ): JsonElement {
        val blobStr = blob?.let { el ->
            (el as? JsonPrimitive)?.contentIfString()
        } ?: return content
        if (blobStr.isBlank()) return content
        val parsed = runCatching { Json.parseToJsonElement(blobStr) }.getOrNull()
            ?: return content
        val arr = parsed as? JsonArray ?: return content
        if (arr.isEmpty()) return content
        val synthetic = buildJsonArray {
            for (entry in arr) {
                val o = entry as? JsonObject ?: continue
                val type = o["type"]?.jsonPrimitive?.contentIfString()
                if (type != "file") continue
                val uri = o["fileUri"]?.jsonPrimitive?.contentIfString()
                    ?: o["url"]?.jsonPrimitive?.contentIfString()
                    ?: continue
                add(
                    buildJsonObject {
                        put("block", buildJsonObject {
                            put("file", buildJsonObject {
                                put("url", uri)
                                o["name"]?.jsonPrimitive?.contentIfString()
                                    ?.let { put("name", it) }
                                o["size"]?.jsonPrimitive?.longOrNull
                                    ?.let { put("size", it) }
                                o["mimeType"]?.jsonPrimitive?.contentIfString()
                                    ?.let { put("mime", it) }
                            })
                        })
                    }
                )
            }
        }
        if (synthetic.isEmpty()) return content
        return buildJsonArray {
            synthetic.forEach { add(it) }
            (content as? JsonArray)?.forEach { add(it) }
        }
    }

    /**
     * Parse composer text into a Story. Each non-empty line becomes its
     * own verse with a break between them; within a line, Markdown
     * walks the text and emits `{bold: …}`, `{italics: …}`, `{code: …}`,
     * `{link: {href, content}}`, and `{ship: …}` inline spans.
     */
    private fun textToStory(text: String): JsonArray = buildJsonArray {
        val lines = text.split('\n')
        lines.forEachIndexed { idx, line ->
            val inline = Markdown.parseInlines(line)
            val finalInline = if (idx < lines.lastIndex) {
                buildJsonArray {
                    inline.forEach { add(it) }
                    add(buildJsonObject { put("break", JsonNull) })
                }
            } else inline
            add(buildJsonObject { put("inline", finalInline) })
        }
    }

    private fun buildEssay(content: JsonArray, sentMs: Long): JsonObject = buildJsonObject {
        put("content", content)
        put("author", ourPatp)
        put("sent", sentMs)
        put("kind", "/chat")
        put("meta", JsonNull)
        put("blob", JsonNull)
    }

    companion object {
        private const val TAG = "TlonChatRepo"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }

    /** Wraps a writ diff for %chat's chat-dm-action-2 mark. */
    private fun dmAction(peer: String, postId: String, delta: JsonObject): JsonObject =
        buildJsonObject {
            put("ship", peer)
            put("diff", buildJsonObject {
                put("id", postId)
                put("delta", delta)
            })
        }

    /**
     * Wraps a writ diff for %chat's chat-club-action-2 mark. The `uid: "0v4"`
     * literal matches what Tlon's web client sends — it's a throwaway
     * deduplication token the server doesn't currently rely on.
     */
    private fun clubAction(clubId: String, postId: String, delta: JsonObject): JsonObject =
        buildJsonObject {
            put("id", clubId)
            put("diff", buildJsonObject {
                put("uid", "0v4")
                put("delta", buildJsonObject {
                    put("writ", buildJsonObject {
                        put("id", postId)
                        put("delta", delta)
                    })
                })
            })
        }

    private fun channelAction(nest: String, action: JsonObject): JsonObject =
        buildJsonObject {
            put("channel", buildJsonObject {
                put("nest", nest)
                put("action", action)
            })
        }

    /** WritsDelta shape for the reply-add case. */
    private fun replyDelta(replyId: String, replyEssay: JsonObject): JsonObject =
        buildJsonObject {
            put("reply", buildJsonObject {
                put("id", replyId)
                put("meta", JsonNull)
                put("delta", buildJsonObject {
                    put("add", buildJsonObject {
                        put("reply-essay", replyEssay)
                        put("time", JsonNull)
                    })
                })
            })
        }

    /** Convert a reply-essay JsonObject into a MessageEntity tagged as a reply. */
    private fun toReplyEntity(
        whom: String,
        parentId: String,
        replyId: String,
        replyEssay: JsonObject,
    ): MessageEntity {
        val author = replyEssay["author"]?.jsonPrimitive?.contentIfString() ?: ""
        val sent = replyEssay["sent"]?.jsonPrimitive?.longOrNull ?: 0L
        val content = replyEssay["content"] ?: JsonArray(emptyList())
        val merged = mergeBlobIntoContent(content, replyEssay["blob"])
        val json = merged.toString()
        StoryCache.partsFor(replyId, json)
        return MessageEntity(
            whom = whom,
            id = replyId,
            author = author,
            sentMs = sent,
            contentJson = json,
            kind = "/chat",
            parentId = parentId,
        )
    }

    private fun JsonPrimitive.contentIfString(): String? =
        if (isString) content else content
}
