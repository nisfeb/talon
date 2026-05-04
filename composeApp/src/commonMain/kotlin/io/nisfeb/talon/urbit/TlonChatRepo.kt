// Diverges from production in three controlled ways so that this file
// can compile in commonMain (no Android, no app/-only types):
//
//  1. android.util.Log -> io.nisfeb.talon.util.Log (the expect/actual
//     facade in commonMain). Two production Log.d calls are folded to
//     Log.i because the facade omits `d` (see Log.kt KDoc).
//
//  2. The constructor takes (db, settingsSync: SettingsSync? = null)
//     instead of (db, aiSettings, dailyDigestSettings, rearmDailyDigest).
//     The production class constructs SettingsSync internally from those
//     three Android-only deps; commonMain instead receives an already-
//     constructed SettingsSync (interface, not class) -- or null on
//     desktop where %settings sync isn't yet wired. Stage F follow-up.
//
//  3. The `settingsSync` field is now the ctor parameter (nullable).
//     Internal callers in start()/applyEvent() are null-guarded.
//
// Keep this file in sync with the production TlonChatRepo whenever
// the production version changes; failing to do so will cause the
// composeApp Android build to behave differently from the production
// app/ build for any chat code that diverged.

@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.nisfeb.talon.urbit

import io.nisfeb.talon.util.Log
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
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
 *
 * **Single-use** — stop() calls scope.cancel(), which permanently
 * deactivates the SupervisorJob. A start() after stop() flips the
 * `started` flag back to true but `scope.launch { … }` no-ops on
 * a cancelled scope, leaving you with a silent dead repo. Callers
 * (composeApp's App() key-block, production's TalonApplication)
 * MUST construct a fresh instance per ship session, not reuse the
 * stopped one.
 */
class TlonChatRepo(
    private val db: AppDatabase,
    /**
     * Optional %settings sync surface. Production app/ Android passes
     * the real implementation (constructed from EncryptedSharedPreferences-
     * backed AiSettings/DailyDigestSettings); composeApp desktop passes
     * `null` until a desktop %settings bridge is added in Stage F.
     */
    val settingsSync: SettingsSync? = null,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /**
     * Scope for fire-and-forget ship pokes (folder reorder,
     * watchword exclude, etc.) that should complete even if the
     * caller's composition disposes mid-flight. Common case the
     * main `scope` doesn't cover: user drags a folder, then
     * rotates the device — composition disposes, the drag
     * handler's rememberCoroutineScope cancels, the push never
     * happens, ship has stale order. With pushScope the launch
     * still has a live owner.
     *
     * NOT cancelled on stop() — orphaned launches from the prior
     * ship complete on their own and are GC'd. Each repo instance
     * has its own pushScope; a new instance per ship doesn't
     * inherit pending pushes from the previous one (which is
     * correct: the previous ship's cookie is gone, those pushes
     * would 401).
     */
    val pushScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var started = false
    @Volatile private var channel: UrbitChannel? = null
    @Volatile private var http: OkHttpClient? = null
    @Volatile private var ourPatp: String = ""
    @Volatile private var sessionJob: Job? = null
    @Volatile private var lastEventMs: Long = 0L

    // Admin-groups cache: populated by refreshAdminGroups(), consumed
    // by the Administration screen. Fetching the full per-group state
    // is 30+ scries so we keep the last result around and only refresh
    // when it's older than ADMIN_CACHE_TTL_MS or the caller asks for a
    // forced reload.
    private val _adminGroups = MutableStateFlow<List<AdminGroup>?>(null)
    val adminGroupsFlow: StateFlow<List<AdminGroup>?> = _adminGroups.asStateFlow()
    @Volatile private var adminGroupsFetchedMs: Long = 0L
    private val adminGroupsMutex = Mutex()

    /**
     * The chat the user is currently viewing, if any. Used to suppress
     * unread-badge bumps for that whom — if they're looking at it,
     * any new messages are effectively already read. Set from UI
     * lifecycle hooks (DmChatScreen mount/unmount).
     */
    @Volatile private var openWhom: String? = null

    fun setOpenChat(whom: String?) {
        val prev = openWhom
        openWhom = whom
        if (prev != null && prev != whom) {
            // Exiting: final mark-read to catch anything that arrived
            // between the last activity event and now.
            scope.launch { runCatching { markRead(prev) } }
        }
        if (whom != null && prev != whom) {
            scope.launch { runCatching { markRead(whom) } }
        }
    }

    // settingsSync is now a constructor parameter (nullable). The production
    // app/ class still constructs its own internally and exposes a
    // non-nullable property for the 37 UI callers reading repo.settingsSync.X
    // directly; commonMain uses the interface, and external commonMain
    // callers only ever invoke this repo's own methods, which null-guard.

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
        scope.launch {
            // One-shot cleanup for anyone whose DM history was doubled
            // by the applyChatDelta dotted-id bug. Idempotent: on a
            // clean DB it's a no-op.
            runCatching { dedupeDottedIds() }
                .onFailure { Log.w(TAG, "dotted-id dedupe failed", it) }
            runSessionLoop(session)
        }
    }

    /**
     * Walk every row whose id / postId is dot-grouped and collapse it
     * onto its undotted twin (or rename in place if none exists). See
     * [DottedIdDedupe] for the planner. Cheap — runs once per boot,
     * the `LIKE '%.%'` scan is indexed by the primary key path and
     * skips the common undotted case entirely.
     */
    private suspend fun dedupeDottedIds() {
        val msgRows = db.messages().findDottedIdRows()
        if (msgRows.isNotEmpty()) {
            // Pre-resolve which undotted twins already exist so the
            // pure planner can run without suspend callbacks.
            val existing = mutableSetOf<Pair<String, String>>()
            for (row in msgRows) {
                val cleanId = undotAtom(row.id)
                if (cleanId == row.id) continue
                if (db.messages().getOne(row.whom, cleanId) != null) {
                    existing += row.whom to cleanId
                }
            }
            val ops = planMessageDedupe(msgRows, existing)
            var renamed = 0
            var dropped = 0
            for (op in ops) when (op) {
                is DedupeOp.Rename -> {
                    db.messages().upsert(op.to)
                    db.messages().hardDelete(op.from.whom, op.from.id)
                    renamed++
                }
                is DedupeOp.Drop -> {
                    db.messages().hardDelete(op.whom, op.dottedId)
                    dropped++
                }
            }
            Log.i(TAG, "dedupe messages: $renamed renamed, $dropped dropped")
        }
        // Reactions are keyed on (whom, postId, author); @Upsert
        // handles the merge case atomically, so we don't need the
        // rename/drop planner. For each dotted row, upsert the
        // undotted twin (overwrites if it exists) and drop the dotted.
        val rxRows = db.reactions().findDottedPostIdRows()
        for (row in rxRows) {
            val cleanId = undotAtom(row.postId)
            if (cleanId == row.postId) continue
            db.reactions().upsert(row.copy(postId = cleanId))
            db.reactions().deleteOne(row.whom, row.postId, row.author)
        }
        if (rxRows.isNotEmpty()) {
            Log.i(TAG, "dedupe reactions: ${rxRows.size} dotted rows merged")
        }
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
        // Pagination markers are per-(channel, ship). A new channel
        // means the ship may surface old history we previously couldn't
        // reach (e.g. backfill arrived during the disconnect). Drop the
        // markers so loadOlder retries them on demand.
        paginationExhausted.clear()

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
        // Groups live-updates: catches new channels added to existing
        // groups, meta edits, etc. Without this, channel-add is only
        // picked up on reconnect (bootstrap re-scry).
        runCatching { ch.subscribe("groups", "/v1/groups") }
            .onFailure { Log.e(TAG, "groups subscribe failed", it) }

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
            runCatching { bootstrapChannelOrders(ch) }
                .onFailure { Log.e(TAG, "channel orders scry failed", it) }
        }

        // Settings sync — scries our desk and also subscribes so any
        // other device's changes stream in. Only need to do this on
        // firstRun; the subscription re-establishes automatically once
        // we attach the new channel instance below.
        // commonMain: skip entirely when no SettingsSync was supplied
        // (e.g. desktop until %settings bridge is wired in Stage F).
        settingsSync?.attach(ch)
        if (settingsSync != null) {
            if (firstRun) {
                runCatching { settingsSync.bootstrap() }
                    .onFailure { Log.e(TAG, "settings bootstrap failed", it) }
            } else {
                runCatching { ch.subscribe("settings", "/desk/${SettingsSync.DESK}") }
                    .onFailure { Log.e(TAG, "settings subscribe failed", it) }
            }
        }

        // Watchdog: if we don't see an event for 90s, the SSE is likely
        // a zombie (doze-frozen or server-side dropped). Cancel the
        // collect job so runSessionLoop reconnects.
        val collectJob = launch {
            ch.events().collect { event ->
                lastEventMs = System.currentTimeMillis()
                runCatching { applyEvent(event.body) }
                    .onFailure {
                        // Rethrow cancellation so structured-concurrency
                        // children of applyEvent can propagate properly;
                        // without this any future child coroutine that
                        // gets cancelled would silently fall through and
                        // the caller would see a "stuck" UI.
                        if (it is kotlinx.coroutines.CancellationException) throw it
                        Log.w(TAG, "apply event failed", it)
                    }
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
     *
     * Debounced — Android's `ON_START` lifecycle event sometimes fires
     * twice in rapid succession (briefly-backgrounded activities, dialog
     * dismissals), and chaining two reconnects within the same second
     * produced a "Job was cancelled" cascade where the freshly-opened
     * channel's bootstrap scries were torn down by the second
     * reconnect before they completed. Coalesce repeats inside a
     * 3-second window — well under the 90s watchdog interval, so
     * legitimate doze-recovery reconnects still go through.
     */
    fun forceReconnect() {
        val now = System.currentTimeMillis()
        if (now - lastReconnectMs < FORCE_RECONNECT_DEBOUNCE_MS) {
            Log.i(TAG, "forceReconnect skipped (recent)")
            return
        }
        lastReconnectMs = now
        Log.i(TAG, "forceReconnect requested")
        sessionJob?.cancel()
    }
    @Volatile private var lastReconnectMs: Long = 0L

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
        // Clear pagination markers so a future repo instance for the
        // same ship doesn't inherit stale "history exhausted" state.
        // (Each ship gets a new TlonChatRepo, but a forceReconnect
        // recovers the same instance and may receive new old history
        // from the ship — without this, loadOlder would silently
        // refuse forever.)
        paginationExhausted.clear()
    }

    // ───────── sends ─────────

    /** Plain text message. Routes by whom prefix. Returns minted post id. */
    suspend fun send(whom: String, text: String): String =
        postContent(whom, textToStory(text))

    /**
     * Send a message quoting another post. Prepends a cite block to
     * the user's text (which can be empty) so recipients see the
     * quoted post above the new message. Currently only supported
     * when both messages are in the same channel — DMs use a
     * different referent shape that we don't yet build.
     */
    suspend fun sendQuote(
        whom: String,
        text: String,
        quotedNest: String,
        quotedPostId: String,
    ): String {
        val quoteBlock = buildJsonObject {
            put("block", buildJsonObject {
                put("cite", buildJsonObject {
                    put("chan", buildJsonObject {
                        put("nest", quotedNest)
                        put("where", "/msg/$quotedPostId")
                    })
                })
            })
        }
        val content = buildJsonArray {
            add(quoteBlock)
            if (text.isNotBlank()) {
                textToStory(text).forEach { add(it) }
            }
        }
        return postContent(whom, content)
    }

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

    /**
     * Post a notebook entry to a `diary/~host/slug` channel. Title is
     * required; image (cover URL) optional. Body is plain markdown —
     * parsed into block-level Verses via [MarkdownBlocks].
     */
    suspend fun sendNotebookPost(
        nest: String,
        title: String,
        image: String,
        bodyMarkdown: String,
    ): String {
        require(nest.startsWith("diary/")) { "not a diary channel: $nest" }
        val content = MarkdownBlocks.toStory(bodyMarkdown)
        val meta = buildJsonObject {
            put("title", title)
            put("image", image)
            put("description", "")
            put("cover", "")
        }
        return postContent(nest, content, kind = "/diary", meta = meta)
    }

    /**
     * Post a gallery entry to a `heap/~host/slug` channel. Gallery
     * posts are single-focus: typically one image, link, or short
     * text. The caller supplies the content array directly.
     */
    suspend fun sendGalleryPost(
        nest: String,
        content: JsonArray,
    ): String {
        require(nest.startsWith("heap/")) { "not a heap channel: $nest" }
        return postContent(nest, content, kind = "/heap", meta = null)
    }

    /** Routes content → appropriate chat / club / channel poke + DB write. */
    private suspend fun postContent(
        whom: String,
        content: JsonArray,
        kind: String = "/chat",
        meta: JsonObject? = null,
    ): String {
        val ch = channel ?: error("not connected")
        val sent = System.currentTimeMillis()
        val da = UrbitTime.unixMsToDa(sent)
        // %channels mints post ids with its own entropy (not purely a
        // function of essay.sent), so we can't predict the server id
        // locally. Use a sentinel "local_<da>" id for the optimistic
        // insert and reap it when the SSE echo's server-id row arrives
        // for the same (whom, author, sentMs).
        // Same local-sentinel rule as chat: %channels mints its own
        // id for diary/heap posts, so we can't predict it locally.
        val id = if (
            whom.startsWith("chat/") ||
            whom.startsWith("diary/") ||
            whom.startsWith("heap/")
        ) "local_${da}"
        else UrbitTime.formatPostId(ourPatp, da)
        val essay = buildEssay(content, sent, kind = kind, meta = meta)
        val addDelta = buildJsonObject {
            put("add", buildJsonObject {
                put("essay", essay)
                put("time", JsonNull)
            })
        }
        // Channel-chat posts get a status="pending" optimistic insert;
        // DMs and clubs leave status null. The SSE poke-ack listener
        // flips pending → failed on NACK; a successful poke leaves the
        // pending row in place until the server-id echo reaps it.
        val isChannel = whom.startsWith("chat/") ||
            whom.startsWith("diary/") ||
            whom.startsWith("heap/")
        val initialStatus = if (isChannel) "pending" else null
        db.messages().upsert(toEntity(whom, id, essay).copy(status = initialStatus))
        val pokeId = when {
            whom.startsWith("~") -> ch.poke(
                app = "chat", mark = "chat-dm-action-2",
                payload = dmAction(whom, id, addDelta),
            )
            whom.startsWith("0v") -> ch.poke(
                app = "chat", mark = "chat-club-action-2",
                payload = clubAction(whom, id, addDelta),
            )
            isChannel -> ch.poke(
                app = "channels", mark = "channel-action-2",
                payload = channelAction(whom, buildJsonObject {
                    put("post", buildJsonObject { put("add", essay) })
                }),
            )
            else -> error("unsupported whom: $whom")
        }
        if (isChannel) pendingChannelPokes[pokeId] = whom to id
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
    /**
     * Set a local pet-name overlay for another ship via %contacts. This
     * is the kip-scoped edit — the overlay lives on our ship only and
     * never reaches the named peer.
     */
    suspend fun setPetName(ship: String, name: String) {
        val ch = channel ?: error("not connected")
        require(ship.startsWith("~")) { "setPetName: $ship isn't a patp" }
        ch.poke(
            app = "contacts",
            mark = "contact-action-1",
            payload = buildJsonObject {
                put("edit", buildJsonObject {
                    put("kip", ship)
                    put("contact", buildJsonObject {
                        put("nickname", buildJsonObject {
                            put("type", "text")
                            put("value", name)
                        })
                    })
                })
            },
        )
        // Optimistic local merge so the change shows without a round-trip.
        val current = db.contacts().get(ship)
        db.contacts().upsert(
            ContactEntity(
                ship = ship,
                nickname = name.takeIf { it.isNotBlank() },
                bio = current?.bio,
                avatarUrl = current?.avatarUrl,
                status = current?.status,
                statusUpdatedMs = current?.statusUpdatedMs,
                color = current?.color,
            )
        )
    }

    // ───────── group administration ─────────

    // commonMain: the nested AdminGroup/AdminMember data classes live in
    // top-level GroupAdmin.kt (see KDoc there). adminGroupsFlow and
    // helpers reference the top-level types. The production app/ class
    // keeps its nested copies until Stage F decomposes TlonChatRepo
    // there too.

    /** One inbound invitation to a group, surfaced in the Invites UI. */
    data class InviteSummary(
        val flag: String,
        val inviter: String?,
        val title: String?,
        val description: String?,
        val image: String?,
        val cover: String?,
        val memberCount: Int?,
    )

    /**
     * Return cached admin groups, refreshing in the background if the
     * cache is stale or empty. Emits into [adminGroupsFlow] — the UI
     * collects from the flow so the cached list paints instantly and
     * the fresh list swaps in when it lands.
     *
     * @param force bypass the TTL check (pull-to-refresh)
     */
    suspend fun refreshAdminGroups(force: Boolean = false) {
        val fresh = System.currentTimeMillis() - adminGroupsFetchedMs < ADMIN_CACHE_TTL_MS
        if (!force && fresh && _adminGroups.value != null) return
        adminGroupsMutex.withLock {
            // Re-check after acquiring — another caller may have
            // refreshed while we were waiting on the mutex.
            val nowFresh = System.currentTimeMillis() - adminGroupsFetchedMs < ADMIN_CACHE_TTL_MS
            if (!force && nowFresh && _adminGroups.value != null) return
            runCatching { fetchAdminGroupsLive() }
                .onSuccess {
                    _adminGroups.value = it
                    adminGroupsFetchedMs = System.currentTimeMillis()
                }
                .onFailure { Log.w(TAG, "refreshAdminGroups failed", it) }
        }
    }

    /**
     * Scry every group the ship belongs to and filter down to ones
     * where the logged-in user is in the admin sect. Prefer
     * [refreshAdminGroups]+[adminGroupsFlow] for UI use — this is the
     * raw one-shot fetch.
     */
    suspend fun fetchAdminGroupsLive(): List<AdminGroup> {
        val ch = channel ?: error("not connected")
        val body = ch.scry("groups", "/v2/groups") as? JsonObject ?: return emptyList()
        val me = ourPatp
        Log.i(TAG, "fetchAdminGroups: ${body.size} total groups, me=$me")
        // The `/v2/groups` scry can return a lightweight listing where
        // `fleet`/`bloc` are absent. Re-scry each flag individually via
        // `/v2/groups/<flag>` to get the full group state every time.
        //
        // Fan the per-flag scries out concurrently — sequential
        // `for (flag in body) { scry … }` was a sentinel-bug for the
        // Administration screen: a user in N groups paid N round-trips
        // back-to-back (3-15s of pure wait for an active member).
        // Semaphore caps in-flight requests at 8 so we don't blow past
        // OkHttp's per-host pool while still getting near-linear
        // speedup. coroutineScope { } means a single failure cancels
        // the rest cleanly; the per-flag runCatching keeps one
        // returned-null group from torpedoing the whole list.
        val flags = body.keys.toList()
        val gate = Semaphore(permits = 8)
        val parsed = coroutineScope {
            flags.map { flag ->
                async {
                    gate.acquire()
                    try {
                        val full = runCatching {
                            ch.scry("groups", "/v2/groups/$flag") as? JsonObject
                        }.getOrNull()
                        if (full == null) {
                            Log.w(TAG, "  $flag: full scry returned null, skipping")
                            null
                        } else {
                            flag to full
                        }
                    } finally {
                        gate.release()
                    }
                }
            }.awaitAll()
        }.filterNotNull()

        val out = ArrayList<AdminGroup>(parsed.size)
        for ((flag, full) in parsed) {
            val g = parseAdminGroup(flag, full)
            val host = flag.substringBefore('/')
            val isHost = host == me
            val memberSects = g.members.firstOrNull { it.ship == me }?.sects.orEmpty()
            val amAdmin = isHost || g.adminSects.any { it in memberSects } ||
                "admin" in memberSects
            if (amAdmin) out.add(g)
        }
        return out.sortedBy { (it.title ?: it.flag).lowercase() }
    }

    /**
     * Re-fetch a single group's admin view. Cheaper than re-scrying
     * the whole directory after a poke.
     */
    suspend fun fetchGroupAdmin(flag: String): AdminGroup? {
        val ch = channel ?: error("not connected")
        val body = ch.scry("groups", "/v2/groups/$flag") as? JsonObject ?: return null
        return parseAdminGroup(flag, body)
    }

    // parseAdminGroup extracted to GroupAdminParser.kt for testability.

    /**
     * Create a new group hosted by our ship. Mirrors Tlon's
     * createDefaultGroup: spins up a group with one "General" chat
     * channel. Returns the new group's flag (`~host/slug`).
     */
    suspend fun createGroup(title: String, description: String = ""): String {
        val ch = channel ?: error("not connected")
        val slug = "v" + randomBase32(7)
        val groupId = "$ourPatp/$slug"
        val channelSlug = "v" + randomBase32(7)
        val channelId = "chat/$ourPatp/$channelSlug"
        val body = buildJsonObject {
            put("groupId", groupId)
            put("meta", buildJsonObject {
                put("title", title)
                put("description", description)
                put("image", "")
                put("cover", "")
            })
            put("guestList", buildJsonArray { })
            put("channels", buildJsonArray {
                add(buildJsonObject {
                    put("channelId", channelId)
                    put("meta", buildJsonObject {
                        put("title", "General")
                        put("description", "")
                        put("image", "")
                        put("cover", "")
                    })
                })
            })
        }
        ch.runThread(
            desk = "groups",
            inputMark = "group-create-thread",
            threadName = "group-create-1",
            outputMark = "group-ui-2",
            body = body,
        )
        // Invalidate the admin-groups cache so the new group appears
        // on the next Administration screen open.
        adminGroupsFetchedMs = 0L
        return groupId
    }

    private fun randomBase32(length: Int): String {
        val chars = "0123456789abcdefghijklmnopqrstuv"
        return (1..length).map { chars.random() }.joinToString("")
    }

    /**
     * Create a new channel inside a group. `kind` is the agent prefix:
     * `chat`, `diary`, or `heap` — matching Tlon's wire names. Returns
     * the new channel's nest (`<kind>/<host>/<slug>`).
     */
    suspend fun createChannel(
        groupFlag: String,
        kind: String,
        title: String,
        description: String = "",
    ): String {
        require(kind in setOf("chat", "diary", "heap")) { "unknown channel kind: $kind" }
        val ch = channel ?: error("not connected")
        val slug = "v" + randomBase32(7)
        val nest = "$kind/$ourPatp/$slug"
        ch.poke(
            app = "channels",
            mark = "channel-action-2",
            payload = buildJsonObject {
                put("create", buildJsonObject {
                    put("kind", kind)
                    put("group", groupFlag)
                    put("name", slug)
                    put("title", title)
                    put("description", description)
                    put("meta", JsonNull)
                    put("readers", buildJsonArray { })
                    put("writers", buildJsonArray { })
                })
            },
        )
        adminGroupsFetchedMs = 0L
        return nest
    }

    /** Update a group's title/description/image/cover via %meta poke. */
    suspend fun updateGroupMeta(
        flag: String,
        title: String,
        description: String,
        image: String,
        cover: String,
    ) {
        pokeAGroup(flag, aGroupMetaUpdate(title, description, image, cover))
    }

    /**
     * Invite one or more ships to a group. Works for all privacy
     * levels — the ship gets an invite token in `admissions.invited`.
     */
    suspend fun inviteToGroup(flag: String, ship: String): Boolean {
        val ch = channel ?: error("not connected")
        ch.poke(
            app = "groups",
            mark = "group-action-4",
            payload = groupAction4InviteAdd(flag, ship),
        )
        return true
    }

    /** Remove a ship from the group (kick). */
    suspend fun kickFromGroup(flag: String, ship: String) {
        pokeAGroup(flag, aGroupSeatDel(ship))
    }

    /** Ban a ship from re-joining. */
    suspend fun banFromGroup(flag: String, ship: String): Boolean {
        pokeAGroup(flag, aGroupBanAdd(ship))
        return true
    }

    /** Remove a ship from the ban list. */
    suspend fun unbanFromGroup(flag: String, ship: String): Boolean {
        pokeAGroup(flag, aGroupBanDel(ship))
        return true
    }

    /**
     * Toggle a role on a single member via `seat.a-seat.{add,del}-roles`.
     */
    suspend fun setMemberRole(flag: String, ship: String, role: String, add: Boolean) {
        val diff = if (add) aGroupSeatAddRole(ship, role) else aGroupSeatDelRole(ship, role)
        pokeAGroup(flag, diff)
    }

    /**
     * Cache of inbound group invites. Populated by [refreshInvites]
     * and consumed by the Invites screen. null = never loaded.
     */
    private val _invites = MutableStateFlow<List<InviteSummary>?>(null)
    val invitesFlow: StateFlow<List<InviteSummary>?> = _invites.asStateFlow()

    /**
     * Try a series of candidate scry paths to find inbound invites.
     * The renamed agent dropped `/gangs` (404); the new path is
     * unknown until we probe. First hit wins; all attempts are logged
     * so we can pin down the working path from a single round-trip.
     */
    /**
     * Fetch inbound invites from the `groups-ui /v7/init` scry and
     * filter to foreigns that have at least one valid invite. The
     * init response also contains everything else the client needs,
     * but we only read `foreigns` here.
     */
    suspend fun refreshInvites() {
        val ch = channel ?: error("not connected")
        val body = runCatching {
            ch.scry("groups-ui", "/v7/init") as? JsonObject
        }.onFailure { Log.w(TAG, "scry groups-ui/v7/init failed", it) }.getOrNull()
        if (body == null) { _invites.value = emptyList(); return }
        val foreigns = body["foreigns"] as? JsonObject
        if (foreigns == null) {
            Log.w(TAG, "refreshInvites: no foreigns in init response, keys=${body.keys}")
            _invites.value = emptyList()
            return
        }
        Log.i(TAG, "refreshInvites: ${foreigns.size} foreigns")
        val out = mutableListOf<InviteSummary>()
        for ((flag, foreign) in foreigns) {
            val f = foreign as? JsonObject ?: continue
            // invites is an array of {ship, token, valid, ...}; keep
            // only foreigns with at least one valid invite.
            val invites = f["invites"] as? JsonArray ?: continue
            val firstValid = invites.asSequence()
                .mapNotNull { it as? JsonObject }
                .firstOrNull { (it["valid"] as? JsonPrimitive)?.content == "true" }
                ?: continue
            val inviter = firstValid["ship"].asStr()
            val preview = f["preview"] as? JsonObject
            val meta = preview?.get("meta") as? JsonObject
            fun metaStr(k: String) = meta?.get(k).asStr()
                ?.takeIf { it.isNotBlank() }
            out += InviteSummary(
                flag = flag,
                inviter = inviter,
                title = metaStr("title"),
                description = metaStr("description"),
                image = metaStr("image"),
                cover = metaStr("cover"),
                memberCount = null,
            )
        }
        _invites.value = out.sortedBy { (it.title ?: it.flag).lowercase() }
    }

    /** Accept an inbound group invite via `group-join`. */
    suspend fun acceptInvite(flag: String) {
        val ch = channel ?: error("not connected")
        ch.poke(
            app = "groups",
            mark = "group-join",
            payload = buildJsonObject {
                put("flag", flag)
                put("join-all", true)
            },
        )
        _invites.value = _invites.value?.filterNot { it.flag == flag }
    }

    /** Reject an inbound group invite via `invite-decline`. */
    suspend fun rejectInvite(flag: String) {
        val ch = channel ?: error("not connected")
        ch.poke(
            app = "groups",
            mark = "invite-decline",
            payload = JsonPrimitive(flag),
        )
        _invites.value = _invites.value?.filterNot { it.flag == flag }
    }

    /**
     * Request access to a group we aren't a member of. Public groups
     * auto-accept; private groups surface the request to admins; for
     * secret groups this usually fails — matches Tlon's
     * `requestGroupInvitation`.
     */
    suspend fun knockGroup(flag: String) {
        val ch = channel ?: error("not connected")
        ch.poke(
            app = "groups",
            mark = "group-knock",
            payload = JsonPrimitive(flag),
        )
    }

    /**
     * Leave a group we're a member of. Mirrors tlon-apps'
     * `leaveGroup`: poke %groups with `group-leave`, payload is the
     * flag string. The ship side handles cleanup; channels and
     * unreads drain via the next %activity / %channels delta.
     */
    suspend fun leaveGroup(flag: String) {
        val ch = channel ?: error("not connected")
        ch.poke(
            app = "groups",
            mark = "group-leave",
            payload = JsonPrimitive(flag),
        )
    }

    /**
     * Fire markRead for every whom in [whoms] in parallel. Used by
     * the long-press "Mark all read" affordance — bulk-clearing a
     * backlog (typical right after a fresh-install pulls the ship's
     * stale activity state) sequentially would take ~retry_budget × N
     * seconds; parallel batches them under one second.
     *
     * markRead itself swallows transient errors and retries; this
     * helper only ensures each whom gets its own attempt.
     */
    suspend fun markAllRead(whoms: Collection<String>) {
        if (whoms.isEmpty()) return
        kotlinx.coroutines.coroutineScope {
            whoms.forEach { whom ->
                launch { runCatching { markRead(whom) } }
            }
        }
    }

    /**
     * Revoke a token-based invite by deleting the token from
     * `admissions.tokens` — ships in `admissions.invited` all have
     * a token; the map maps ship → token.
     */
    suspend fun revokeTokenInvite(flag: String, token: String) {
        pokeAGroup(flag, aGroupTokenDel(token))
    }

    /** Revoke a direct (no-token) invite via `entry.pending.a-pending.del`. */
    suspend fun revokeDirectInvite(flag: String, ship: String) {
        pokeAGroup(flag, aGroupPendingDel(ship))
    }

    /** Accept a join request: `ask` → `approve`. */
    suspend fun approveRequest(flag: String, ship: String) {
        pokeAGroup(flag, aGroupAskResolve(ship, approve = true))
    }

    /** Deny a join request: `ask` → `deny`. */
    suspend fun denyRequest(flag: String, ship: String) {
        pokeAGroup(flag, aGroupAskResolve(ship, approve = false))
    }

    /**
     * Send a `group-action-4` poke wrapping the given `a-group` diff
     * for the target group. All admin actions (meta, seat, entry,
     * role) go through this helper.
     */
    private suspend fun pokeAGroup(flag: String, aGroup: JsonObject) {
        val ch = channel ?: error("not connected")
        ch.poke(
            app = "groups",
            mark = "group-action-4",
            payload = groupAction4(flag, aGroup),
        )
    }

    suspend fun updateProfile(
        nickname: String? = null,
        bio: String? = null,
        avatarUrl: String? = null,
        status: String? = null,
        /** Color as `#RRGGBB`; null = don't change, empty = clear. */
        color: String? = null,
    ) {
        val ch = channel ?: error("not connected")
        // Modern %contacts uses `contact-action-1` with a kip-scoped
        // typed-field map (matches what `parseContact` reads back —
        // %text for nickname/bio/status, %look for avatar, %tint for
        // color). The legacy `contact-action` mark cast-failed on the
        // user's ship the moment any edit list contained `color` or
        // `avatar`, silently nacking the whole save (HTTP 200 +
        // SSE poke-nack with `gall: poke-as cast fail`).
        val contactFields = buildJsonObject {
            nickname?.let {
                put("nickname", buildJsonObject {
                    put("type", "text")
                    put("value", it)
                })
            }
            bio?.let {
                put("bio", buildJsonObject {
                    put("type", "text")
                    put("value", it)
                })
            }
            status?.let {
                put("status", buildJsonObject {
                    put("type", "text")
                    put("value", it)
                })
            }
            avatarUrl?.let {
                put("avatar", buildJsonObject {
                    put("type", "look")
                    put("value", it)
                })
            }
            color?.let {
                put("color", buildJsonObject {
                    put("type", "tint")
                    put("value", toUrbitHexColor(it))
                })
            }
        }
        if (contactFields.isEmpty()) return
        // Optimistic local update FIRST so the UI reflects the edit
        // before the poke round-trips. Merge with any existing row so
        // fields the caller didn't supply stay intact. Stamp
        // statusUpdatedMs when the status actually changes so the feed
        // reorders. (Sequencing the poke after the upsert was making
        // the status feed feel laggy on slow links.)
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
        // Per tlon-apps desk/lib/contacts/json-1.hoon's `++action`:
        //   self+contact            <- own profile edit (this code path)
        //   edit+(ot kip+kip contact+contact ~)  <- pet-name overlay for ANOTHER ship
        // setPetName above uses `edit` because that's a kip-scoped overlay.
        // For the user's own profile, the discriminator is `self`, with the
        // contact object directly as the value (no kip / contact wrapper).
        ch.poke(
            app = "contacts",
            mark = "contact-action-1",
            payload = buildJsonObject {
                put("self", contactFields)
            },
        )
    }

    /**
     * `#FF5050` → `ff.5050` for the JSON `tint` value.
     *
     * The hoon json-1 mark decodes a tint as `(slav %ux (cat 3 '0x' s))` —
     * it prepends `0x` itself before parsing as @ux. So the value we send
     * must NOT carry a `0x` prefix; the cat would otherwise produce
     * `0x0xff.5050` and slav would fail. Dot grouping is optional but
     * keeps roundtripped values matching the form `parseContact` reads.
     */
    private fun toUrbitHexColor(hex: String): String {
        val stripped = hex.trim().removePrefix("#").lowercase()
        val padded = stripped.padStart(6, '0').takeLast(6)
        return padded.substring(0, 2) + "." + padded.substring(2, 6)
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
        /** Post id of the message the event is about — used for chat
         *  scroll-to or thread anchoring on tap. */
        val postId: String? = null,
        /** When the event is about a reply, the parent post id.
         *  Tap routes into ThreadScreen anchored on [postId]. */
        val parentPostId: String? = null,
    )

    suspend fun fetchActivityFeed(): List<ActivityFeedItem> {
        val ch = channel ?: error("not connected")
        val body = ch.scry("activity", "/v5/feed/init/30") as? JsonObject
            ?: return emptyList()
        val all = body["all"] as? JsonArray ?: return emptyList()
        val items = mutableListOf<ActivityFeedItem>()
        for (bundleEl in all) {
            val bundle = bundleEl as? JsonObject ?: continue
            val sourceKey = bundle["source-key"].asStr()
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
                val author = eventObj["mention-author"].asStr()
                    ?: eventObj["author"].asStr()
                    ?: (eventObj["key"] as? JsonObject)?.get("id").asStr()
                        ?.substringBefore('/')
                val content = eventObj["content"]?.let { it.toString() }
                val timeStr = wrap["time"].asStr() ?: ""
                val sentMs = parseEventTimeMs(timeStr, eventObj)

                // Post + parent ids power tap-to-deep-link into the
                // exact reply / chat scroll-to. Pulled out into the
                // pure parseActivityEventTarget helper so wire-shape
                // drift surfaces in tests.
                val target = parseActivityEventTarget(tag, eventObj)

                // Normalize ids to the format our DB uses for the
                // resolved whom — channel tables key on bare `<da>`,
                // DMs/clubs on `~author/<da>`. Without this, channel
                // thread deep-links can never find the parent locally.
                items.add(
                    ActivityFeedItem(
                        kind = label,
                        author = author,
                        whom = sourceWhom,
                        contentJson = content,
                        sentMs = sentMs,
                        title = title,
                        postId = canonicalPostIdForWhom(sourceWhom, target.postId),
                        parentPostId = canonicalPostIdForWhom(sourceWhom, target.parentPostId),
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
        val keyTime = (eventObj["key"] as? JsonObject)?.get("time").asStr()
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
        val id = seal["id"].asStr() ?: return null
        val essay = body["essay"] as? JsonObject ?: return null
        val entity = toEntity(nest, id, essay)
        db.messages().upsert(entity)
        (seal["reacts"] as? JsonObject)?.let { reacts ->
            db.reactions().clearForPost(nest, id)
            val rx = reacts.entries.mapNotNull { (author, emoji) ->
                val e = emoji.asStr() ?: return@mapNotNull null
                ReactionEntity(nest, id, author, e)
            }
            if (rx.isNotEmpty()) db.reactions().upsertAll(rx)
        }
        return entity
    }

    /**
     * Scry a thread's parent post (which Tlon returns with its full
     * `seal.replies` map embedded), ingest it + every reply into the
     * local DB. Used when the user navigates into a thread we
     * haven't mirrored yet — typically via an activity-feed deep-
     * link or a notification tap on a reply we got pinged on
     * without ever scrolling near the parent.
     */
    suspend fun fetchThread(whom: String, parentId: String) {
        val ch = channel ?: return
        val (agent, paths) = when {
            whom.startsWith("chat/") ||
                whom.startsWith("diary/") ||
                whom.startsWith("heap/") -> {
                // Channel post id is the raw @ud — needs dotting.
                val dotted = dotAtom(parentId)
                "channels" to listOf("/v5/$whom/posts/post/$dotted")
            }
            whom.startsWith("~") -> {
                // DM writ id is `~author/<dotted-da>` already.
                "chat" to listOf(
                    "/v4/dm/$whom/writs/writ/id/$parentId",
                    "/v3/dm/$whom/writs/writ/id/$parentId",
                )
            }
            whom.startsWith("0v") -> "chat" to listOf(
                "/v4/club/$whom/writs/writ/id/$parentId",
                "/v3/club/$whom/writs/writ/id/$parentId",
            )
            else -> return
        }
        var post: JsonElement? = null
        for (path in paths) {
            val body = runCatching { ch.scry(agent, path) }.getOrNull() ?: continue
            if (body is JsonObject && body.containsKey("seal")) {
                post = body
                break
            }
        }
        if (post == null) {
            Log.w(TAG, "fetchThread($whom, $parentId): no post returned from any path")
            return
        }
        val messages = mutableListOf<MessageEntity>()
        val reactions = mutableListOf<ReactionEntity>()
        ingestPost(whom, post, messages, reactions)
        if (messages.isNotEmpty()) db.messages().upsertAll(messages)
        if (reactions.isNotEmpty()) db.reactions().upsertAll(reactions)
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
        val id = seal["id"].asStr() ?: return null
        val parentId = seal["parent-id"].asStr()
            ?: seal["parent"].asStr()
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
            whom.startsWith("chat/") ||
                whom.startsWith("diary/") ||
                whom.startsWith("heap/") -> "channels"
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

        val probe = scryFirstMatching(ch, app, paths, label = "refreshConversation($whom)")
        val body: JsonElement = probe ?: return
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
        if (whom.startsWith("chat/")) db.messages().purgeStaleLocalIds(whom)
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
            whom.startsWith("chat/") ||
                whom.startsWith("diary/") ||
                whom.startsWith("heap/") -> Triple(
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

        val body = scryFirstMatching(ch, app, paths, label = "loadOlder $whom") as? JsonObject
            ?: return false

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
     * Tracks in-flight channel-post pokes so the SSE poke-ack listener
     * can flip a row's status to "failed" if the channels agent
     * NACKs. Keys are the request ids returned by [UrbitChannel.poke];
     * values are the (whom, localId) pair pointing at the optimistic
     * local twin that needs the update. Cleared on either ack or nack.
     *
     * Channel-only — DM and club sends don't populate the map (their
     * UI doesn't render a status indicator and there's no useful
     * delivery signal beyond our own ship's ack anyway).
     */
    private val pendingChannelPokes:
        java.util.concurrent.ConcurrentHashMap<Long, Pair<String, String>> =
        java.util.concurrent.ConcurrentHashMap()

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
        val token = tokenElement.asStr() ?: error("no memex token")

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

        val (hostedUrl, uploadUrl) = client.newCall(memexReq).withUploadTimeout()
            .execute().use { resp ->
                if (!resp.isSuccessful) error("memex upload-url failed: HTTP ${resp.code}")
                val body = resp.body?.string() ?: error("empty memex response")
                val obj = Json.parseToJsonElement(body).jsonObject
                val hosted = obj["hostedUrl"].asStr()
                    ?: error("no hostedUrl in memex response")
                val upload = obj["uploadUrl"].asStr()
                    ?: error("no uploadUrl in memex response")
                hosted to upload
            }

        val uploadReq = Request.Builder()
            .url(uploadUrl)
            .put(bytes.toRequestBody(contentType.toMediaType()))
            .header("Cache-Control", "public, max-age=3600")
            .build()
        client.newCall(uploadReq).withUploadTimeout().execute().use { resp ->
            if (!resp.isSuccessful) error("memex PUT failed: HTTP ${resp.code}")
        }
        return hostedUrl
    }

    /**
     * Cap any single image-upload HTTP call at 60s. The shared OkHttp
     * client uses readTimeout=0 to keep the SSE channel alive forever,
     * so without an explicit per-call cap an unresponsive memex / S3
     * endpoint would freeze the whole save flow indefinitely.
     */
    private fun okhttp3.Call.withUploadTimeout(): okhttp3.Call = apply {
        timeout().timeout(60, java.util.concurrent.TimeUnit.SECONDS)
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
        val endpoint = inner["endpoint"].asStr() ?: ""
        val accessKeyId = inner["access-key-id"].asStr()
            ?: inner["accessKeyId"].asStr()
            ?: ""
        val secretAccessKey = inner["secret-access-key"].asStr()
            ?: inner["secretAccessKey"].asStr()
            ?: ""
        return StorageCreds(endpoint, accessKeyId, secretAccessKey)
    }

    private fun parseStorageConfiguration(body: JsonElement?): StorageConfig? {
        val obj = (body as? JsonObject) ?: return null
        val inner = (obj["storage-update"] as? JsonObject)?.get("configuration") as? JsonObject
            ?: (obj["configuration"] as? JsonObject)
            ?: obj
        val bucket = inner["current-bucket"].asStr()
            ?: inner["currentBucket"].asStr()
            ?: ""
        val region = inner["region"].asStr() ?: ""
        val publicUrlBase = inner["public-url-base"].asStr()
            ?: inner["publicUrlBase"].asStr()
        val service = inner["service"].asStr()
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
            whom.startsWith("chat/") ||
                whom.startsWith("diary/") ||
                whom.startsWith("heap/") -> ch.poke(
                app = "channels", mark = "channel-action-2",
                payload = channelAction(whom, buildJsonObject {
                    put("post", buildJsonObject {
                        put("add-react", buildJsonObject {
                            put("id", dotAtom(postId))
                            // %channels c-react expects `ship`, not
                            // `author` — sending `author` produces a
                            // poke-as cast fail on the server and the
                            // reaction is silently dropped (only the
                            // local optimistic upsert sticks, and
                            // other devices never see the vote).
                            put("ship", ourPatp)
                            put("react", emoji)
                        })
                    })
                }),
            )
            else -> error("unsupported whom: $whom")
        }
        db.reactions().upsert(ReactionEntity(whom, postId, ourPatp, emoji))
        runCatching { db.reactionUsage().bump(emoji) }
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
            whom.startsWith("chat/") ||
                whom.startsWith("diary/") ||
                whom.startsWith("heap/") -> ch.poke(
                app = "channels", mark = "channel-action-2",
                payload = channelAction(whom, buildJsonObject {
                    put("post", buildJsonObject {
                        put("del-react", buildJsonObject {
                            put("id", dotAtom(postId))
                            // See note on add-react above — same
                            // schema mismatch on del-react.
                            put("ship", ourPatp)
                        })
                    })
                }),
            )
            else -> error("unsupported whom: $whom")
        }
        db.reactions().delete(whom, postId, ourPatp)
    }

    /** Delete a message. Author-only on the server. */
    /**
     * Delete a message. For replies (parentId != null) the poke is
     * routed through the parent's reply-action. We never soft-delete
     * locally here — wait for the server's SSE echo so the row stays
     * visible if the poke is rejected (e.g. user isn't an admin when
     * trying to delete someone else's channel post).
     */
    suspend fun delete(whom: String, postId: String, parentId: String? = null) {
        val ch = channel ?: error("not connected")
        val isReply = parentId != null
        if (whomNeedsOptimisticDelete(whom)) {
            // Mirror what react/unreact do — apply the local change
            // immediately so the message disappears regardless of
            // whether the SSE echo arrives. The DM/club `del`
            // round-trip has been unreliable across mark drift and
            // we'd previously regressed by trusting it.
            db.messages().softDelete(whom, postId)
            db.reactions().clearForPost(whom, postId)
            db.watchwords().clearHitsForPost(whom, postId)
        }
        when {
            whom.startsWith("~") -> {
                val payload = if (isReply) {
                    dmAction(whom, parentId!!, buildJsonObject {
                        put("reply", buildJsonObject {
                            put("id", redotWritId(postId))
                            put("delta", buildJsonObject { put("del", JsonNull) })
                        })
                    })
                } else {
                    dmAction(whom, postId, buildJsonObject { put("del", JsonNull) })
                }
                ch.poke(app = "chat", mark = "chat-dm-action-2", payload = payload)
            }
            whom.startsWith("0v") -> {
                val payload = if (isReply) {
                    clubAction(whom, parentId!!, buildJsonObject {
                        put("reply", buildJsonObject {
                            put("id", redotWritId(postId))
                            put("delta", buildJsonObject { put("del", JsonNull) })
                        })
                    })
                } else {
                    clubAction(whom, postId, buildJsonObject { put("del", JsonNull) })
                }
                ch.poke(app = "chat", mark = "chat-club-action-2", payload = payload)
            }
            whom.startsWith("chat/") ||
                whom.startsWith("diary/") ||
                whom.startsWith("heap/") -> {
                // Channel-action-2 `id` / `del` fields dejs through
                // `slav %ud`, which requires dot-grouped decimals.
                val inner = if (isReply) {
                    buildJsonObject {
                        put("post", buildJsonObject {
                            put("reply", buildJsonObject {
                                put("id", dotAtom(parentId!!))
                                put("action", buildJsonObject {
                                    put("del", JsonPrimitive(dotAtom(postId)))
                                })
                            })
                        })
                    }
                } else {
                    // tlon-apps deletePost for channels: {post: {del: postId}}.
                    buildJsonObject {
                        put("post", buildJsonObject {
                            put("del", JsonPrimitive(dotAtom(postId)))
                        })
                    }
                }
                ch.poke(
                    app = "channels", mark = "channel-action-2",
                    payload = channelAction(whom, inner),
                )
            }
            else -> error("unsupported whom: $whom")
        }
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
        val replyId = if (
            whom.startsWith("chat/") ||
            whom.startsWith("diary/") ||
            whom.startsWith("heap/")
        ) "local_${da}" else UrbitTime.formatPostId(ourPatp, da)
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
            whom.startsWith("chat/") ||
                whom.startsWith("diary/") ||
                whom.startsWith("heap/") -> {
                // channels c-reply shape nests under action:, not c-reply:
                // Channel-action-2's `reply.id` dejs is `(se %ud)`; the
                // agent runs `slav %ud` which demands dot-grouped
                // decimals for values ≥ 1000.
                val payload = channelAction(whom, buildJsonObject {
                    put("post", buildJsonObject {
                        put("reply", buildJsonObject {
                            put("id", dotAtom(parentId))
                            put("action", buildJsonObject {
                                put("add", replyEssay)
                            })
                        })
                    })
                })
                // Same pending-status pattern as postContent: optimistic
                // insert first with status="pending", then poke, then
                // track the pokeId so the SSE listener can flip → failed
                // on a NACK. Server echo reaps the local twin which
                // implicitly clears the indicator.
                db.messages().upsert(
                    toReplyEntity(whom, parentId, replyId, replyEssay)
                        .copy(status = "pending")
                )
                val pokeId = ch.poke(
                    app = "channels", mark = "channel-action-2", payload = payload,
                )
                pendingChannelPokes[pokeId] = whom to replyId
            }
            else -> error("unsupported whom: $whom")
        }
        return replyId
    }

    /**
     * Edit a top-level channel message's text content. %chat (DMs and
     * clubs) rejects edit actions on the ships we've tested even though
     * the action mold in newer Hoon sources includes an `%edit` variant
     * — so we only offer this for %channels chat-channels. Reply
     * editing isn't in the agent mold for either side.
     */
    suspend fun edit(whom: String, postId: String, text: String, originalSentMs: Long) {
        val ch = channel ?: error("not connected")
        if (!whom.startsWith("chat/")) error("edit only supported on channel chats")
        // Preserve the original `sent` — the server sorts by it and
        // re-using our current time would bump the post to "just now".
        // Tlon keeps the original essay shell and only swaps content.
        val essay = buildEssay(textToStory(text), originalSentMs)
        val payload = channelAction(whom, buildJsonObject {
            put("post", buildJsonObject {
                // channel-action-2's `id` dejs is `(se %ud)` which runs
                // `slav %ud` → `dem:ag`, and dem:ag demands Urbit-style
                // dot-grouped decimals for numbers ≥ 1000.
                put("edit", buildJsonObject {
                    put("id", dotAtom(postId))
                    put("essay", essay)
                })
            })
        })
        ch.poke(app = "channels", mark = "channel-action-2", payload = payload)
    }

    /**
     * Edit a notebook post — swap title/image/body while preserving
     * the original `sent` so the post keeps its list position.
     */
    suspend fun editNotebookPost(
        nest: String,
        postId: String,
        title: String,
        image: String,
        bodyMarkdown: String,
        originalSentMs: Long,
    ) {
        require(nest.startsWith("diary/")) { "not a diary channel: $nest" }
        val ch = channel ?: error("not connected")
        val content = MarkdownBlocks.toStory(bodyMarkdown)
        val meta = buildJsonObject {
            put("title", title)
            put("image", image)
            put("description", "")
            put("cover", "")
        }
        val essay = buildEssay(content, originalSentMs, kind = "/diary", meta = meta)
        ch.poke(
            app = "channels", mark = "channel-action-2",
            payload = channelAction(nest, buildJsonObject {
                put("post", buildJsonObject {
                    put("edit", buildJsonObject {
                        put("id", dotAtom(postId))
                        put("essay", essay)
                    })
                })
            }),
        )
    }

    /** Edit a gallery post — replace its content array. */
    suspend fun editGalleryPost(
        nest: String,
        postId: String,
        content: JsonArray,
        originalSentMs: Long,
    ) {
        require(nest.startsWith("heap/")) { "not a heap channel: $nest" }
        val ch = channel ?: error("not connected")
        val essay = buildEssay(content, originalSentMs, kind = "/heap", meta = null)
        ch.poke(
            app = "channels", mark = "channel-action-2",
            payload = channelAction(nest, buildJsonObject {
                put("post", buildJsonObject {
                    put("edit", buildJsonObject {
                        put("id", dotAtom(postId))
                        put("essay", essay)
                    })
                })
            }),
        )
    }

    /**
     * Pin [postId] in [nest]. Tlon's pinned post is `channel.order[0]`;
     * we prepend [postId] to the current order (dropping any existing
     * occurrence) and ship the new list.
     */
    suspend fun pinPost(nest: String, postId: String) {
        require(nest.startsWith("chat/")) { "pin only supported on chat channels: $nest" }
        val ch = channel ?: error("not connected")
        val current = db.groups().pinnedPostIdFor(nest)
        // The banner only ever reads order[0]; keeping just [postId]
        // matches behavior and dodges a full scry on every pin.
        val next = if (current == postId) return else listOf(postId)
        ch.poke(
            app = "channels", mark = "channel-action-2",
            payload = channelAction(nest, channelOrderAction(next)),
        )
        db.groups().setPinnedPostId(nest, postId)
    }

    /** Unpin whatever is currently pinned in [nest]. */
    suspend fun unpinPost(nest: String) {
        require(nest.startsWith("chat/")) { "pin only supported on chat channels: $nest" }
        val ch = channel ?: error("not connected")
        ch.poke(
            app = "channels", mark = "channel-action-2",
            payload = channelAction(nest, channelOrderAction(emptyList())),
        )
        db.groups().setPinnedPostId(nest, null)
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
        // subscribe-replies have no json and can be skipped — but
        // surface poke NACKs (and watch rejections) so silent server
        // rejections are debuggable.
        val response = outer["response"].asStr()
        if (response == "poke" || response == "subscribe") {
            val err = outer["err"]
            val pokeIdLong = outer["id"].asLong()
            if (err != null && err !is JsonNull) {
                Log.w(TAG, "$response nack id=${outer["id"]} err=$err")
                // Channel-post NACK: flip the optimistic local twin
                // from "pending" → "failed" so the UI surfaces the
                // failure (small "!" indicator). DM/club rows aren't
                // tracked here (their initial status was null).
                if (response == "poke" && pokeIdLong != null) {
                    pendingChannelPokes.remove(pokeIdLong)?.let { (whom, id) ->
                        scope.launch {
                            runCatching {
                                db.messages().setStatus(whom, id, "failed")
                            }.onFailure { Log.w(TAG, "setStatus(failed) failed", it) }
                        }
                    }
                }
            } else if (response == "poke" && pokeIdLong != null) {
                // Successful poke — drop the tracking entry. Status is
                // implicitly cleared when the server-id echo arrives
                // and reapLocalTwin removes the pending row.
                pendingChannelPokes.remove(pokeIdLong)
            }
            return
        }
        if (response != "diff") return
        val payload = outer["json"] as? JsonObject ?: return

        // %chat writ-response-4: { whom, id, response }
        val whom = payload["whom"].asStr()
        val directId = payload["id"].asStr()
        if (whom != null && directId != null) {
            // %chat echoes post ids with dot-grouping in the envelope
            // but our DB stores raw undotted ids everywhere else.
            // Stripping here fixes a nasty dup where live SSE events
            // wrote dotted rows and paginate/bootstrap wrote undotted
            // rows for the same underlying message.
            applyChatDelta(
                whom,
                undotAtom(directId),
                payload["response"] as? JsonObject ?: return,
            )
            return
        }

        // %channels r-channels-5: { nest, response }
        val nest = payload["nest"].asStr()
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
            settingsSync?.applySettingsEvent(payload)
            return
        }

        // %groups /v1/groups — {flag, r-group}. Carries group
        // create/delete, meta, and channel add/edit/del. Without this
        // branch, new channels never land in the home list until a
        // reconnect triggers bootstrapGroups.
        if (payload.containsKey("flag") && payload.containsKey("r-group")) {
            applyGroupEvent(payload)
            return
        }
    }

    private suspend fun applyGroupEvent(payload: JsonObject) {
        when (val intent = classifyGroupEvent(payload) ?: return) {
            is GroupEventIntent.CreateGroup -> {
                db.groups().upsertGroups(listOf(
                    GroupEntity(
                        flag = intent.flag,
                        title = intent.title,
                        image = intent.image,
                    )
                ))
                if (intent.channels.isNotEmpty()) {
                    db.groups().upsertChannelGroups(intent.channels.map { (nest, title) ->
                        ChannelGroupEntity(
                            nest = nest,
                            groupFlag = intent.flag,
                            title = title,
                        )
                    })
                }
            }
            is GroupEventIntent.DeleteGroup -> {
                db.groups().deleteChannelsForGroup(intent.flag)
                db.groups().deleteGroup(intent.flag)
            }
            is GroupEventIntent.EditGroupMeta -> {
                db.groups().upsertGroups(listOf(
                    GroupEntity(
                        flag = intent.flag,
                        title = intent.title,
                        image = intent.image,
                    )
                ))
            }
            is GroupEventIntent.AddChannel -> {
                db.groups().upsertChannelGroups(listOf(
                    ChannelGroupEntity(
                        nest = intent.nest,
                        groupFlag = intent.flag,
                        title = intent.title,
                    )
                ))
            }
            is GroupEventIntent.EditChannel -> {
                db.groups().upsertChannelGroups(listOf(
                    ChannelGroupEntity(
                        nest = intent.nest,
                        groupFlag = intent.flag,
                        title = intent.title,
                    )
                ))
            }
            is GroupEventIntent.DeleteChannel -> {
                db.groups().deleteChannelGroup(intent.nest)
            }
            is GroupEventIntent.Unknown -> Unit
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
            db.watchwords().clearHitsForPost(whom, id)
            return
        }
        (response["add-react"] as? JsonObject)?.let { ar ->
            val author = ar["author"].asStr() ?: return@let
            val react = ar["react"].asStr() ?: return@let
            db.reactions().upsert(ReactionEntity(whom, id, author, react))
            return
        }
        response["del-react"].asStr()?.let { author ->
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
        // Same normalization as applyChatDelta — reply ids also arrive
        // dotted in the %chat SSE envelope.
        val replyId = reply["id"].asStr()
            ?.let(::undotAtom) ?: return
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
            db.watchwords().clearHitsForPost(whom, replyId)
            return
        }
        (delta["add-react"] as? JsonObject)?.let { ar ->
            val author = ar["author"].asStr() ?: return@let
            val react = ar["react"].asStr() ?: return@let
            db.reactions().upsert(ReactionEntity(whom, replyId, author, react))
            return
        }
        delta["del-react"].asStr()?.let { author ->
            db.reactions().delete(whom, replyId, author)
        }
    }

    private suspend fun applyChannelDelta(nest: String, response: JsonObject) {
        // Classification lives in ChannelEventRouter (pure, tested).
        // This function only dispatches intents into DB/listener writes.
        when (val intent = classifyChannelDelta(response)) {
            is ChannelDeltaIntent.PostsBatch -> {
                val messages = mutableListOf<MessageEntity>()
                val reactions = mutableListOf<ReactionEntity>()
                intent.posts.forEach { (_, post) ->
                    ingestPost(nest, post, messages, reactions)
                }
                if (messages.isNotEmpty()) db.messages().upsertAll(messages)
                if (reactions.isNotEmpty()) db.reactions().upsertAll(reactions)
            }
            is ChannelDeltaIntent.PostSet -> {
                val msgs = mutableListOf<MessageEntity>()
                val rx = mutableListOf<ReactionEntity>()
                ingestPost(nest, intent.post, msgs, rx)
                db.messages().upsertAll(msgs)
                if (rx.isNotEmpty()) {
                    db.reactions().clearForPost(nest, intent.id)
                    db.reactions().upsertAll(rx)
                }
                msgs.firstOrNull { it.id == intent.id && it.author == ourPatp }?.let {
                    db.messages().reapLocalTwin(nest, ourPatp, it.sentMs)
                }
                msgs.firstOrNull { it.id == intent.id && it.parentId == null }
                    ?.takeIf { it.author != ourPatp }
                    ?.let { messageListener?.invoke(it, false) }
            }
            is ChannelDeltaIntent.PostTombstone, is ChannelDeltaIntent.PostDeleted -> {
                val id = when (intent) {
                    is ChannelDeltaIntent.PostTombstone -> intent.id
                    is ChannelDeltaIntent.PostDeleted -> intent.id
                    else -> return
                }
                db.messages().softDelete(nest, id)
                db.reactions().clearForPost(nest, id)
                db.watchwords().clearHitsForPost(nest, id)
            }
            is ChannelDeltaIntent.PostReactions -> {
                db.reactions().clearForPost(nest, intent.id)
                val rx = intent.reacts.entries.mapNotNull { (author, emoji) ->
                    val e = emoji.asStr() ?: return@mapNotNull null
                    ReactionEntity(nest, intent.id, author, e)
                }
                if (rx.isNotEmpty()) db.reactions().upsertAll(rx)
            }
            is ChannelDeltaIntent.PostEssay -> {
                val entity = toEntity(nest, intent.id, intent.essay)
                db.messages().upsert(entity)
                // Edits don't trigger a notification.
            }
            is ChannelDeltaIntent.Reply ->
                applyReplyIntent(nest, intent.parentId, intent.replyId, intent.inner)
            is ChannelDeltaIntent.OrderUpdate ->
                db.groups().setPinnedPostId(nest, intent.postIds.firstOrNull())
            is ChannelDeltaIntent.PendingPost,
            is ChannelDeltaIntent.Unknown -> Unit
        }
    }

    private suspend fun applyReplyIntent(
        whom: String,
        parentId: String,
        replyId: String,
        inner: ReplyIntent,
    ) {
        when (inner) {
            is ReplyIntent.Upsert -> {
                val entity = toReplyEntity(whom, parentId, replyId, inner.replyEssay)
                db.messages().upsert(entity)
                if (entity.author == ourPatp) {
                    db.messages().reapLocalTwin(whom, ourPatp, entity.sentMs)
                } else {
                    val parent = db.messages().getOne(whom, parentId)
                    val replyToUs = parent?.author == ourPatp
                    messageListener?.invoke(entity, replyToUs)
                }
            }
            is ReplyIntent.Tombstone, is ReplyIntent.Deleted -> {
                db.messages().softDelete(whom, replyId)
                db.reactions().clearForPost(whom, replyId)
                db.watchwords().clearHitsForPost(whom, replyId)
            }
            is ReplyIntent.Reactions -> {
                db.reactions().clearForPost(whom, replyId)
                val rx = inner.reacts.entries.mapNotNull { (author, emoji) ->
                    val e = emoji.asStr() ?: return@mapNotNull null
                    ReactionEntity(whom, replyId, author, e)
                }
                if (rx.isNotEmpty()) db.reactions().upsertAll(rx)
            }
            is ReplyIntent.Unknown -> Unit
        }
    }

    // ───────── activity / unreads ─────────

    private suspend fun bootstrapActivity(channel: UrbitChannel) {
        val body = channel.scry("activity", "/v4/activity")
        val obj = body as? JsonObject ?: return
        val focused = openWhom
        val rows = obj.entries.mapNotNull { (sourceKey, summary) ->
            toUnread(sourceKey, summary as? JsonObject ?: return@mapNotNull null)
        }.map { row ->
            // Respect the focus-override so a post-mark-read scry race
            // doesn't re-bump the badge while the user is still looking.
            if (row.whom == focused) row.copy(count = 0, notifyCount = 0) else row
        }
        if (rows.isNotEmpty()) db.unreads().upsertAll(rows)
    }

    private suspend fun applyActivityUpdate(obj: JsonObject) {
        val focused = openWhom
        (obj["activity"] as? JsonObject)?.let { map ->
            val rows = map.entries.mapNotNull { (key, summary) ->
                toUnread(key, summary as? JsonObject ?: return@mapNotNull null)
            }.map { row ->
                // User is actively looking at this chat — treat as read.
                // Also propagate that to the ship so other clients
                // (Tlon webapp / mobile) don't keep showing the badge
                // while we read here. Without this, every new message
                // bumped the ship's unread count and Talon's local
                // suppression hid it from us but not from anyone else.
                if (row.whom == focused) {
                    if (row.count > 0 || row.notifyCount > 0) {
                        scope.launch { runCatching { markRead(row.whom) } }
                    }
                    row.copy(count = 0, notifyCount = 0)
                } else row
            }
            if (rows.isNotEmpty()) db.unreads().upsertAll(rows)
            return
        }
        (obj["read"] as? JsonObject)?.let { read ->
            val source = read["source"] as? JsonObject ?: return@let
            val summary = read["activity"] as? JsonObject ?: return@let
            val whom = sourceToWhom(source) ?: return@let
            toUnread(sourceKey = null, summary = summary, overrideWhom = whom)
                ?.let { row ->
                    val adjusted = if (row.whom == focused) {
                        // Same upstream-propagation as the bulk-update
                        // branch above: if the ship still thinks there
                        // are unreads here while we're focused, send a
                        // markRead so other clients agree.
                        if (row.count > 0 || row.notifyCount > 0) {
                            scope.launch { runCatching { markRead(row.whom) } }
                        }
                        row.copy(count = 0, notifyCount = 0)
                    } else row
                    db.unreads().upsert(adjusted)
                }
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
    // toUnread / sourceKeyToWhom / sourceToWhom / activityReadSource /
    // activityReadAction extracted to ActivityParser.kt for testing.

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

        val groupFlag = if (
            whom.startsWith("chat/") ||
            whom.startsWith("diary/") ||
            whom.startsWith("heap/")
        ) {
            db.groups().channelGroupFor(whom)?.groupFlag ?: run {
                Log.w(TAG, "markRead: no group flag for $whom; skipping poke")
                return
            }
        } else null
        val source = activityReadSource(whom, groupFlag) ?: return
        // Retry on transient errors (channel cycle, socket reset
        // mid-poke). Mirrors Tlon's TS client (`backOff(...,
        // numOfAttempts: 4)` in activityApi.ts) — without retry, a
        // poke that races with a reconnect just dropped, leaving the
        // ship's unread count stuck and Tlon's UI showing a stale
        // badge after Talon already cleared it locally.
        val maxAttempts = 4
        var attempt = 0
        var lastErr: Throwable? = null
        while (attempt < maxAttempts) {
            val outcome = runCatching {
                ch.poke(
                    app = "activity",
                    mark = "activity-action",
                    payload = activityReadAction(source),
                )
            }
            if (outcome.isSuccess) return
            lastErr = outcome.exceptionOrNull()
            val transient = lastErr is java.io.InterruptedIOException ||
                lastErr is java.net.SocketException ||
                lastErr?.cause is java.net.SocketException
            if (!transient) break
            attempt += 1
            if (attempt < maxAttempts) {
                kotlinx.coroutines.delay(1_000L * (1 shl (attempt - 1)))
            }
        }
        lastErr?.let { err ->
            val transient = err is java.io.InterruptedIOException ||
                err is java.net.SocketException ||
                err.cause is java.net.SocketException
            if (transient) {
                Log.i(TAG, "markRead $whom gave up after $attempt attempts: ${err.message}")
            } else {
                Log.w(TAG, "markRead poke failed for $whom", err)
            }
        }
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
        // Bootstrap stamps "now" when a contact has a status but the
        // server didn't send a parseable mod-at. Without this fallback
        // a fresh-install desktop (which never observed the live news
        // event that set the real timestamp) would show every status
        // ordered by ship-name only — recent updates buried at the
        // bottom of the Statuses feed. Matches applyContactsNews's
        // ?: System.currentTimeMillis() fallback.
        val now = System.currentTimeMillis()

        runCatching { channel.scry("contacts", "/v1/all") }.getOrNull()?.let { body ->
            (body as? JsonObject)?.forEach { (ship, entry) ->
                val obj = entry as? JsonObject ?: return@forEach
                // Newer ships wrap each entry as {contact: {...fields},
                // mod-at: "..."}; older ones emit the flat field map.
                val wrapped = obj["contact"] as? JsonObject
                val fields = wrapped ?: obj
                val parsed = parseContact(ship, fields, parseContactModAt(obj))
                fresh.add(stampStatusFallback(parsed, now))
            }
        }

        runCatching { channel.scry("contacts", "/v1/self") }.getOrNull()?.let { body ->
            (body as? JsonObject)?.let { obj ->
                val wrapped = obj["contact"] as? JsonObject
                val fields = wrapped ?: obj
                val parsed = parseContact(ourPatp, fields, parseContactModAt(obj))
                fresh.add(stampStatusFallback(parsed, now))
            }
        }

        if (fresh.isNotEmpty()) {
            val merged = fresh.map { mergeContact(it) }
            db.contacts().upsertAll(merged)
        }
    }

    private fun stampStatusFallback(
        contact: ContactEntity,
        fallbackMs: Long,
    ): ContactEntity =
        if (!contact.status.isNullOrBlank() && contact.statusUpdatedMs == null) {
            contact.copy(statusUpdatedMs = fallbackMs)
        } else contact

    /**
     * Apply a single peer update from %contacts /v1/news. Handles the
     * `page` and `peer` response shapes; `wipe` (contact removal) isn't
     * surfaced locally for v1.
     */
    private suspend fun applyContactsNews(event: JsonObject) {
        (event["page"] as? JsonObject)?.let { page ->
            val kip = page["kip"].asStr() ?: return
            if (!kip.startsWith("~")) return
            val contact = page["contact"] as? JsonObject ?: return
            // Prefer the server-provided mod-at. Fall back to our own
            // observation time — we know the status just changed since
            // this fact is the change event itself.
            val modAt = parseContactModAt(page) ?: System.currentTimeMillis()
            db.contacts().upsert(mergeContact(parseContact(kip, contact, modAt)))
            return
        }
        (event["peer"] as? JsonObject)?.let { peer ->
            val who = peer["who"].asStr() ?: return
            if (!who.startsWith("~")) return
            val contact = peer["contact"] as? JsonObject ?: return
            val modAt = parseContactModAt(peer) ?: System.currentTimeMillis()
            db.contacts().upsert(mergeContact(parseContact(who, contact, modAt)))
            return
        }
    }

    /**
     * Merge an incoming contact record with what we already have. Keeps
     * avatar/bio/nickname intact when the incoming row doesn't supply
     * them, and preserves `statusUpdatedMs` unless the caller passed a
     * fresh, authoritative one via `parseContact`. Never stamps "now"
     * during bulk ingest — that was hiding real timestamps behind a
     * uniform value.
     */
    private suspend fun mergeContact(incoming: ContactEntity): ContactEntity {
        val existing = db.contacts().get(incoming.ship)
        return incoming.copy(
            nickname = incoming.nickname ?: existing?.nickname,
            bio = incoming.bio ?: existing?.bio,
            avatarUrl = incoming.avatarUrl ?: existing?.avatarUrl,
            statusUpdatedMs = when {
                incoming.status.isNullOrBlank() -> null
                incoming.statusUpdatedMs != null -> incoming.statusUpdatedMs
                else -> existing?.statusUpdatedMs
            },
        )
    }

    private fun parseContact(
        ship: String,
        fields: JsonObject,
        modAtMs: Long? = null,
    ): ContactEntity {
        // Tlon's contacts /v1 wire shape wraps each field as
        //   {type: <tag>, value: <payload>}
        // where <tag> is the value-type tag from sur/contacts.hoon —
        // %text for nickname/bio/status, %look for avatar/cover, %tint
        // for color. Earlier versions of this parser only matched
        // %text, which silently dropped every avatar. See
        // tlon-apps/desk/lib/contacts/json-1.hoon:23-37.
        fun typedValue(name: String, type: String): String? {
            val field = fields[name] as? JsonObject ?: return null
            if (field["type"].asStr() != type) return null
            return field["value"].asStr()?.takeIf { it.isNotBlank() }
        }
        // Colors may arrive as either a plain text field ("#ff5050") or a
        // typed "color" field whose value is Urbit @ux hex ("0xff.5050").
        fun colorField(name: String): String? {
            val field = fields[name] as? JsonObject ?: return null
            val raw = field["value"].asStr()
                ?: return null
            return normalizeHexColor(raw)
        }
        val status = typedValue("status", "text")
        return ContactEntity(
            ship = ship,
            nickname = typedValue("nickname", "text"),
            bio = typedValue("bio", "text"),
            avatarUrl = typedValue("avatar", "look"),
            status = status,
            // Only carry a server-provided timestamp here. `mergeContact`
            // decides whether to stamp "now" for live observations.
            statusUpdatedMs = if (status.isNullOrBlank()) null else modAtMs,
            color = colorField("color"),
        )
    }

    /**
     * Best-effort parse of %contacts' `mod-at` envelope field. Tlon
     * serializes it as a dotted unix-ms cord (e.g. "1.734.890.123.456").
     * Older ships may emit a raw @da or omit it entirely — we only
     * accept values that look like a sensible recent ms timestamp.
     */
    private fun parseContactModAt(envelope: JsonObject?): Long? {
        val raw = envelope?.get("mod-at").asStr()
            ?: return null
        // %contacts serializes `mod-at` as the @da's underlying
        // integer, hoon-style dot-grouped (e.g.
        // "170.141.184.505.296...."). Earlier revisions stripped
        // dots and called toLongOrNull, which silently overflowed
        // on every real value (@da ≈ 1.7e38 vs Long.MAX_VALUE ≈
        // 9.2e18) and returned null — meaning every bootstrapped
        // contact ended up with statusUpdatedMs=null and the
        // Statuses feed couldn't sort recent updates to the top.
        val digits = raw.replace(".", "")
        if (digits.isEmpty() || !digits.all { it.isDigit() }) return null
        val da = runCatching { java.math.BigInteger(digits) }.getOrNull()
            ?: return null
        val ms = UrbitTime.daToUnixMs(da) ?: return null
        // Sanity-bound: 2020-01-01 .. 2100-01-01 in unix-ms — clips
        // out fixture / debug values that decoded into nonsense.
        return if (ms in 1_577_836_800_000L..4_102_444_800_000L) ms else null
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
     * Scry the %channels agent for each chat channel's `order` field
     * and mirror `order[0]` into `channel_groups.pinnedPostId`. Without
     * this, the pinned banner only populates after the next SSE order
     * event — on cold start we'd otherwise show nothing for channels
     * pinned long ago.
     */
    private suspend fun bootstrapChannelOrders(channel: UrbitChannel) {
        val body = channel.scry("channels", "/v5/channels")
        val obj = body as? JsonObject ?: return
        for ((nest, ch) in obj) {
            if (!nest.startsWith("chat/")) continue
            val chObj = ch as? JsonObject ?: continue
            val order = chObj["order"] as? kotlinx.serialization.json.JsonArray
            val pinned = order?.firstOrNull().asStr()?.replace(".", "")
            db.groups().setPinnedPostId(nest, pinned)
        }
    }

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
                title = meta?.get("title").asStr()
                    ?.takeIf { it.isNotBlank() },
                image = meta?.get("image").asStr()
                    ?.takeIf { it.isNotBlank() },
            )
            val channels = groupObj["channels"] as? JsonObject ?: continue
            for ((nest, channel) in channels) {
                val channelObj = channel as? JsonObject
                val channelMeta = channelObj?.get("meta") as? JsonObject
                val channelTitle = channelMeta?.get("title")
                    .asStr()
                    ?.takeIf { it.isNotBlank() }
                channelGroups += ChannelGroupEntity(
                    nest = nest,
                    groupFlag = flag,
                    title = channelTitle,
                )
            }
        }
        // Reconcile: drop local rows the ship no longer reports.
        // Without this, groups the user left / hosts deleted while
        // Talon was offline linger in the home list forever.
        val plan = planGroupReconcile(
            existingGroups = db.groups().allGroups(),
            existingChannels = db.groups().allChannelGroups(),
            liveGroupFlags = obj.keys,
            liveChannelNests = channelGroups.map { it.nest }.toSet(),
        )
        for (flag in plan.deletedGroupFlags) {
            db.groups().deleteChannelsForGroup(flag)
            db.groups().deleteGroup(flag)
        }
        for (nest in plan.deletedChannelNests) {
            db.groups().deleteChannelGroup(nest)
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
            val title = meta?.get("title").asStr()
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
    private suspend fun ingestPost(
        whom: String,
        post: JsonElement,
        messagesOut: MutableList<MessageEntity>,
        reactionsOut: MutableList<ReactionEntity>,
    ) {
        // Pure classification lives in PostIngest.kt.
        val result = ingestedPost(whom, post)
        messagesOut.addAll(result.messages)
        reactionsOut.addAll(result.reactions)
        // Deliberately *not* pre-warming StoryCache here. Bulk callers
        // (refreshConversation, loadOlder, applyChannelDelta r-post.set)
        // run ingestPost in a tight loop over hundreds of rows; warming
        // every one of them ran Story.parse + buildAnnotatedString
        // hundreds of times, allocating tens of MB of garbage that
        // immediately competed for GC right when the chat was painting.
        // Visible rows lazy-parse on first composition (≈1ms × ~15-20
        // visible rows = one frame on first open) and that cost is well
        // hidden behind the network round-trip the user is already
        // waiting on. Single-message ingests (toEntity, toReplyEntity,
        // applyChatDelta) still pre-warm — those are individual events
        // where the cost is bounded and prevents render-time jank when
        // an SSE delta lands while the user is staring at the chat.
        // Process tombstones inline — soft-delete so stale rows from
        // earlier scries disappear on re-ingest.
        result.tombstones.forEach { id ->
            db.messages().softDelete(whom, id)
            db.reactions().clearForPost(whom, id)
            db.watchwords().clearHitsForPost(whom, id)
        }
    }

    private fun toEntity(whom: String, id: String, essay: JsonObject): MessageEntity {
        // Thin wrapper so the two applyChannelDelta ingestion branches
        // (full post set vs full post ingest) produce identical rows.
        val entity = pureEntity(whom, id, essay)
        StoryCache.partsFor(entity.id, entity.contentJson)
        return entity
    }

    // mergeBlobIntoContent extracted to PostIngest.kt.

    /**
     * Parse composer text into a Story. See [chatTextToStory] —
     * extracted so tests can exercise blockquote grouping without a
     * repo instance.
     */
    private fun textToStory(text: String): JsonArray = chatTextToStory(text)

    private fun buildEssay(
        content: JsonArray,
        sentMs: Long,
        kind: String = "/chat",
        meta: JsonObject? = null,
    ): JsonObject = buildJsonObject {
        put("content", content)
        put("author", ourPatp)
        put("sent", sentMs)
        put("kind", kind)
        put("meta", meta ?: JsonNull)
        put("blob", JsonNull)
    }

    /**
     * Try `paths` in order until one returns a 2xx body, then return
     * that body. Bounded by three stop conditions:
     *
     *  1. **Per-probe OkHttp timeout** ([SCRY_PROBE_PER_CALL_SECS]).
     *     Each `ch.scry` attempt asks OkHttp to cap the call at this
     *     duration. The chat-screen path-fallback list has up to 20
     *     entries; the default 30s per-call cap meant a wedged ship
     *     wedged the loading indicator for the same 30s. A
     *     coroutine-level `withTimeout` looks tempting but doesn't
     *     work — `OkHttp.execute()` is blocking, so cancellation
     *     can't preempt an in-flight request without going through
     *     `call.cancel()`, which is what the per-call timeout
     *     triggers internally.
     *  2. **Wall-clock budget** ([SCRY_PROBE_BUDGET_MS]). Caps the
     *     total iteration time across all probes so a chain of
     *     "wrong shape" 404s plus one slow timeout can't exceed it.
     *  3. **Fast-fail on socket timeout.** A timed-out probe means
     *     the network is wedged — the next probes will time out the
     *     same way. HTTP errors (404, 5xx) still fall through
     *     because those mean "wrong shape, try the next one."
     *
     * Returns null on no successful probe.
     */
    private suspend fun scryFirstMatching(
        ch: UrbitChannel,
        app: String,
        paths: List<String>,
        label: String,
    ): JsonElement? {
        val deadline = System.currentTimeMillis() + SCRY_PROBE_BUDGET_MS
        var lastErr: Throwable? = null
        for (path in paths) {
            if (System.currentTimeMillis() >= deadline) {
                Log.w(TAG, "$label: ${SCRY_PROBE_BUDGET_MS}ms budget exhausted, giving up; last err: ${lastErr?.message}")
                return null
            }
            val attempt = runCatching { ch.scry(app, path, SCRY_PROBE_PER_CALL_SECS) }
            if (attempt.isSuccess) return attempt.getOrNull()
            val err = attempt.exceptionOrNull()
            lastErr = err
            if (err is java.io.InterruptedIOException ||
                err?.cause is java.io.InterruptedIOException ||
                err is java.net.SocketException ||
                err?.cause is java.net.SocketException
            ) {
                Log.w(TAG, "$label: network timeout, giving up; err: ${err.message}")
                return null
            }
        }
        Log.w(TAG, "$label: all ${paths.size} probes failed; last err: ${lastErr?.message}")
        return null
    }

    companion object {
        private const val TAG = "TlonChatRepo"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        // 5 minutes — refresh on screen entry if older, instant paint
        // from cache within the window.
        private const val ADMIN_CACHE_TTL_MS = 5L * 60_000L
        // Coalesce rapid forceReconnect requests within this window so
        // a doubled lifecycle ON_START doesn't tear down the channel
        // mid-bootstrap. See [forceReconnect].
        private const val FORCE_RECONNECT_DEBOUNCE_MS = 3_000L
        // Wall-clock budget for the shape-fallback scry probes in
        // [scryFirstMatching]. Caps the total iteration time across
        // all paths so chained "wrong shape" 404s plus one slow
        // timeout can't blow past it.
        private const val SCRY_PROBE_BUDGET_MS = 12_000L
        // OkHttp per-call timeout (in seconds) passed to `ch.scry`
        // from inside scryFirstMatching. 6s is plenty for a healthy
        // ship (typical scry < 500ms) and short enough that a wedged
        // network bails the spinner before the user gives up. The
        // shared client's 30s default still applies to non-probe
        // scry/poke paths (bootstrap, sends) where slower responses
        // are tolerable.
        private const val SCRY_PROBE_PER_CALL_SECS = 6L
    }

    // dmAction / clubAction / channelAction / replyDelta extracted to WireShapes.kt.

    /** Thin wrapper: pure reply-entity + StoryCache pre-warm. */
    private fun toReplyEntity(
        whom: String,
        parentId: String,
        replyId: String,
        replyEssay: JsonObject,
    ): MessageEntity {
        val entity = pureReplyEntity(whom, parentId, replyId, replyEssay)
        StoryCache.partsFor(entity.id, entity.contentJson)
        return entity
    }

}
