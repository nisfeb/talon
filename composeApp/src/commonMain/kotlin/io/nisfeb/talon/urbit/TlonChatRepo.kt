// Diverges from production in three controlled ways so that this file
// can compile in commonMain (no Android, no app/-only types):
//
//  1. android.util.Log -> io.nisfeb.talon.util.Log (the expect/actual
//     facade in commonMain). Two production Log.d calls are folded to
//     Log.i because the facade omits `d` (see Log.kt KDoc).
//
//  2. The constructor takes (db, settingsSync: SettingsSync? = null)
//     instead of (db, aiSettings, ...).
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
import kotlin.concurrent.Volatile
import com.ionspin.kotlin.bignum.integer.BigInteger
import io.nisfeb.talon.util.ConcurrentMap
import io.nisfeb.talon.util.ConcurrentSet
import io.nisfeb.talon.util.isTransientNetworkError
import io.nisfeb.talon.util.isShipSlow
import io.nisfeb.talon.util.backgroundExceptionHandler
import io.nisfeb.talon.util.ioDispatcher
import io.nisfeb.talon.util.nowMs

import io.nisfeb.talon.util.Log
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.ChannelGroupEntity
import io.nisfeb.talon.data.ClubEntity
import io.nisfeb.talon.data.ContactEntity
import io.nisfeb.talon.data.DmInviteEntity
import io.nisfeb.talon.data.FollowedThreadEntity
import io.nisfeb.talon.data.GroupEntity
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.data.ReactionEntity
import io.nisfeb.talon.data.UnreadEntity
import io.nisfeb.talon.ui.ReactionPalette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import io.nisfeb.talon.data.latestPerConversation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
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
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess

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
     * backed AiSettings); composeApp desktop passes
     * `null` until a desktop %settings bridge is added in Stage F.
     */
    val settingsSync: SettingsSync? = null,
    /**
     * Diagnostics sink for the Notification Health panel. Repo
     * writes SSE / reconcile / reconnect timestamps; UI reads them.
     * Defaults to a fresh instance so test harnesses don't need to
     * stand one up — the host should pass the app-wide instance so
     * all surfaces see the same state.
     */
    val notificationHealth: io.nisfeb.talon.notify.NotificationHealth =
        io.nisfeb.talon.notify.NotificationHealth(),
) {

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher + backgroundExceptionHandler)
    /**
     * Scope for fire-and-forget ship pokes (folder reorder,
     * notify preferences, etc.) that should complete even if the
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
    val pushScope = CoroutineScope(SupervisorJob() + ioDispatcher + backgroundExceptionHandler)
    @Volatile private var started = false
    @Volatile private var channel: UrbitChannel? = null
    @Volatile private var http: HttpClient? = null
    @Volatile private var baseUrl: String? = null
    @Volatile private var ourPatp: String = ""
    @Volatile private var sessionJob: Job? = null
    @Volatile private var lastEventMs: Long = 0L

    /** When the reconciliation scries last ran. A reconnect that lands
     *  right after one skips them: the previous pass already covered
     *  the window, the subscriptions carry everything live, and a
     *  reconnect must never re-run an expensive bootstrap. Bounds the
     *  damage if anything ever loops again. */
    @Volatile private var lastBootstrapMs: Long = 0L

    /** Authenticated client + base URL for the active ship (null until
     *  [start]). Lets ship-adjacent features — e.g. the MCP endpoint at
     *  `/mcp` — reuse the session without re-plumbing auth. */
    val shipHttp: HttpClient? get() = http
    val shipBaseUrl: String? get() = baseUrl

    // Admin-groups cache: populated by refreshAdminGroups(), consumed
    // by the Administration screen. Fetching the full per-group state
    // is 30+ scries so we keep the last result around and only refresh
    // when it's older than ADMIN_CACHE_TTL_MS or the caller asks for a
    // forced reload.
    private val _adminGroups = MutableStateFlow<List<AdminGroup>?>(null)
    val adminGroupsFlow: StateFlow<List<AdminGroup>?> = _adminGroups.asStateFlow()
    @Volatile private var adminGroupsFetchedMs: Long = 0L
    @Volatile private var adminGroupsFailedMs: Long = 0L
    private val adminGroupsMutex = Mutex()

    /**
     * True while the first-run bootstrap (init-posts + activity scries
     * + contacts/clubs/groups scries) is in flight. UI surfaces this
     * as a progress bar so the 10-30s of silence on a fresh start
     * doesn't feel like the app has hung. Flipped to false when
     * bootstrap completes or errors out — the SSE event loop continues
     * running either way.
     */
    private val _bootstrapping = MutableStateFlow(false)
    val bootstrapping: StateFlow<Boolean> = _bootstrapping.asStateFlow()

    /**
     * Ships in our curated %contacts *book* (`/x/v1/book`) — the
     * contacts we've deliberately added, as opposed to the much
     * broader `/v1/all` peer directory that populates the `contacts`
     * table. The Contacts screen and the "Add to contacts" affordances
     * key off this so "already a contact" means "in my book", not
     * "any peer my ship has ever heard of". Hydrated from /v1/book on
     * bootstrap and kept in sync by %contacts /v1/news page/wipe facts;
     * add/remove update it optimistically. Not persisted — re-scried
     * every login.
     */
    private val _bookContacts = MutableStateFlow<Set<String>>(emptySet())
    val bookContacts: StateFlow<Set<String>> = _bookContacts.asStateFlow()

    /**
     * The chat the user is currently viewing, if any. Used to suppress
     * unread-badge bumps for that whom — if they're looking at it,
     * any new messages are effectively already read. Set from UI
     * lifecycle hooks (DmChatScreen mount/unmount).
     */
    @Volatile private var openWhom: String? = null

    /**
     * Whether the app is actually in front of the user — foregrounded on
     * Android, window-focused on desktop. A chat left open in a
     * backgrounded app or an unfocused/minimized window is NOT being
     * read, so incoming messages there must still raise the unread badge
     * and fire a notification. Driven by UI lifecycle via [setForeground].
     */
    @Volatile private var appForegrounded: Boolean = true

    /**
     * The chat the user is *actively viewing right now*: the open chat,
     * but only while the app is foregrounded. This is the gate for
     * auto-marking incoming messages read — see [applyActivityUpdate].
     * Reading [openWhom] alone (the old behaviour) silently marked DMs
     * read whenever the app was backgrounded with that chat still open.
     */
    private fun focusedWhom(): String? = if (appForegrounded) openWhom else null

    /** The thread the user is reading, as [openWhom] is the chat. Set
     *  by the thread list and by the gallery / notebook post screens
     *  (whose comments are that post's thread). */
    private var openThread: Pair<String, String>? = null
    private fun focusedThread(): Pair<String, String>? = if (appForegrounded) openThread else null

    fun setOpenThread(whom: String?, parentId: String?) {
        val next = if (whom != null && parentId != null) whom to parentId else null
        val prev = openThread
        openThread = next
        if (prev != null && prev != next) {
            // Leaving: the read waiting for replies that landed while open,
            // if there is one; nothing new, nothing to tell the ship.
            flushReadSoon("${prev.first}#${prev.second}") { markThreadRead(prev.first, prev.second, force = true) }
        }
        if (next != null && prev != next) {
            scope.launch { runCatching { markThreadRead(next.first, next.second) } }
        }
    }

    fun setOpenChat(whom: String?) {
        val prev = openWhom
        openWhom = whom
        io.nisfeb.talon.notify.NotificationFocus.openWhom = whom
        if (prev != null && prev != whom) {
            // Leaving: the read waiting for what arrived while it was open,
            // if there is one. It used to poke the ship on every exit,
            // nothing new or not.
            flushReadSoon(prev) { markRead(prev, force = true) }
        }
        if (whom != null && prev != whom) {
            scope.launch { runCatching { markRead(whom) } }
        }
    }

    /**
     * UI lifecycle hook: the app moved to / from the foreground (Android
     * ON_START/ON_STOP) or the desktop window gained / lost focus. On
     * regaining focus with a chat still open, the user is looking at it
     * again, so mark anything that arrived while away as read.
     */
    fun setForeground(foreground: Boolean) {
        val was = appForegrounded
        appForegrounded = foreground
        if (foreground && !was) {
            openWhom?.let { whom -> scope.launch { runCatching { markRead(whom) } } }
        }
    }

    // settingsSync is now a constructor parameter (nullable). The production
    // app/ class still constructs its own internally and exposes a
    // non-nullable property for the 37 UI callers reading repo.settingsSync.X
    // directly; commonMain uses the interface, and external commonMain
    // callers only ever invoke this repo's own methods, which null-guard.

    /**
     * Client for the %notes agent (Tlon v12 Markdown notebooks). Owned
     * here rather than injected because it needs nothing this repo
     * doesn't already have, and it rides the same channel: the session
     * loop attaches it on connect and routes its facts in applyEvent.
     * Ships older than webapp v12 have no %notes agent, in which case
     * its bootstrap no-ops and the notes tables stay empty.
     */
    val notes: NotesRepo = NotesRepo(db, scope)

    /**
     * Called once per incoming message delta from another author, after
     * the row has been written to Room. UI layers wire this to their
     * notification / in-app banner logic. A reply comes only from a
     * thread that counts for the owner (followed, a DM's, their own: see
     * [threadCounts]), and always with `replyToUs` true: following is
     * asking for it, so a chat at "mentions only" still lets it through.
     */
    @Volatile var messageListener: ((MessageEntity, Boolean) -> Unit)? = null

    /**
     * A chat the ship says is read to the end, here or on any other client:
     * its notifications can go, as Tlon's %notify dismisses them. Only on
     * a read, not on any count of zero: a chat whose unreads the ship does
     * not count still notifies here.
     */
    @Volatile var readListener: ((whom: String) -> Unit)? = null

    /** A live message from someone else, to the listener. */

    /**
     * Called once per newly-arrived pending DM request (a ship that just
     * opened a DM with us, live — not on bootstrap). UI wires this to the
     * notification path, same as [messageListener]. The ship is the
     * inviter's patp.
     */
    @Volatile var dmInviteListener: ((ship: String) -> Unit)? = null

    /**
     * Fired for each newly-arrived group invite (not ones already
     * pending at launch). Mirrors [dmInviteListener] — the app wires it
     * to a tray/system notification so a group invite is as hard to
     * miss as a DM request.
     */
    @Volatile var groupInviteListener: ((InviteSummary) -> Unit)? = null

    /** A channel and an identity without the session loop, for tests of what a write does before the ship answers. */
    internal fun attachForTest(ch: UrbitChannel, us: String, http: HttpClient? = null) {
        channel = ch
        ourPatp = us
        this.http = http
    }

    /**
     * [stop], then wait for what was already running to finish: the
     * session and its bootstrap, and the writes on [pushScope], which get
     * [pokeGraceMs] to land before they are cancelled too. The database
     * closes only after this (see closeAfterWork): a query still running
     * when it closes is a native crash, and cancelling does not stop the
     * query a coroutine is in.
     */
    suspend fun stopAndJoin(pokeGraceMs: Long = 3_000) {
        stop()
        scope.coroutineContext[kotlinx.coroutines.Job]?.join()
        val pushes = pushScope.coroutineContext[kotlinx.coroutines.Job]
        if (pokeGraceMs > 0) kotlinx.coroutines.withTimeoutOrNull(pokeGraceMs) { pushes?.children?.forEach { it.join() } }
        pushes?.cancelAndJoin()
    }

    /** For tests: [stopAndJoin] without waiting for pokes to land. */
    internal suspend fun stopAndJoinForTest() = stopAndJoin(pokeGraceMs = 0)

    /** The ship's word on what is unread, read as a connect reads it. */
    internal suspend fun bootstrapActivityForTest() = bootstrapActivity(channel!!)

    /** The ship's follows, read as a connect reads them. */
    internal suspend fun bootstrapFollowedThreadsForTest() = bootstrapFollowedThreads(channel!!)

    /** As if the last full pass ran [byMs] ago and the stream last heard anything [heardMs] ago (null: as it is). */
    internal fun ageForTest(byMs: Long, heardMs: Long? = null) {
        lastBootstrapMs -= byMs
        heardMs?.let { lastEventMs = nowMs() - it }
    }

    fun start(session: UrbitSession) {
        if (started) return
        started = true
        ourPatp = session.ourPatp
        // What a ship serves is learnt once a login, not again on every
        // reconnect: a new login may be another ship, or this one upgraded.
        servedPath.clear()
        subServed.clear()
        walkServed.clear()
        http = session.http
        baseUrl = session.baseUrl
        scope.launch {
            // One-shot cleanup for anyone whose DM history was doubled
            // by the applyChatDelta dotted-id bug. Idempotent: on a
            // clean DB it's a no-op.
            runCatching { dedupeDottedIds() }
                .onFailure { Log.w(TAG, "dotted-id dedupe failed", it) }
            runSessionLoop(session)
        }
        // Rows stored before searchText existed get it, a batch at a
        // time; until then search reads their JSON as it used to.
        scope.launch {
            runCatching { while (db.messages().fillSearchText(500) > 0) yield() }
                .onFailure { Log.w(TAG, "search text fill failed", it) }
        }
    }

    /**
     * Walk every row whose id / postId is dot-grouped and collapse it
     * onto its undotted twin (or rename in place if none exists). See
     * [DottedIdDedupe] for the planner. Cheap — runs once per boot,
     * the `LIKE '%.%'` scan is indexed by the primary key path and
     * skips the common undotted case entirely.
     */
    internal suspend fun dedupeDottedIds() {
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
                    db.messages().upsertWithMedia(db.messageMedia(), op.to)
                    db.messages().hardDelete(op.from.whom, op.from.id)
                    db.messageMedia().deleteForMessage(op.from.whom, op.from.id)
                    renamed++
                }
                is DedupeOp.Drop -> {
                    db.messages().hardDelete(op.whom, op.dottedId)
                    db.messageMedia().deleteForMessage(op.whom, op.dottedId)
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
     * Forever-loop the SSE session. Each iteration resumes the last
     * channel while the ship keeps it, or opens a fresh one, subscribes
     * and re-scries recent state; then it drains events until the stream
     * errors or ends. On failure we wait with
     * exponential backoff (capped) and reconnect — handles doze-wake,
     * network blips, and server-side channel timeouts transparently.
     */
    private suspend fun runSessionLoop(session: UrbitSession) {
        var backoffMs = 2_000L
        var firstRun = true
        while (scope.isActive && started) {
            val ended = runCatching { runSessionOnce(session, firstRun) }
                .onFailure { Log.w(TAG, "session iteration ended", it) }
            // A channel the ship reaped is no trouble: the next pass opens a
            // new one at once. Its replay went with it, so that pass reads
            // everything, however short the outage looks. One refused to
            // this login waits out the backoff first: if the login lapsed,
            // the new channel is refused too.
            val gone = ended.exceptionOrNull() as? ChannelGone
            if (gone != null) lastBootstrapMs = 0L
            val ok = endedWell(ended.exceptionOrNull())
            if (!scope.isActive || !started) break
            firstRun = false
            // Back off exponentially after failures; reset on a normal
            // "stream completed" exit (rare, but polite).
            backoffMs = if (ok) 2_000L else (backoffMs * 2).coerceAtMost(60_000L)
            // Jitter. A ship restart drops every client at the same
            // instant, so a fixed delay brings them all back on the same
            // tick and the ship meets the whole fleet at once.
            // [forceReconnect] cuts the wait short: one person coming back
            // to the app is not a fleet. On iOS the stream is often already
            // dead by then, the loop sitting in a backoff of up to a
            // minute, and the new messages waited for all of it.
            val woken = withTimeoutOrNull(jittered(backoffMs)) { reconnectWake.receive() } != null
            if (woken) backoffMs = 2_000L
        }
    }

    /** Set by [forceReconnect]; wakes the loop out of its backoff. */
    private val reconnectWake = Channel<Unit>(Channel.CONFLATED)

    /** Set by [forceReconnect], read once by the next connect: see [runSessionOnce]. */
    @Volatile private var forcedReconnect = false

    private suspend fun runSessionOnce(session: UrbitSession, firstRun: Boolean) = coroutineScope {
        // When the old stream last heard anything: how long this outage was.
        val heardBeforeMs = lastEventMs
        // The last channel, while the ship keeps it. Eyre holds a channel,
        // its subscriptions and every event since the last ack for twelve
        // hours after its stream drops, and a stream opened on it again
        // starts after the last event applied here. ~ricsul, too busy to
        // send its keepalives, cut every stream at 45 s, and each reconnect
        // made a new channel: every watch again, and on a ship that slow
        // the outage always looked long, so the whole pass ran as well.
        val kept = channel?.takeIf { !it.gone }
        val ch = kept ?: session.openChannel()
        channel = ch
        lastEventMs = nowMs()
        notificationHealth.markSseConnected(true)
        notificationHealth.markSseEvent(lastEventMs)
        if (kept != null) {
            Log.i(TAG, "resuming the channel")
            // What came while it was down is replayed: nothing to read again.
            forcedReconnect = false
            pushScope.launch { runCatching { drainQueue() }.onFailure { Log.w(TAG, "queue not sent", it) } }
        } else {
            Log.i(TAG, "opening channel (firstRun=$firstRun)")
            // Pagination markers are per-(channel, ship). A new channel
            // means the ship may surface old history we previously couldn't
            // reach (e.g. backfill arrived during the disconnect). Drop the
            // markers so loadOlder retries them on demand.
            paginationExhausted.clear()

            // Subscribe to all streams we care about. Failures here don't
            // skip the event-loop — a partial subscribe set still delivers
            // whatever the server accepted. Run in parallel: each is one
            // round-trip and they're independent, so the serial form was
            // 5× the wall time for no reason.
            // Groups subscribe catches new channels added to existing
            // groups + meta edits — without it, channel-add is only picked
            // up on reconnect (bootstrap re-scry).
            // %presence shipped with Tlon v11.4.0. On an older ship the
            // agent isn't installed and this subscribe nacks — harmless,
            // the nack is logged by applyEvent and typing simply never
            // shows. Same for the pokes in setTyping/clearTyping.
            //
            // %activity /v5 is where %react and %dm-react events live:
            // /v4's down-conversion returns ~ for them, so the agent emits
            // no v4 fact at all and reaction notifications never arrive.
            // But /v5 only exists on v11.4.0+, and an older ship's watch
            // arm crashes on it — which would cost us *every* activity
            // event, not just reactions. So ask for /v5 and let the nack
            // walk us back to /v4.
            subFallbacks.clear()
            subPaths.clear()
            // What streamed while away is not in an opened chat yet: read again.
            readOnOpen.clear()
            listOf(
                SubSpec("chat", "/v4"),
                SubSpec("channels", "/v4"),
                // /v6 (12.1.0) carries notebook and note sources, which v5
                // and v4 leave out: notebooks had no unreads here.
                subSpecs[0],
                SubSpec("contacts", "/v1/news"),
                // /v3 (12.2.0) is what Tlon's own client watches: same
                // envelope, plus `blob` and `active-channel`, which go to
                // Unknown. /v1 is no longer called by Tlon's clients, which
                // makes it removable under its N-1 policy.
                subSpecs[1],
                SubSpec("presence", "/v1"),
                // Invites live here, not on the content subscriptions above:
                // %chat pushes the pending-DM list on /dm/invited (never on
                // /v4), and %groups pushes gang/invite updates on
                // /gangs/updates. Without these two, a new invite only
                // surfaced on the next reconnect's bootstrap scry — or, for
                // groups, never, since refreshInvites ran only when the
                // Invites screen was opened.
                SubSpec("chat", "/dm/invited"),
                // /v1/foreigns is where Tlon's client hears of group invites;
                // the desk marks /gangs/updates deprecated (12.3.0). The same
                // change goes out on both, a flag-keyed map either way.
                subSpecs[2],
            ).let { specs ->
                // Where this ship stopped refusing last time, if it did: a
                // reconnect walked the refusals again, every time.
                val plans = specs.map { spec ->
                    val all = listOf(spec.path) + spec.fallbacks
                    Triple(spec, all, subServed["${spec.app}${spec.path}"] ?: 0)
                }
                // All in one PUT: one each was an event on the ship apiece.
                runCatching { ch.subscribeAll(plans.map { (spec, all, from) -> spec.app to all[from] }) }
                    .onSuccess { ids ->
                        ids.zip(plans).forEach { (id, plan) ->
                            val (spec, all, from) = plan
                            subPaths[id] = spec.app to all[from]
                            val rest = all.drop(from + 1)
                            if (rest.isNotEmpty()) subFallbacks[id] = spec.app to rest
                        }
                    }
                    .onFailure { Log.e(TAG, "subscribe failed", it) }
            }

            // Re-scry init-posts + activity every reconnect so we catch up on
            // anything that landed while the stream was down. The
            // `_bootstrapping` flag drives a top-of-screen progress bar so
            // the silent fetch on first launch doesn't read as
            // "the app has hung."
            //
            // Two-stage history load: a small 10-per-source scry runs
            // synchronously (fast first paint, ~1-2s on a busy ship) and
            // counts as "bootstrapping done" the moment it lands. The
            // larger 50-per-source scry runs in the background after,
            // reactively upserting older history into the same tables —
            // the UI is interactive throughout. Without this, a heavy
            // ship's full init-posts payload took 30+ seconds and either
            // timed out the scry or left the user staring at a progress
            // bar with no idea if anything was happening. See `bootstrap`
            // for the count semantics.
            // A reconnect this soon after the last full pass re-registers
            // its subscriptions and nothing else. See the client-conduct
            // rules: a reconnect must be cheap.
            val sinceBootstrapMs = nowMs() - lastBootstrapMs
            val skipBootstrap = !shouldBootstrap(firstRun, lastBootstrapMs, nowMs(), heardBeforeMs)
            // A connect somebody asked for (back to the app, the network back)
            // still reads the recent messages and the unread counts inside the
            // window: the stream was down while they were away, and what landed
            // then comes no other way. Without it, a quick trip out of an iOS
            // app lost that minute's messages until some later reconnect.
            val forced = forcedReconnect
            forcedReconnect = false
            val reconnectAskedMs = lastReconnectMs
            if (skipBootstrap) {
                Log.i(TAG, "reconnected ${sinceBootstrapMs}ms after the last bootstrap; re-subscribed only")
            }
            if (firstRun) _bootstrapping.value = true
            try {
                // The group list is reconciled on EVERY connect, throttle or
                // not. A group joined while the stream was down does not
                // arrive as a fact when we re-subscribe, so without this a
                // join can stay invisible until the app restarts — the exact
                // bug a fresh comet hit. One scry is not the expensive
                // bootstrap the throttle exists to prevent.
                val groupsJob = async {
                    runCatching { bootstrapGroups(ch) }
                        .onFailure { Log.e(TAG, "groups scry failed", it) }
                }
                // Skipped when a reconnect lands right after the last
                // pass; the subscriptions above are re-registered either way.
                if (!skipBootstrap) {
                    // Parallel-fan-out the bootstrap scries — each is a network
                    // round-trip and they write to disjoint tables, so running
                    // them serially burned 4× wall time for no reason. Failures
                    // are caught per-job so a slow one doesn't poison the rest.
                    //
                    // - initPosts: chat/channel history + reactions
                    // - activity: unread + notify counts → also marks reconcile
                    //   success on notificationHealth
                    // - contacts: status / nickname / color updates that the
                    //   live %contacts /v1/news subscribe doesn't replay on
                    //   reconnect
                    // - channel orders: pin/unpin state, same reconnect-replay
                    //   gap as contacts
                    //
                    // Clubs stay in a firstRun-only branch (the %chat /v4
                    // subscription covers edits adequately on reconnect).
                    // Groups do not: a group joined while the channel was down
                    // never arrives as a fact, so the list is reconciled from a
                    // scry on every connect. Seen on a fresh comet, whose join
                    // landed while the ship was busy and the channel cycled.
                    val initJob = async {
                        runCatching { catchUpPosts(ch) }
                            .onFailure { Log.e(TAG, "initPosts scry failed", it) }
                    }
                    val activityJob = async {
                        runCatching { bootstrapActivity(ch) }
                            .onSuccess { notificationHealth.markReconcileSuccess() }
                            .onFailure { Log.e(TAG, "activity scry failed", it) }
                    }
                    val followsJob = async {
                        runCatching { bootstrapFollowedThreads(ch) }
                            .onFailure { Log.w(TAG, "followed threads scry failed", it) }
                    }
                    val contactsJob = async {
                        runCatching { bootstrapContacts(ch) }
                            .onFailure { Log.e(TAG, "contacts scry failed", it) }
                    }
                    val ordersJob = async {
                        runCatching { bootstrapChannelOrders(ch) }
                            .onFailure { Log.e(TAG, "channel orders scry failed", it) }
                    }
                    // A request that arrived while this session was down is
                    // news on a reconnect, so it notifies; only the first
                    // pass of a session stays quiet.
                    val dmInvitesJob = async {
                        runCatching { bootstrapDmInvites(ch, notify = !firstRun) }
                            .onFailure { Log.e(TAG, "dm-invites scry failed", it) }
                    }
                    // Group invites had no bootstrap at all — refreshInvites ran
                    // only when the Invites screen was opened, so an invite that
                    // arrived while you weren't looking never lit the badge.
                    // notify=false: populate the badge, don't fire a toast for
                    // invites that were already pending before this launch.
                    val groupInvitesJob = async {
                        runCatching { refreshInvites(notify = false) }
                            .onFailure { Log.e(TAG, "group-invites scry failed", it) }
                    }
                    val firstRunJobs = if (firstRun) {
                        listOf(
                            async {
                                runCatching { bootstrapClubs(ch) }
                                    .onFailure { Log.e(TAG, "clubs scry failed", it) }
                            },
                        )
                    } else emptyList()
                    (
                        listOf(initJob, activityJob, followsJob, contactsJob, ordersJob, dmInvitesJob, groupInvitesJob) +
                            firstRunJobs
                        ).awaitAll()
                    lastBootstrapMs = nowMs()
                } else if (forced) {
                    listOf(
                        async {
                            runCatching { catchUpPosts(ch) }
                                .onFailure { Log.e(TAG, "initPosts scry failed", it) }
                        },
                        async {
                            runCatching { bootstrapActivity(ch) }
                                .onSuccess { notificationHealth.markReconcileSuccess() }
                                .onFailure { Log.e(TAG, "activity scry failed", it) }
                        },
                    ).awaitAll()
                }
                if (forced) Log.i(TAG, "reconnect asked for: messages read ${nowMs() - reconnectAskedMs}ms later")
                groupsJob.await()
            } finally {
                if (firstRun) _bootstrapping.value = false
            }
            // The ship is back: what waited for it goes, now that the reading
            // above has reaped any queued channel post that landed after all.
            pushScope.launch { runCatching { drainQueue() }.onFailure { Log.w(TAG, "queue not sent", it) } }

            // Stage two: deep history fill-out. Fires on firstRun only —
            // reconnects already pulled the same window via the small
            // scry above, and the bigger one would just re-download the
            // same data. Run on the session scope so a stop()/teardown
            // cancels it cleanly, and so the SSE collect job below
            // (the next thing this function does) starts immediately
            // rather than waiting on a network call we don't need to
            // complete before showing the UI.
            // Only with nothing kept, or nothing newer than a day: otherwise
            // the ten above and the read on opening a chat cover it, and this
            // is 50 posts from every channel on every launch. The admin
            // groups (a scry per group) are read when a group message's menu
            // opens, not here.
            if (firstRun && needsDeepHistory(db.messages().newestSentMs(), nowMs())) {
                launch {
                    Log.i(TAG, "deep-history scry starting (count=$DEEP_PAGE_COUNT)")
                    runCatching { bootstrap(ch, count = DEEP_PAGE_COUNT) }
                        .onSuccess { Log.i(TAG, "deep-history scry complete") }
                        .onFailure { Log.w(TAG, "deep-history scry failed", it) }
                }
            }

            // Settings sync — scries our desk and subscribes so changes
            // from other devices stream in. Run on every connect, not just
            // firstRun: an Urbit subscribe doesn't replay missed events, so
            // any %settings change made on another device while this SSE
            // was zombie (doze, screen off, network blip) would be silently
            // lost forever — the watchdog at the bottom of this function
            // restores the connection but not the missed payload, and the
            // user only catches up on a full app kill+relaunch.
            // A reconnect nobody asked for, inside a minute of the last full
            // pass, only watches again: the gap is that minute, and a whole
            // desk and the notebook list per reconnect is what a loop costs.
            val watchOnly = skipBootstrap && !forced
            settingsSync?.attach(ch)
            if (settingsSync != null) {
                runCatching { if (watchOnly) settingsSync.resubscribe() else settingsSync.bootstrap() }
                    .onFailure { Log.e(TAG, "settings bootstrap failed", it) }
            }

            // %notes (v12 Markdown notebooks). Scries the notebook list and
            // subscribes to each notebook's stream. A pre-v12 ship has no
            // such agent — bootstrap logs and returns, leaving the tables
            // empty, so this is safe to run unconditionally.
            notes.attach(ch)
            runCatching { if (watchOnly) notes.resubscribe() else notes.bootstrap() }
                .onFailure { Log.w(TAG, "notes bootstrap failed", it) }
        }

        // Watchdog: if nothing at all arrives on the stream for 90s the
        // SSE is a zombie (doze-frozen or server-side dropped) — cancel
        // the collect job so runSessionLoop reconnects. "Nothing at all"
        // counts eyre's heartbeats, which arrive every ~25s on the
        // quietest ship: measuring events alone made this fire on every
        // idle ship, forever, and each reconnect re-ran the whole
        // bootstrap. See UrbitChannel.streamIdleMs.
        // Acks ride their own queue instead of being awaited inline. Each
        // ack is a network PUT to the ship; awaiting it after every event
        // capped ingestion at one round-trip per event, so a burst of
        // events (and the poke-echo that un-greys a just-sent message)
        // drained only as fast as we could ack — the "pending stays on
        // screen long after it posted" lag. Draining acks on a separate
        // coroutine lets applyEvent (which does the reap) run at DB speed;
        // the single consumer keeps acks in order and Eyre tolerates them
        // arriving a beat behind ingestion.
        val ackQueue = Channel<Long>(Channel.UNLIMITED)
        val ackJob = launch {
            // In batches: one ack covers every event before it.
            ackInBatches(ackQueue) { ch.ack(it) }
        }
        val collectJob = launch {
            try {
                ch.events().collect { event ->
                    lastEventMs = nowMs()
                    notificationHealth.markSseEvent(lastEventMs)
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
                    event.id?.let {
                        ch.applied(it)
                        ackQueue.trySend(it)
                    }
                }
            } finally {
                // Close on normal completion AND cancellation so the ack
                // consumer drains what's queued and exits with us.
                ackQueue.close()
                // The channel stays: the next stream resumes it. It is
                // deleted where it is given up, in stop() and when the ship
                // dropped one of its subscriptions (see applyEvent).
            }
            Log.w(TAG, "event stream completed; will reconnect")
        }
        collectJob.invokeOnCompletion { ackJob.cancel() }
        sessionJob = collectJob
        // A reconnect asked for while this one was connecting is answered
        // by it; left pending, it would skip the jitter on some later drop.
        reconnectWake.tryReceive()
        val watchdogJob = launch {
            while (isActive && collectJob.isActive) {
                delay(30_000L)
                val idleMs = minOf(nowMs() - lastEventMs, ch.streamIdleMs)
                if (idleMs > 90_000L) {
                    Log.w(TAG, "watchdog: ${idleMs}ms without event; force-reconnect")
                    notificationHealth.incrementForceReconnects()
                    notificationHealth.markSseConnected(false)
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
     * will observe the collect job completing and open the stream again,
     * on the same channel while the ship keeps it. Safe to call from any
     * thread.
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
        val now = nowMs()
        if (now - lastReconnectMs < FORCE_RECONNECT_DEBOUNCE_MS) {
            Log.i(TAG, "forceReconnect skipped (recent)")
            return
        }
        lastReconnectMs = now
        Log.i(TAG, "forceReconnect requested")
        forcedReconnect = true
        sessionJob?.cancel()
        reconnectWake.trySend(Unit)
    }
    @Volatile private var lastReconnectMs: Long = 0L

    /**
     * Reconnect only if the stream has gone quiet. Eyre sends something at
     * least every ~20 s, so bytes within [STREAM_FRESH_MS] mean the socket
     * is live and nothing was missed. Android's foreground service keeps
     * the socket up, and tearing it down on every return to the app (back
     * from the image picker included) cost a delete, every subscribe and
     * the catch-up reads. iOS and desktop keep [forceReconnect]: there the
     * socket is usually dead on return.
     */
    fun reconnectIfStale() {
        val ch = channel
        if (ch != null && ch.streamIdleMs < STREAM_FRESH_MS) {
            Log.i(TAG, "reconnect skipped: stream live (${ch.streamIdleMs} ms)")
            return
        }
        forceReconnect()
    }

    /**
     * Re-scry init-posts + activity without reopening the channel. Cheap
     * enough to call every time the app comes to the foreground — closes
     * the "messages came in while I was away" gap that doze sometimes
     * opens up even when the service stayed alive.
     */
    fun catchUp() {
        val ch = channel ?: return
        // A stream with bytes in the last half minute has missed nothing:
        // a screen-on or the 15-minute worker re-read the recent slice of
        // every chat and the whole activity anyway, a healthy socket or not.
        if (ch.streamIdleMs < STREAM_FRESH_MS) return
        scope.launch {
            // catchUp pulls just the recent slice — anything older was
            // already covered by the deep-history scry on the original
            // bootstrap. Smaller page = lower bandwidth on every wake
            // / network-change tick.
            runCatching { catchUpPosts(ch) }
                .onFailure { Log.w(TAG, "catchUp bootstrap failed", it) }
            runCatching { bootstrapActivity(ch) }
                .onSuccess { notificationHealth.markReconcileSuccess() }
                .onFailure { Log.w(TAG, "catchUp activity failed", it) }
        }
    }

    fun stop() {
        started = false
        // Closing the channel is a request to the ship that waits on it,
        // so it does not go on the pool the screens share.
        channel?.deleteSoon(kotlinx.coroutines.CoroutineScope(io.nisfeb.talon.util.ioDispatcher), timeoutMs = 3_000)
        channel = null
        notificationHealth.markSseConnected(false)
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
     * Power-user escape hatch: poke an arbitrary agent on this ship
     * with a raw JSON payload. Used by the `/poke` slash command,
     * gated upstream by the per-device power-features toggle.
     *
     * Throws if the channel isn't open. Caller is expected to
     * `runCatching` and surface failures via the composer's error
     * line — bad payloads can crash a receiving agent, but they
     * can't corrupt our local state.
     */
    /**
     * The ship's %trunk wire, 0 where %trunk is not installed or will not
     * say. Throws when not connected, so a caller can tell "not now" from
     * "no trunk".
     */
    /** The ship's %trunk status (wire 12, owner-only): its devices and when
     *  each was last pushed to, and what it dropped. Null before wire 12. */
    suspend fun trunkDebug(): kotlinx.serialization.json.JsonElement? {
        val ch = channel ?: return null
        return runCatching { ch.scry(io.nisfeb.talon.call.TrunkWire.AGENT, "/debug") }.getOrNull()
    }

    suspend fun trunkWire(): Int {
        val ch = channel ?: error("not connected")
        return runCatching {
            io.nisfeb.talon.call.TrunkWire.parseWireVersion(ch.scry(io.nisfeb.talon.call.TrunkWire.AGENT, "/version"))
        }.getOrDefault(0)
    }

    suspend fun pokeRaw(
        app: String,
        mark: String,
        payload: kotlinx.serialization.json.JsonElement,
    ): Long {
        val ch = channel ?: error("not connected")
        return ch.poke(app = app, mark = mark, payload = payload)
    }

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
        val content = buildJsonArray {
            add(citeBlock(quotedNest, quotedPostId))
            if (text.isNotBlank()) {
                textToStory(text).forEach { add(it) }
            }
        }
        return postContent(whom, content)
    }

    private fun citeBlock(nest: String, postId: String) = buildJsonObject {
        put("block", buildJsonObject {
            put("cite", buildJsonObject {
                put("chan", buildJsonObject {
                    put("nest", nest)
                    put("where", "/msg/$postId")
                })
            })
        })
    }

    /**
     * Send an image message. `src` is the hosted URL returned by
     * uploadImage; width/height are the image's natural dimensions (0 if
     * unknown); alt is a short description (often the filename); caption
     * is text written with it, which goes under it in the same message.
     * A quote ([quotedNest], [quotedPostId]) leads it, as in [sendQuote].
     */
    suspend fun sendImage(
        whom: String,
        src: String,
        width: Int,
        height: Int,
        alt: String,
        caption: String = "",
        quotedNest: String? = null,
        quotedPostId: String? = null,
    ): String = postContent(
        whom,
        buildJsonArray {
            if (quotedNest != null && quotedPostId != null) add(citeBlock(quotedNest, quotedPostId))
            imageStory(src, width, height, alt, caption).forEach { add(it) }
        },
    )

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
        val content = MarkdownBlocks.toStory(bodyMarkdown, tables = false)
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
        val sent = nowMs()
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
        val out = postPoke(whom, id, essay)
        // Channel-chat posts get a status="pending" optimistic insert;
        // DMs and clubs leave status null. A refusal marks the row
        // failed, a ship out of reach queues it; an accepted post stays
        // until its echo replaces it.
        val initialStatus = if (isChannelNest(whom)) "pending" else null
        db.messages().upsertWithMedia(
            db.messageMedia(),
            toEntity(whom, id, essay).copy(status = initialStatus),
        )
        sendOrQueue(whom, id, out)
        return id
    }

    /** One poke as it goes to the ship; what a queued message is sent again as. */
    private data class Outgoing(val app: String, val mark: String, val payload: JsonObject)

    private fun isChannelNest(whom: String) =
        whom.startsWith("chat/") || whom.startsWith("diary/") || whom.startsWith("heap/")

    /** A top-level post: the poke [postContent] sends, and [drainQueue] sends again. */
    private fun postPoke(whom: String, id: String, essay: JsonObject): Outgoing {
        val addDelta = buildJsonObject {
            put("add", buildJsonObject {
                put("essay", essay)
                put("time", JsonNull)
            })
        }
        return when {
            whom.startsWith("~") -> Outgoing("chat", "chat-dm-action-2", dmAction(whom, id, addDelta))
            whom.startsWith("0v") -> Outgoing("chat", "chat-club-action-2", clubAction(whom, id, addDelta))
            isChannelNest(whom) -> Outgoing(
                "channels", "channel-action-2",
                channelAction(whom, buildJsonObject { put("post", buildJsonObject { put("add", essay) }) }),
            )
            else -> error("unsupported whom: $whom")
        }
    }

    /** A reply: the poke [replyContent] sends, and [drainQueue] sends again. */
    private fun replyPoke(whom: String, parentId: String, replyId: String, replyEssay: JsonObject): Outgoing = when {
        whom.startsWith("~") -> Outgoing("chat", "chat-dm-action-2", dmAction(whom, parentId, replyDelta(replyId, replyEssay)))
        whom.startsWith("0v") -> Outgoing("chat", "chat-club-action-2", clubAction(whom, parentId, replyDelta(replyId, replyEssay)))
        // channels c-reply shape nests under action:, not c-reply:
        // Channel-action-2's `reply.id` dejs is `(se %ud)`; the agent
        // runs `slav %ud` which demands dot-grouped decimals for
        // values ≥ 1000.
        isChannelNest(whom) -> Outgoing(
            "channels", "channel-action-2",
            channelAction(whom, buildJsonObject {
                put("post", buildJsonObject {
                    put("reply", buildJsonObject {
                        put("id", dotAtom(parentId))
                        put("action", buildJsonObject { put("add", replyEssay) })
                    })
                })
            }),
        )
        else -> error("unsupported whom: $whom")
    }

    /** A queued message's poke, from its row: what was sent, as it was sent. Null where the row cannot say. */
    private fun resendPoke(row: MessageEntity): Outgoing? = runCatching {
        val content = Json.parseToJsonElement(row.contentJson) as JsonArray
        val parent = row.parentId
        if (parent == null) {
            val meta = if (row.kind == "/diary") buildJsonObject {
                put("title", row.title.orEmpty())
                put("image", row.image.orEmpty())
                put("description", "")
                put("cover", "")
            } else null
            postPoke(row.whom, row.id, buildEssay(content, row.sentMs, kind = row.kind, meta = meta))
        } else {
            replyPoke(row.whom, parent, row.id, buildJsonObject {
                put("content", content)
                put("author", ourPatp)
                put("sent", row.sentMs)
                put("blob", JsonNull)
            })
        }
    }.getOrNull()

    /**
     * Send [out] for our message [id], on the repo's scope: leaving the
     * screen cancelled a send half done. The ship's refusal marks the row
     * failed and is thrown. A ship that is slow or out of reach has not
     * refused anything: the message is queued, and goes when it answers
     * again ([drainQueue]). A timed-out write has often landed anyway,
     * and the resend makes sure of it without a second copy.
     */
    private suspend fun sendOrQueue(whom: String, id: String, out: Outgoing) = pushScope.async {
        // Behind a message of this conversation that is waiting for the
        // ship: queued too, so it is counted with it and goes after it.
        // Sent straight on, it sat greyed out and uncounted beside "1
        // queued", and in a channel, where the ship numbers posts as they
        // arrive, could land ahead of the one written before it.
        if (db.messages().queuedIn(whom) > 0) {
            neverSent.add("$whom|$id")
            db.messages().setStatus(whom, id, "queued")
            scheduleDrain(0)
            return@async
        }
        val ch = channel ?: return@async queueMessage(whom, id, IllegalStateException("not connected to the ship"))
        try {
            val start = nowMs()
            ch.poke(app = out.app, mark = out.mark, payload = out.payload)
            // eyre holds the PUT until the agent has taken the poke, so a
            // slow number here is the ship's compute, not our wire.
            val putMs = nowMs() - start
            if (putMs > 1_000) Log.w(TAG, "slow poke PUT whom=$whom putMs=$putMs (ship busy)")
            shipAnswered()
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            if (t is PokeNacked) {
                db.messages().setStatus(whom, id, "failed")
                throw t
            }
            queueMessage(whom, id, t)
        }
    }.await()

    private suspend fun queueMessage(whom: String, id: String, why: Throwable) {
        db.messages().setStatus(whom, id, "queued")
        stillSlow(why)
    }

    // ── Queued writes: a slow ship is not a refusal ─────────────────

    /** A reaction, or taking ours off ([glyph] null), waiting for the ship. */
    private data class QueuedReact(val whom: String, val postId: String, val parentId: String?, val glyph: String?) {
        val key get() = "$whom|$postId|${parentId.orEmpty()}"
    }

    /** The newest intention per post: a reaction queued, then taken off, sends the taking off. */
    private val queuedReacts = MutableStateFlow<Map<String, QueuedReact>>(emptyMap())

    /**
     * A read the ship has not heard, by whom (or whom#parent for a thread),
     * with when it was read. A read that timed out was lost: the badge was
     * already cleared here, so the next open sent nothing, and the ship's
     * next word brought the same messages back as unread, again and again.
     */
    private data class OwedRead(val key: String, val source: JsonObject, val readAtMs: Long)

    private val owedReads = MutableStateFlow<Map<String, OwedRead>>(emptyMap())

    /**
     * Whether the ship's word on what is unread has been read since this
     * connection began. Until it has, the counts here may be stale, so a
     * chat with none here is read on the ship anyway: skipped, its unread
     * there came back when the slow read of them finally landed.
     */
    @kotlin.concurrent.Volatile private var activityKnown = false

    /**
     * Whether a count from the ship for [key] is one the owner has read and
     * the ship has not heard of yet: nothing in it newer than the read.
     */
    /**
     * A chat's row as shown here. The chat being looked at is read, and
     * the ship is told, so other clients drop the badge too; one read here
     * and owed shows read, its count being from before.
     */
    private fun localView(row: UnreadEntity, focused: String?): UnreadEntity {
        if (row.whom == focused) {
            if (row.count > 0 || row.notifyCount > 0) markReadSoon(row.whom) { markRead(row.whom, force = true) }
            return row.copy(count = 0, notifyCount = 0)
        }
        return if (readHere(row.whom, row.recencyMs)) row.copy(count = 0, notifyCount = 0) else row
    }

    private fun readHere(key: String, recencyMs: Long): Boolean =
        owedReads.value[key]?.let { recencyMs <= it.readAtMs } == true

    /** What the last write that could not reach the ship said, for "Copy error details"; null once the queue is empty. */
    private val slowDetails = MutableStateFlow<String?>(null)

    /** What vere's healthz said when the last write could not reach the ship; null until asked. */
    private val slowHealth = MutableStateFlow<ShipHealth?>(null)

    /**
     * The ship is slow: [queued] writes wait for it, [details] is what the
     * last try said, and [health] whether it is busy, down or out of reach.
     */
    data class ShipSlow(val queued: Int, val details: String, val health: ShipHealth? = null)

    /**
     * Whether writes are waiting for the ship, for the calm line in a
     * chat: messages queued there and reactions queued here. Null while
     * the ship is keeping up.
     */
    val shipSlow: Flow<ShipSlow?> = combine(db.messages().queuedCount(), queuedReacts, slowDetails, slowHealth) { messages, reacts, details, health ->
        val queued = messages + reacts.size
        if (queued == 0) null
        else ShipSlow(queued, (details ?: "Waiting for the ship.") + (health?.let { "\nhealthz: $it" } ?: ""), health)
    }

    private val drainLock = Mutex()
    @Volatile private var drainJob: Job? = null
    @Volatile private var drainPauseMs = FIRST_DRAIN_PAUSE_MS

    /** The ship took a write: whatever waited can go now. */
    private fun shipAnswered() {
        drainPauseMs = FIRST_DRAIN_PAUSE_MS
        slowHealth.value = null
        // The ship is back: a drain sleeping out its backoff goes now. Left
        // to sleep, a message queued behind one that waited sat out the
        // rest of a backoff of up to a minute with the ship answering.
        if (queuedReacts.value.isNotEmpty() || owedReads.value.isNotEmpty() || slowDetails.value != null) scheduleDrain(0, wake = true)
    }

    /** A write did not reach the ship: say why, and try again later, a little later each time. */
    private fun stillSlow(why: Throwable) {
        slowDetails.value = io.nisfeb.talon.util.errorDetailsOf(why)
        // Busy, down or out of reach, asked of vere itself: no event on a
        // ship that is already behind.
        channel?.let { ch -> pushScope.launch { slowHealth.value = ch.health() } }
        val pause = drainPauseMs
        drainPauseMs = (pause * 2).coerceAtMost(MAX_DRAIN_PAUSE_MS)
        scheduleDrain(pause)
    }

    /** True while the drain job is waiting out its backoff, not yet sending. */
    @kotlin.concurrent.Volatile private var drainSleeping = false

    /**
     * Drain after [afterMs]. A drain already scheduled stands, unless it is
     * still asleep and [wake] says the ship has answered since.
     */
    private fun scheduleDrain(afterMs: Long, wake: Boolean = false) {
        val job = drainJob
        if (job?.isActive == true) {
            if (!(wake && drainSleeping)) return
            job.cancel()
        }
        drainJob = pushScope.launch {
            drainSleeping = true
            try {
                delay(if (afterMs == 0L) 0L else jittered(afterMs))
            } finally {
                drainSleeping = false
            }
            drainQueue()
        }
    }

    /**
     * Messages queued behind another of their conversation, never sent:
     * whom|id. Such a message cannot have landed, so the drain does not
     * read the channel to ask, a read on a slow ship for every one of them.
     * Forgotten at the first send of it, so an interrupted one is asked
     * about after all; kept in memory only, so after a restart all are.
     */
    private val neverSent = ConcurrentSet<String>()

    /**
     * Send what waited for the ship, oldest first: queued messages, then
     * queued reactions. Stops at the first write the ship still does not
     * take, to try again later. Runs after every reconnect and on a timer
     * while the ship is slow.
     *
     * A channel post is sent again only once the ship has been asked for
     * its newest posts and has none of ours from that moment: a %channels
     * post id is the ship's own, so a second send of one that timed out
     * but landed would post it twice. A DM or club message carries its own
     * id, which the ship takes once.
     */
    internal suspend fun drainQueue() = drainLock.withLock {
        val ch = channel ?: return@withLock
        // Read again until none are left: one queued while this ran, behind
        // one of its own conversation, waited otherwise for the next round.
        val tried = mutableSetOf<Pair<String, String>>()
        while (true) {
            val batch = db.messages().queued().filter { (it.whom to it.id) !in tried }
            if (batch.isEmpty()) break
            for (row in batch) {
                tried += row.whom to row.id
                val now = db.messages().getOne(row.whom, row.id) ?: continue
                if (now.status != "queued") continue
                val out = resendPoke(now)
                if (out == null) {
                    db.messages().setStatus(now.whom, now.id, "failed")
                    continue
                }
                try {
                    val untried = neverSent.remove("${now.whom}|${now.id}")
                    if (isChannelNest(now.whom) && !untried && landedAlready(now)) continue
                    // Queued until the ship takes it, label and count with it:
                    // marked sent before the send, it lost its label and stood
                    // grey and unexplained for as long as a slow ship took.
                    ch.poke(app = out.app, mark = out.mark, payload = out.payload)
                    db.messages().setStatus(now.whom, now.id, if (isChannelNest(now.whom)) "pending" else null)
                    // The ship took one: the next wait, if there is one, starts short.
                    drainPauseMs = FIRST_DRAIN_PAUSE_MS
                } catch (t: Throwable) {
                    if (goesOn(t, t is PokeNacked) { db.messages().setStatus(now.whom, now.id, "failed") }) continue
                    return@withLock
                }
            }
        }
        for (r in queuedReacts.value.values) {
            try {
                sendReact(ch, r)
                queuedReacts.dropIfSame(r.key, r)
            } catch (t: Throwable) {
                // Refused now: dropped, and the post's next reading shows the ship's word.
                if (goesOn(t, t is PokeNacked) { queuedReacts.dropIfSame(r.key, r) }) continue
                return@withLock
            }
        }
        // Reads the ship did not hear, up to when each was read: what came
        // in since stays unread.
        for (r in owedReads.value.values) {
            try {
                sendActivityRead(ch, r.source, upTo = r.readAtMs)
                owedReads.dropIfSame(r.key, r)
            } catch (t: Throwable) {
                if (goesOn(t, t is PokeNacked) { owedReads.dropIfSame(r.key, r) }) continue
                return@withLock
            }
        }
        // Follows made while the ship was slow.
        for (f in db.followedThreads().unsent()) {
            try {
                sendFollow(ch, f.whom, f.parentPostId, f.follow)
            } catch (t: Throwable) {
                // Refused, or the thread is not known here: the ship's word stands.
                if (goesOn(t, !isShipSlow(t)) { db.followedThreads().delete(f.whom, f.parentPostId) }) continue
                return@withLock
            }
        }
        slowDetails.value = null
        drainPauseMs = FIRST_DRAIN_PAUSE_MS
    }

    /**
     * After a queued write failed with [t]: one the ship refused ([drops])
     * goes ([drop]) and the drain moves on (true); otherwise the ship is
     * still slow, and the drain stops (false) to try again later.
     */
    private inline fun goesOn(t: Throwable, drops: Boolean, drop: () -> Unit): Boolean {
        if (t is kotlinx.coroutines.CancellationException) throw t
        if (drops) {
            drop()
            return true
        }
        drainJob = null
        stillSlow(t)
        return false
    }

    /**
     * Whether our queued channel [row] is on the ship already: its newest
     * posts (or the thread) read, and a copy of ours from that moment found.
     * Its local twin goes, as an echo would have taken it.
     */
    private suspend fun landedAlready(row: MessageEntity): Boolean {
        val parent = row.parentId
        if (parent == null) refreshConversation(row.whom, count = LANDED_CHECK_COUNT) else fetchThread(row.whom, parent)
        if (db.messages().shipCopyCount(row.whom, ourPatp, row.sentMs) == 0) return false
        db.messages().reapLocalTwin(row.whom, ourPatp, row.sentMs)
        db.messageMedia().reapLocalTwinMedia(row.whom, ourPatp, row.sentMs)
        return true
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
    /**
     * Add [ship] to our curated contact *book*.
     *
     * Two pokes per tlon-apps desk/lib/contacts/json-1.hoon ++action:
     *  1. `%meet [ship]` — track the peer so %contacts fetches their
     *     published profile into `peers` (a prerequisite: %page reads
     *     the peer's `con` from there).
     *  2. `%page {kip, contact}` — create the book entry, with the
     *     [nickname] as our local `mod` overlay (empty overlay when no
     *     nickname). This is what puts them in /v1/book.
     *
     * NOTE: %page asserts the peer is already tracked, so for a
     * never-before-seen ship the %page can race the async %meet fetch
     * and NACK on the first try. We still optimistically add to
     * [bookContacts] + upsert a local row so the UI is immediate; the
     * /v1/book re-scry on next login reconciles. Worth smoke-testing
     * the round-trip on a live ship.
     */
    /**
     * Ask our ship for [ship]'s profile when we hold nothing of it: no
     * name, picture or bio. A ship can hold someone in its book without
     * ever having met them, and then it has no profile to give: a comet
     * on ~ricsul showed bare while another ship had his name and picture
     * (2026-10-06). Tlon asks when a profile opens; so does Talon now. The
     * contacts agent ignores a %meet for a peer it already tracks. On the
     * repo's scope, so closing the profile does not cancel it.
     */
    fun meetIfUnknown(ship: String): kotlinx.coroutines.Job? {
        if (!ship.startsWith("~") || ship == ourPatp) return null
        return scope.launch {
            val c = db.contacts().get(ship)
            if (c != null && listOf(c.nickname, c.avatarUrl, c.bio).any { !it.isNullOrBlank() }) return@launch
            val ch = channel ?: return@launch
            runCatching {
                ch.poke(
                    app = "contacts",
                    mark = "contact-action-1",
                    payload = buildJsonObject { put("meet", buildJsonArray { add(JsonPrimitive(ship)) }) },
                )
            }.onFailure { Log.w(TAG, "asking for $ship's profile failed: ${it.message}") }
        }
    }

    suspend fun addContact(ship: String, nickname: String? = null) {
        val ch = channel ?: error("not connected")
        require(ship.startsWith("~")) { "addContact: $ship isn't a patp" }
        val name = nickname?.trim()?.takeIf { it.isNotBlank() }
        // 1. Track the peer.
        ch.poke(
            app = "contacts",
            mark = "contact-action-1",
            payload = buildJsonObject {
                put("meet", buildJsonArray { add(JsonPrimitive(ship)) })
            },
        )
        // 2. Create the book page (our overlay = optional nickname).
        ch.poke(
            app = "contacts",
            mark = "contact-action-1",
            payload = buildJsonObject {
                put("page", buildJsonObject {
                    put("kip", ship)
                    put("contact", buildJsonObject {
                        if (name != null) {
                            put("nickname", buildJsonObject {
                                put("type", "text")
                                put("value", name)
                            })
                        }
                    })
                })
            },
        )
        // Optimistic: mark in-book + ensure a local row so it shows now.
        _bookContacts.value = _bookContacts.value + ship
        val current = db.contacts().get(ship)
        db.contacts().upsert(
            ContactEntity(
                ship = ship,
                nickname = name ?: current?.nickname,
                bio = current?.bio,
                avatarUrl = current?.avatarUrl,
                status = current?.status,
                statusUpdatedMs = current?.statusUpdatedMs,
                color = current?.color,
            )
        )
    }

    /**
     * Remove [ship] from our contact book via `%wipe [kip]`
     * (json-1.hoon ++action `wipe+(ar kip)`). Optimistically drops it
     * from [bookContacts]; the contacts-table row stays since the peer
     * may still be in /v1/all.
     */
    suspend fun removeContact(ship: String) {
        val ch = channel ?: error("not connected")
        require(ship.startsWith("~")) { "removeContact: $ship isn't a patp" }
        ch.poke(
            app = "contacts",
            mark = "contact-action-1",
            payload = buildJsonObject {
                put("wipe", buildJsonArray { add(JsonPrimitive(ship)) })
            },
        )
        _bookContacts.value = _bookContacts.value - ship
    }

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
        /** "public" / "private" / "secret", when the preview said. */
        val privacy: String? = null,
        /** The ship's last try at joining it failed (its join progress is %error). */
        val failed: Boolean = false,
        /** Joining, and the host took the join (%watch): only the group is still to come. */
        val hostAnswered: Boolean = false,
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
        val fresh = nowMs() - adminGroupsFetchedMs < ADMIN_CACHE_TTL_MS
        if (!force && fresh && _adminGroups.value != null) return
        adminGroupsMutex.withLock {
            // Re-check after acquiring — another caller may have
            // refreshed while we were waiting on the mutex.
            val nowFresh = nowMs() - adminGroupsFetchedMs < ADMIN_CACHE_TTL_MS
            if (!force && nowFresh && _adminGroups.value != null) return
            runCatching { fetchAdminGroupsLive() }
                .onSuccess {
                    _adminGroups.value = it
                    adminGroupsFetchedMs = nowMs()
                }
                .onFailure {
                    // Leaving the Administration screen cancels this;
                    // that is not a failure worth a stack in the log.
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    Log.w(TAG, "refreshAdminGroups failed", it)
                    // Every caller catches; swallowing it here left the
                    // Administration screen spinning on a load that had
                    // already failed, its error line unreachable.
                    throw it
                }
        }
    }

    /**
     * [refreshAdminGroups] for a message menu: on the repo's scope, so
     * closing the menu does not throw the read away, and not again for a
     * minute after one failed, where every tap on a slow ship asked anew.
     */
    fun prefetchAdminGroups() {
        if (nowMs() - adminGroupsFailedMs < ADMIN_RETRY_MS) return
        scope.launch {
            io.nisfeb.talon.util.runSuspendCatching { refreshAdminGroups() }
                .onFailure { adminGroupsFailedMs = nowMs() }
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
        val body = scryNewest(ch, "groups", "/v3/groups", "/v2/groups") as? JsonObject
            ?: error("The ship's list of groups could not be read.")
        val me = ourPatp
        Log.i(TAG, "fetchAdminGroups: ${body.size} total groups, me=$me")
        // The list is every group whole: %groups encodes each entry of
        // /v3/groups (groups-3) with the same encoder as /v3/groups/<flag>
        // (group-3), seats and roles in it. Each was read again on its own
        // anyway, a scry per group of the ship's one thread, and the page
        // waited ten to twenty seconds for someone in a few dozen groups.
        // Only an entry without its members (a light listing, from an older
        // ship) is read on its own, eight at a time.
        val gate = Semaphore(permits = 8)
        val parsed = coroutineScope {
            body.entries.map { (flag, entry) ->
                async {
                    val whole = (entry as? JsonObject)?.takeIf { it["seats"] != null || it["fleet"] != null }
                    if (whole != null) return@async flag to whole
                    gate.acquire()
                    try {
                        val full = runCatching {
                            scryNewest(ch, "groups", "/v3/groups/$flag", "/v2/groups/$flag") as? JsonObject
                        }.getOrNull()
                        if (full == null) Log.w(TAG, "  $flag: full scry returned null")
                        flag to full
                    } finally {
                        gate.release()
                    }
                }
            }.awaitAll()
        }
        // A group that could not be read is not one we do not run: left
        // out, it vanished from Administration (and lost its Pin) for the
        // five minutes the list is kept. The list we had stays instead.
        parsed.firstOrNull { it.second == null }?.let { (flag, _) ->
            error("$flag could not be read from the ship. Try again.")
        }

        val out = ArrayList<AdminGroup>(parsed.size)
        for ((flag, full) in parsed) {
            if (full == null) continue
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
        val body = scryNewest(ch, "groups", "/v3/groups/$flag", "/v2/groups/$flag") as? JsonObject ?: return null
        return parseAdminGroup(flag, body)
    }

    /**
     * The group's role vocabulary: role id → display title. Backs the
     * wire-5 party-line role gates, which store ids but should read as
     * titles. Same scry [fetchGroupAdmin] uses; `cabals` is the legacy
     * name for the same map. Empty when the group can't be read.
     */
    suspend fun fetchGroupRoles(flag: String): Map<String, String> {
        val ch = channel ?: error("not connected")
        val body = scryNewest(ch, "groups", "/v3/groups/$flag", "/v2/groups/$flag") as? JsonObject
            ?: error("the group's record was not readable")
        val roles = (body["roles"] ?: body["cabals"]) as? JsonObject ?: return emptyMap()
        return roles.mapValues { (id, v) ->
            ((v as? JsonObject)?.get("meta") as? JsonObject)?.get("title").asStr()
                ?.takeIf { it.isNotBlank() } ?: id
        }
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
        require(kind in setOf("chat", "heap", "notes")) { "unknown channel kind: $kind" }
        // Notebooks live on %notes, which registers the channel with
        // %groups itself — there's no %channels create to send, and the
        // host derives the slug from the title rather than taking ours.
        // ("diary"/Bulletin is deliberately absent: deprecated upstream,
        // so we no longer mint new ones.)
        if (kind == "notes") {
            return notes.createGroupNotebook(groupFlag, title)
                ?: error("couldn't create notebook")
        }
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

    /** What this ship's %channels says of who may post in a channel. */
    sealed interface ChannelWriters {
        /** Role ids; none means every member. */
        data class Roles(val ids: Set<String>) : ChannelWriters
        /** %channels has no such channel here: this ship has not joined it. */
        data object NotJoined : ChannelWriters
        /** The ship did not say, or said something unreadable. Not the same as everyone. */
        data class NoAnswer(val why: String) : ChannelWriters
    }

    /**
     * Who may post in [nest]. A notebook (notes/) is not a %channels
     * channel at all, so ask only for the others: every one read as
     * "not joined", though the owner had written in it that day.
     */
    suspend fun fetchChannelWriters(nest: String): ChannelWriters {
        val ch = channel ?: return ChannelWriters.NoAnswer("not connected")
        val perm = runCatching { ch.scry("channels", "/v5/$nest/perm") }.getOrElse { e ->
            // ponytail: the status read off our own scry error; a typed error if another caller needs it.
            return if ("HTTP 404" in e.message.orEmpty()) ChannelWriters.NotJoined
            else ChannelWriters.NoAnswer(e.message ?: e::class.simpleName.orEmpty())
        }
        val writers = ((perm as? JsonObject)?.get("writers") as? JsonArray)
            ?: return ChannelWriters.NoAnswer("its answer had no writers")
        return ChannelWriters.Roles(writers.mapNotNull { it.asStr() }.toSet())
    }

    /**
     * Who may post in [nest], from [was] to [now]. Roles are added before
     * any are taken away: the other way round, a channel going from one
     * role to another was open to every member in between.
     */
    suspend fun setChannelWriters(nest: String, was: Set<String>, now: Set<String>) = carry {
        val ch = channel ?: error("not connected")
        (now - was).takeIf { it.isNotEmpty() }?.let {
            ch.poke(app = "channels", mark = "channel-action-2", payload = channelWriters(nest, true, it), confirm = true)
        }
        (was - now).takeIf { it.isNotEmpty() }?.let {
            ch.poke(app = "channels", mark = "channel-action-2", payload = channelWriters(nest, false, it), confirm = true)
        }
    }

    /** Who may read [nest], from [was] to [now]; added first, as [setChannelWriters]. */
    suspend fun setChannelReaders(flag: String, nest: String, was: Set<String>, now: Set<String>) = carry {
        (now - was).takeIf { it.isNotEmpty() }?.let { pokeAGroup(flag, aGroupChannel(nest, aChannelReaders(true, it))) }
        (was - now).takeIf { it.isNotEmpty() }?.let { pokeAGroup(flag, aGroupChannel(nest, aChannelReaders(false, it))) }
    }

    /** A channel's title and description; the rest of it as the group's record has it. */
    suspend fun editChannel(flag: String, c: AdminChannel, title: String, description: String) = carry {
        pokeAGroup(flag, aGroupChannel(c.nest, aChannelEdit(c, title, description)))
    }

    /** Take [nest] out of the group. */
    suspend fun deleteChannel(flag: String, nest: String) = carry {
        pokeAGroup(flag, aGroupChannel(nest, aChannelDelete()))
    }

    /**
     * [block] on this repo's scope, awaited: an admin who leaves the screen
     * mid-change does not stop it halfway, a role added and none taken away;
     * a photo sent as the chat is left still goes.
     */
    suspend fun <T> carry(block: suspend () -> T): T = scope.async { block() }.await()

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
    /**
     * Invite a ship to a group.
     *
     * Confirmed, because the answer goes straight to a person as
     * "invited". A nack already threw; silence used to read as yes,
     * and silence is what one wedged ames flow to one peer looks
     * like, so the person was told it had gone and went looking at
     * the other ship.
     */
    suspend fun inviteToGroup(flag: String, ship: String): Boolean {
        val ch = channel ?: error("not connected")
        pokeGroupAction(ch, groupAction4InviteAdd(flag, ship))
        return true
    }

    /**
     * A group action under group-action-5 (12.2.0), the mark Tlon's client
     * sends, and again under group-action-4 where an older ship refuses
     * it. The two bodies are the same; -5 only adds a `blob` variant.
     */
    private suspend fun pokeGroupAction(ch: UrbitChannel, payload: JsonObject) =
        pokeNewest(ch, "groups", GROUP_ACTION_MARKS, payload, confirm = true)

    /**
     * Poke under the first of [marks] this ship takes, newest first, as
     * [scryNewest] does with paths: a refusal falls back to the next, and
     * the one taken is remembered for the login, so an older ship pays one
     * refused poke, not one with every action.
     */
    private suspend fun pokeNewest(ch: UrbitChannel, app: String, marks: List<String>, payload: JsonObject, confirm: Boolean = false) {
        val key = app + ":" + marks.first()
        val start = servedPath[key] ?: 0
        for (i in start until marks.size) {
            try {
                ch.poke(app = app, mark = marks[i], payload = payload, confirm = confirm)
                if (i > start) servedPath[key] = i
                return
            } catch (n: PokeNacked) {
                if (i == marks.lastIndex) throw n
            }
        }
    }

    /** Which of a scryNewest family's paths, or a pokeNewest family's marks, this ship served: its index. */
    private val servedPath = ConcurrentMap<String, Int>()

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
     * Report a message to the group's admins — Tlon's flag-content.
     * The poke goes to our own %groups, which relays it to the group
     * host; it lands in the group's flagged-content for admin review
     * and fires the admins' activity notification. For a thread reply
     * pass the reply's own id as [postId] and its [parentId] too.
     */
    suspend fun reportMessage(
        groupFlag: String,
        nest: String,
        postId: String,
        parentId: String?,
    ) {
        pokeAGroup(groupFlag, aGroupFlagContent(nest, postId, parentId, ourPatp))
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

    private val _joining = MutableStateFlow<List<InviteSummary>>(emptyList())

    /**
     * Groups the ship is joining and has not got into: an accepted
     * invite waiting on its host. Not invites to answer. %groups does
     * nothing with another join while one is under way, and listed as an
     * invite one came back after every refresh, to be accepted again.
     */
    val joiningFlow: StateFlow<List<InviteSummary>> = _joining.asStateFlow()

    /**
     * Try a series of candidate scry paths to find inbound invites.
     * The renamed agent dropped `/gangs` (404); the new path is
     * unknown until we probe. First hit wins; all attempts are logged
     * so we can pin down the working path from a single round-trip.
     */
    /**
     * Fetch inbound invites from the `groups-ui /v10/init` scry (or /v7) and
     * filter to foreigns that have at least one valid invite. The
     * init response also contains everything else the client needs,
     * but we only read `foreigns` here.
     */
    suspend fun refreshInvites(notify: Boolean = false) {
        val ch = channel ?: error("not connected")
        // Flags we already knew about, so a live refresh only toasts
        // genuinely new invites — not the whole pending set every time
        // %groups republishes it. Null (never loaded) counts as "knew
        // nothing", but bootstrap passes notify=false so that first load
        // stays quiet regardless.
        val known = _invites.value?.mapTo(mutableSetOf()) { it.flag } ?: mutableSetOf()
        // No answer is not "no invites": stored as one, a failed refresh
        // wiped the invites being shown. Callers catch and say so.
        // The foreigns alone (groups /v1/foreigns): the groups-ui init this
        // read before is the ship's largest scry, every group, channel and
        // unread in it, read whole for this one map at each start and on
        // every invite heard. The init only on a ship without the scry.
        val direct = io.nisfeb.talon.util.runSuspendCatching { ch.scry("groups", "/v1/foreigns") as? JsonObject }
            .getOrElse { if (!notServed(it)) throw it; null }
        val foreigns: JsonObject
        val joined: Set<String>
        if (direct != null) {
            foreigns = direct
            joined = db.groups().allGroups().mapTo(mutableSetOf()) { it.flag }
        } else {
            val body = runCatching { scryNewest(ch, "groups-ui", "/v10/init", "/v7/init") }
                .onFailure { Log.w(TAG, "scry groups-ui init failed", it) }
                .getOrThrow() as? JsonObject
                ?: error("the ship's answer about invites was not readable")
            foreigns = body["foreigns"] as? JsonObject ?: run {
                Log.w(TAG, "refreshInvites: no foreigns in init response, keys=${body.keys}")
                _invites.value = emptyList()
                _joining.value = emptyList()
                return
            }
            joined = (body["groups"] as? JsonObject)?.keys.orEmpty()
        }
        Log.i(TAG, "refreshInvites: ${foreigns.size} foreigns")
        val out = mutableListOf<InviteSummary>()
        val joining = mutableListOf<InviteSummary>()
        for ((flag, foreign) in foreigns) {
            if (flag in joined) continue
            val (summary, under) = inviteOf(flag, foreign as? JsonObject ?: continue) ?: continue
            if (under) joining += summary else out += summary
        }
        _joining.value = joining.sortedBy { (it.title ?: it.flag).lowercase() }
        _invites.value = out.sortedBy { (it.title ?: it.flag).lowercase() }
        if (notify) {
            out.filter { it.flag !in known }
                .forEach { runCatching { groupInviteListener?.invoke(it) } }
        }
    }

    /**
     * A /v1/foreigns fact: the one group that moved (groups.hoon
     * +fi-give-update gives `(my flag^foreign ~)`), in the shape the scry
     * gives. Applied as it is; the whole list was read again for each.
     * A list never read, or an older ship's /gangs/updates shape, is read.
     */
    private suspend fun applyForeigns(payload: JsonObject) {
        val shown = _invites.value
        if (shown == null || (subServed["groups/v1/foreigns"] ?: 0) != 0) {
            refreshInvites(notify = true)
            return
        }
        val joined = db.groups().joinedOf(payload.keys).toSet()
        val out = shown.filter { it.flag !in payload }.toMutableList()
        val joining = _joining.value.filter { it.flag !in payload }.toMutableList()
        val fresh = mutableListOf<InviteSummary>()
        for ((flag, foreign) in payload) {
            if (flag in joined) continue
            val (summary, under) = inviteOf(flag, foreign as? JsonObject ?: continue) ?: continue
            if (under) joining += summary
            else {
                out += summary
                if (shown.none { it.flag == flag }) fresh += summary
            }
        }
        _joining.value = joining.sortedBy { (it.title ?: it.flag).lowercase() }
        _invites.value = out.sortedBy { (it.title ?: it.flag).lowercase() }
        fresh.forEach { runCatching { groupInviteListener?.invoke(it) } }
    }

    /** One group's invite or join under way, and whether it is the join; null when neither. */
    private fun inviteOf(flag: String, f: JsonObject): Pair<InviteSummary, Boolean>? {
        // Where the ship is with joining it: a join under way was
        // answered already, and one that is done is a group.
        val progress = f["progress"].asStr()
        if (progress == "done") return null
        val under = progress == "join" || progress == "watch"
        // invites is an array of {from, token, valid, ...}
        // (lib/groups-json +invite); an invite to answer needs at
        // least one still valid.
        val firstValid = (f["invites"] as? JsonArray).orEmpty().asSequence()
            .mapNotNull { it as? JsonObject }
            .firstOrNull { (it["valid"] as? JsonPrimitive)?.content == "true" }
        if (firstValid == null && !under) return null
        val inviter = firstValid?.get("from").asStr()
        val preview = f["preview"] as? JsonObject
        val meta = preview?.get("meta") as? JsonObject
        fun metaStr(k: String) = meta?.get(k).asStr()
            ?.takeIf { it.isNotBlank() }
        // member-count + privacy are siblings of `meta` in the group
        // preview (sur/groups.hoon +$preview). Older ships spell the
        // count `count`; either way it's a plain JSON number.
        val memberCount = (preview?.get("member-count") ?: preview?.get("count"))
            ?.let { (it as? JsonPrimitive)?.content?.toIntOrNull() }
        return InviteSummary(
            flag = flag,
            inviter = inviter,
            title = metaStr("title"),
            description = metaStr("description"),
            image = metaStr("image"),
            cover = metaStr("cover"),
            memberCount = memberCount,
            privacy = preview?.get("privacy").asStr()?.takeIf { it.isNotBlank() },
            failed = progress == "error",
            hostAnswered = progress == "watch",
        ) to under
    }

    /** Accept an inbound group invite: join it, and move it to the groups being joined. */
    suspend fun acceptInvite(flag: String) {
        joinGroup(flag)
        val accepted = _invites.value?.firstOrNull { it.flag == flag }
        _invites.value = _invites.value?.filterNot { it.flag == flag }
        accepted?.let { a -> _joining.update { list -> list.filterNot { it.flag == flag } + a.copy(failed = false) } }
    }

    /**
     * Stop a join its host has not answered (%groups `group-cancel`). An
     * invite it came from is back to be answered, or declined.
     */
    suspend fun cancelJoin(flag: String) {
        val ch = channel ?: error("not connected")
        ch.poke(app = "groups", mark = "group-cancel", payload = JsonPrimitive(flag))
        _joining.update { list -> list.filterNot { it.flag == flag } }
        runCatching { refreshInvites() }.onFailure { Log.w(TAG, "invites not read after a cancelled join", it) }
    }

    /**
     * Join a group via `group-join`: one we hold an invite to, or a public
     * group anyone may join, such as one whose code was scanned.
     */
    suspend fun joinGroup(flag: String) {
        val ch = channel ?: error("not connected")
        ch.poke(
            app = "groups",
            mark = "group-join",
            payload = buildJsonObject {
                put("flag", flag)
                put("join-all", true)
            },
        )
        // The ship accepts the poke and does the join afterwards, and the
        // groups subscription does not replay it. Reconcile until it
        // lands instead of depending on a reconnect that may never come.
        scope.launch { awaitJoinedGroup(flag) }
    }

    /** Poll the group list until [flag] appears, briefly. The join poke
     *  is accepted long before the group exists, especially on a busy
     *  ship; six tries over about a minute covers it without becoming
     *  another timer that hammers the ship. */
    private suspend fun awaitJoinedGroup(flag: String) {
        var wait = 2_000L
        repeat(6) {
            delay(wait)
            if (runCatching { db.groups().getGroup(flag) }.getOrNull() != null) {
                _joining.update { list -> list.filterNot { it.flag == flag } }
                return
            }
            runCatching { refreshGroups() }
                .onFailure { Log.w(TAG, "post-join group refresh failed", it) }
            wait = (wait * 2).coerceAtMost(20_000L)
        }
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
     *
     * %groups sends the *leaving* member no `r-group: {delete}` fact
     * (unlike a host deleting the group), so the home list would keep
     * showing a left group until the next full /v2/groups reconcile —
     * i.e. an app restart. So drop the group locally once the ship
     * acks the leave. A refusal throws and keeps it, since it is still
     * ours; silence keeps it too, and the next reconcile decides.
     */
    suspend fun leaveGroup(flag: String) {
        val ch = channel ?: error("not connected")
        val acked = try {
            ch.poke(app = "groups", mark = "group-leave", payload = JsonPrimitive(flag), confirm = true)
            true
        } catch (_: PokeUnacked) {
            false
        }
        if (acked) {
            db.groups().deleteChannelsForGroup(flag)
            db.groups().deleteGroup(flag)
        }
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
                // Asked for: told whatever the local count says.
                launch { runCatching { markRead(whom, force = true) } }
            }
        }
    }

    /** Reads waiting for the chat or thread in view, by whom or whom#parent. */
    private val readSoon = ConcurrentMap<String, Job>()

    /**
     * Read [key] soon, once for whatever arrives meanwhile: a busy channel
     * open on screen poked the ship once per message, three events each.
     */
    private fun markReadSoon(key: String, read: suspend () -> Unit) {
        if (readSoon[key]?.isActive == true) return
        readSoon[key] = scope.launch {
            delay(FOCUSED_READ_EVERY_MS)
            readSoon.remove(key)
            runCatching { read() }
        }
    }

    /** The read waiting for [key], sent now, if one is. */
    private fun flushReadSoon(key: String, read: suspend () -> Unit) {
        val waiting = readSoon.remove(key) ?: return
        waiting.cancel()
        scope.launch { runCatching { read() } }
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
     * Send a group action (see [pokeGroupAction]) wrapping the given `a-group` diff
     * for the target group. All admin actions (meta, seat, entry,
     * role) go through this helper.
     */
    private suspend fun pokeAGroup(flag: String, aGroup: JsonObject) {
        val ch = channel ?: error("not connected")
        // Every one of these is an admin acting on somebody else's
        // membership and being shown the result: a kick, a ban, an ask
        // resolved. None of them may report a silence as done (confirm).
        pokeGroupAction(ch, groupAction4(flag, aGroup))
    }

    /**
     * The first of [paths] the ship answers, newest first. Tlon's N-1
     * policy (12.3.0) lets a desk drop a path its own client no longer
     * calls, and a ship older than the newest has only the ones before it.
     */
    private suspend fun scryNewest(ch: UrbitChannel, app: String, vararg paths: String, timeoutSecs: Long? = null): JsonElement {
        // A path this ship has shown it serves is where to start; the newer
        // ones before it are not asked again this session. Asked every time,
        // an older ship paid a failed request on every call.
        // Keyed by the family (app, version, first name), so one group's
        // answer serves every group's: `/v3/groups` and `/v3/groups/<flag>`.
        val key = app + "/" + paths.first().split('/').filter { it.isNotEmpty() }.take(2).joinToString("/")
        val start = servedPath[key] ?: 0
        var last: Throwable? = null
        for ((i, p) in paths.withIndex()) {
            if (i < start) continue
            try {
                val body = if (timeoutSecs == null) ch.scry(app, p) else ch.scry(app, p, timeoutSecs)
                if (i > 0) servedPath[key] = i
                return body
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                // Only a path the ship says it does not serve goes on to the
                // next. A timeout or a dropped connection is a busy or absent
                // ship: asking it again, older, doubled the wait and the load
                // on it, just when it could least take either.
                if (!notServed(t)) throw t
                last = t
            }
        }
        throw last ?: IllegalStateException("no path to scry")
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
            // Empty clears, and a null value is %contacts' delete: an empty
            // colour went up as 0 and turned the profile black, and an
            // emptied text field was kept as "".
            fun field(name: String, type: String, value: String?, wire: (String) -> String = { it }) {
                if (value != null) {
                    put(name, if (value.isBlank()) JsonNull else buildJsonObject { put("type", type); put("value", wire(value)) })
                }
            }
            field("nickname", "text", nickname)
            field("bio", "text", bio)
            field("status", "text", status)
            field("avatar", "look", avatarUrl)
            field("color", "tint", color) { urbitHexColor(it) }
        }
        if (contactFields.isEmpty()) return
        // Optimistic local update FIRST so the UI reflects the edit
        // before the poke round-trips. Merge with any existing row so
        // fields the caller didn't supply stay intact. Stamp
        // statusUpdatedMs when the status actually changes so the feed
        // reorders. (Sequencing the poke after the upsert was making
        // the status feed feel laggy on slow links.)
        val current = db.contacts().get(ourPatp)
        // Null leaves a field as it was; empty clears it, here as on the
        // ship. An emptied field used to keep its old value here.
        fun kept(new: String?, old: String?) = if (new == null) old else new.ifBlank { null }
        val statusChanged = status != null && status != current?.status.orEmpty()
        db.contacts().upsert(
            ContactEntity(
                ship = ourPatp,
                nickname = kept(nickname, current?.nickname),
                bio = kept(bio, current?.bio),
                avatarUrl = kept(avatarUrl, current?.avatarUrl),
                status = kept(status, current?.status),
                statusUpdatedMs = if (statusChanged) nowMs()
                    else current?.statusUpdatedMs,
                color = kept(color, current?.color),
            )
        )
        // Per tlon-apps desk/lib/contacts/json-1.hoon's `++action`:
        //   self+contact            <- own profile edit (this code path)
        //   edit+(ot kip+kip contact+contact ~)  <- pet-name overlay for ANOTHER ship
        // setPetName above uses `edit` because that's a kip-scoped overlay.
        // For the user's own profile, the discriminator is `self`, with the
        // contact object directly as the value (no kip / contact wrapper).
        try {
            ch.poke(
                app = "contacts",
                mark = "contact-action-1",
                payload = buildJsonObject {
                    put("self", contactFields)
                },
            )
        } catch (t: Throwable) {
            // Refused, or never left: put back what the ship still has. An
            // unanswered poke may have landed, and its echo will say.
            if (t !is kotlinx.coroutines.CancellationException && t !is PokeUnacked && current != null) {
                db.contacts().upsert(current)
            }
            throw t
        }
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

    /**
     * The three views `%activity`'s `feed/init` returns together. They
     * aren't subsets of one another: `all` is the firehose, while
     * `mentions` and `replies` are the ship's own filtered views, so
     * we render what it gives us rather than re-deriving them.
     */
    data class ActivityFeed(
        val all: List<ActivityFeedItem> = emptyList(),
        val mentions: List<ActivityFeedItem> = emptyList(),
        val replies: List<ActivityFeedItem> = emptyList(),
    ) {
        fun forTab(tab: ActivityTab): List<ActivityFeedItem> = when (tab) {
            ActivityTab.ALL -> all
            ActivityTab.MENTIONS -> mentions
            ActivityTab.REPLIES -> replies
            // Read from the database, not the feed: see ActivityList.
            ActivityTab.THREADS -> emptyList()
        }
    }

    enum class ActivityTab(val label: String) {
        ALL("All"),
        MENTIONS("Mentions"),
        REPLIES("Replies"),
        THREADS("Threads"),
    }

    /**
     * Process-singleton cache of the activity feed. Null = never
     * loaded; non-null = last successful fetch. UI binds to this
     * StateFlow so reopening the Activity view shows instantly,
     * and a background fetchActivityFeed() refresh updates the
     * cache without flashing a spinner. Cleared automatically on
     * ship switch (TlonChatRepo is per-ship — a new repo means a
     * fresh empty StateFlow).
     */
    private val _activityFeed = MutableStateFlow<ActivityFeed?>(null)
    val activityFeedFlow: StateFlow<ActivityFeed?> = _activityFeed.asStateFlow()

    suspend fun fetchActivityFeed(): ActivityFeed {
        val ch = channel ?: error("not connected")
        // The feed's version namespace shifted under us in Tlon v11.4.0:
        // what /v6 now serves is what /v5 used to, and /v5 became the
        // down-converted view that silently drops every %react and
        // %dm-react event. Ask for the richest the ship will answer.
        val body = scryFirstMatching(
            ch,
            "activity",
            listOf("/v6/feed/init/30", "/v5/feed/init/30"),
            "activity feed",
        ) as? JsonObject
            // No answer is not an empty feed: stored as one, a timed-out
            // refresh replaced what was showing with "No activity yet".
            ?: error("the ship did not answer for its activity")
        val items = parseActivityFeed(body)
        _activityFeed.value = items
        return items
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
        db.messages().upsertWithMedia(db.messageMedia(), entity)
        (seal["reacts"] as? JsonObject)?.let { reacts ->
            db.reactions().clearForPost(nest, id)
            val rx = reacts.entries.mapNotNull { (author, emoji) ->
                val e = emoji.asStr() ?: return@mapNotNull null
                ReactionEntity(nest, id, author, ReactionPalette.normalize(e))
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
            // A DM writ id is `~author/<da>`, stored undotted; the ship
            // parses the da as an @ud, which wants its dots back.
            whom.startsWith("~") -> {
                val id = redotWritId(parentId)
                "chat" to listOf("/v4/dm/$whom/writs/writ/id/$id", "/v3/dm/$whom/writs/writ/id/$id")
            }
            whom.startsWith("0v") -> {
                val id = redotWritId(parentId)
                "chat" to listOf("/v4/club/$whom/writs/writ/id/$id", "/v3/club/$whom/writs/writ/id/$id")
            }
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
        if (messages.isNotEmpty()) db.messages().upsertAllWithMedia(db.messageMedia(), messages)
        if (reactions.isNotEmpty()) db.reactions().upsertAll(reactions)
    }

    /**
     * A quoted reply, read off its parent post, as Tlon's client reads one
     * (/v5/<nest>/posts/post/<id>, its replies in the seal). The reply
     * scry this used answers `{seal, revision, memo}` (channel-reply-2),
     * not the `reply-essay` it looked for, so a quoted reply never loaded;
     * and Tlon's client does not call it, which makes it removable.
     */
    suspend fun fetchCiteReply(nest: String, parentDa: String, replyDa: String): MessageEntity? {
        if (channel == null) return null
        fetchThread(nest, parentDa.replace(".", ""))
        return db.messages().getOne(nest, replyDa.replace(".", ""))
    }

    /** Conversations read on opening since this connect; the stream keeps them current after. */
    private val readOnOpen = ConcurrentSet<String>()

    /**
     * A conversation read as it opens: its newest [OPEN_READ_COUNT], once a
     * connect. It read 500 on every open, a reopen seconds later with the
     * stream live included, and rewrote them all; scrolling back loads the
     * rest page by page, and a reconnect's catch-up covers a gap.
     */
    suspend fun refreshOnOpen(whom: String) {
        if (readOnOpen.contains(whom)) return
        refreshConversation(whom, count = OPEN_READ_COUNT)
        readOnOpen.add(whom)
    }

    /**
     * Fill holes in a conversation by re-fetching the last `count` posts
     * ending at the current newest known post. Uses the same older-than-
     * cursor scries as pagination, so anything that should have arrived
     * via SSE but got dropped (e.g. pre-buffer-fix events that were ACK'd
     * but never applied) gets backfilled. Idempotent upsert — no dupes.
     * Throws when the ship did not answer, so a screen can tell a
     * conversation it could not load from an empty one.
     */
    suspend fun refreshConversation(whom: String, count: Int = 100) {
        val ch = channel ?: error("not connected")
        val newest = db.messages().newestIdFor(whom)
        // In path form Urbit @ud atoms need dotted-decimal (3-digit groups).
        val dottedCursor = newest?.let {
            runCatching { UrbitTime.daToUd(BigInteger.parseString(it)) }.getOrNull()
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

        val probe = scryFirstMatching(ch, app, paths, label = "refreshConversation($whom)", memo = "$app/$postsKey")
        val obj = probe as? JsonObject ?: error("the ship did not send $whom")
        val posts = obj[postsKey] as? JsonObject
        if (posts == null) {
            Log.w(TAG, "refreshConversation($whom): no '$postsKey' key; keys=${obj.keys}")
            return
        }
        val messages = mutableListOf<MessageEntity>()
        val reactions = mutableListOf<ReactionEntity>()
        posts.forEach { (_, post) -> ingestPost(whom, post, messages, reactions) }
        if (messages.isNotEmpty()) db.messages().upsertAllWithMedia(db.messageMedia(), messages)
        if (reactions.isNotEmpty()) db.reactions().upsertAll(reactions)
        // Same grey-twin reap as the init-posts bootstrap: this refresh
        // re-adds our own posts under their real id, so clear any stranded
        // optimistic twin (exact whom/author/sentMs) it would otherwise
        // duplicate.
        messages.filter { it.author == ourPatp && it.parentId == null && !it.id.startsWith("local_") }
            .forEach {
                db.messages().reapLocalTwin(it.whom, ourPatp, it.sentMs)
                db.messageMedia().reapLocalTwinMedia(it.whom, ourPatp, it.sentMs)
            }
        // Clean up stale optimistic-insert rows for channels whose id
        // format we'd gotten wrong in earlier builds. One-shot; no-op
        // once all such ghosts are gone.
        if (whom.startsWith("chat/") || whom.startsWith("heap/") || whom.startsWith("diary/")) {
            db.messages().purgeStaleLocalIds(whom)
        }
    }

    /**
     * Fetch older posts for a conversation and upsert them. Returns true
     * if the server claims more history is available below what we just
     * loaded; false if we've hit the bottom. Throws when the ship did not
     * answer: that says nothing about the bottom, so callers keep asking.
     *
     *   DM:      %chat  /v4/dm/~peer/writs/older/{cursor}/{count}/light
     *   Club:    %chat  /v4/club/0v.../writs/older/{cursor}/{count}/light
     *   Channel: %channels /v5/chat/~host/name/posts/older/{cursor}/{count}/outline
     */
    suspend fun loadOlder(whom: String, count: Int = 30): Boolean {
        val ch = channel ?: error("not connected")
        if (paginationExhausted.contains(whom)) return false
        val cursor = db.messages().oldestIdFor(whom) ?: return false

        val dotted = runCatching { UrbitTime.daToUd(BigInteger.parseString(cursor)) }
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

        val body = scryFirstMatching(ch, app, paths, label = "loadOlder $whom", memo = "$app/$postsKey") as? JsonObject
            ?: error("the ship did not send older messages of $whom")

        val posts = body[postsKey] as? JsonObject
        if (posts != null) {
            val messages = mutableListOf<MessageEntity>()
            val reactions = mutableListOf<ReactionEntity>()
            posts.forEach { (_, post) -> ingestPost(whom, post, messages, reactions) }
            if (messages.isNotEmpty()) db.messages().upsertAllWithMedia(db.messageMedia(), messages)
            if (reactions.isNotEmpty()) db.reactions().upsertAll(reactions)
        }

        val hasMore = body["older"].let { it != null && it !is JsonNull }
        if (!hasMore) paginationExhausted.add(whom)
        return hasMore
    }

    private val paginationExhausted = ConcurrentSet<String>()



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
    ): String = withContext(ioDispatcher) {
        // Root guard for every upload path. ContentResolver reads of a
        // not-yet-downloaded cloud item return 0 bytes WITHOUT throwing,
        // and an uploaded blank object posts as an unrenderable message —
        // the Samsung report. Callers with a nicer UX (staging, pickers)
        // reject earlier; this catches the paths that don't (share sheet,
        // which funnels FILES through here too — keep the wording generic).
        require(bytes.isNotEmpty()) {
            "the file came back empty — if it lives in cloud storage, download it to the device first"
        }
        val ch = channel ?: error("not connected")
        val client = http ?: error("not connected")

        // Shortened here rather than in either backend, because both
        // fail on an over-long name and neither is the safe fallback:
        // the report that prompted this had memex 500 and then S3 500
        // with `Filename too long (os error 36)` on the same upload.
        val safeLengthName = truncateUploadName(fileName)

        // A cancelled upload goes on up as one: wrapped in the failure
        // below, it defeated every caller's cancellation check.
        val memexErr = io.nisfeb.talon.util.runSuspendCatching {
            uploadViaMemex(ch, client, bytes, contentType, safeLengthName)
        }.onSuccess { return@withContext it }.exceptionOrNull()

        val storageErr = io.nisfeb.talon.util.runSuspendCatching {
            uploadViaStorage(ch, client, bytes, contentType, safeLengthName)
        }.onSuccess { return@withContext it }.exceptionOrNull()

        throw UploadFailed(
            uploadFailureLine(memexErr, storageErr),
            IllegalStateException("image upload failed: memex=${memexErr?.message}; storage=${storageErr?.message}"),
        )
    }

    private suspend fun uploadViaMemex(
        ch: UrbitChannel,
        client: HttpClient,
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

        // Cap each upload call at 60s. The shared client uses no read
        // timeout to keep the SSE channel alive forever, so without an
        // explicit per-call cap an unresponsive memex / S3 endpoint would
        // freeze the whole save flow indefinitely.
        val memexResp = client.put("https://memex.tlon.network/v1/$bareShip/upload") {
            contentType(ContentType.Application.Json)
            setBody(memexBody)
            timeout { requestTimeoutMillis = 60_000 }
        }
        if (!memexResp.status.isSuccess()) error("memex upload-url failed: HTTP ${memexResp.status.value}")
        val body = memexResp.bodyAsText().ifBlank { error("empty memex response") }
        val obj = Json.parseToJsonElement(body).jsonObject
        // The memex wire response is { url, filePath }: `url` is the
        // presigned PUT target (where the bytes go), `filePath` is the
        // public URL to embed in the message. tlon-apps maps these as
        // uploadUrl=data.url, hostedUrl=data.filePath (see
        // packages/api/src/client/storageApi.ts). We used to fall the
        // hosted URL back to `url` when there was no `hostedUrl` key —
        // but `url` is the PUT-scoped presigned URL, so the posted
        // image 404'd on GET ("unable to load image"). Read `filePath`
        // for the src; the hostedUrl/uploadUrl aliases stay as
        // defensive fallbacks for any other memex version. If the
        // shape is still unrecognized, report the actual KEYS (not
        // values — a value can be a short-lived presigned URL).
        val uploadUrl = obj["uploadUrl"].asStr() ?: obj["url"].asStr()
            ?: error("no upload url in memex response; keys=${obj.keys}")
        val hostedUrl = obj["hostedUrl"].asStr() ?: obj["filePath"].asStr()
            ?: error("no hosted url in memex response; keys=${obj.keys}")

        val putResp = client.put(uploadUrl) {
            contentType(ContentType.parse(contentType))
            header("Cache-Control", "public, max-age=3600")
            setBody(bytes)
            timeout { requestTimeoutMillis = 60_000 }
        }
        if (!putResp.status.isSuccess()) error("memex PUT failed: HTTP ${putResp.status.value}")
        return hostedUrl
    }

    private suspend fun uploadViaStorage(
        ch: UrbitChannel,
        client: HttpClient,
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
        // Upload via direct S3 whenever the agent has full credentials,
        // regardless of the `service` mode — matching tlon-apps. We do NOT
        // reject `presigned-url` mode: that field only routes Tlon-hosted
        // ships to memex (already tried above), and gating on it here was
        // what blocked ships left in presigned-url mode but holding valid
        // creds (the ~dinnyt-divsud report). See StorageUpload.kt.
        if (!storageS3Ready(creds, config)) {
            error(
                if (config.service == "presigned-url") {
                    "%storage is in presigned-url mode with no S3 credentials. " +
                        "Talon uploads need direct credentials — add them in " +
                        "Landscape's storage settings, or switch the agent with " +
                        "`:storage &storage-action [%toggle-service %credentials]`."
                } else {
                    "%storage credentials not fully configured"
                },
            )
        }

        val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val key = "talon/${UrbitTime.unixMsToDa(nowMs())}-$safeName"

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

    /**
     * Add or replace our reaction on a post. The caller may pass either
     * a shortcode (`:thumbsup:`) or a unicode glyph (`👍`); we normalize
     * to a glyph here because Tlon migrated reactions away from
     * shortcodes to unicode in 2025 — every other Tlon client now sends
     * glyphs on the wire, so emitting a shortcode would fragment reaction
     * grouping (a `:thumbsup:` from us and a `👍` from web Tlon become two
     * separate chips). `ReactionPalette.display` is shortcode→glyph
     * with a passthrough fallback, so glyphs in stay glyphs out.
     */
    suspend fun react(whom: String, postId: String, emoji: String, parentId: String? = null) {
        // Wire: the emoji-presentation glyph (FE0F-bearing), to match
        // what every other Tlon client sends. Local DB + usage: the
        // variation-selector-stripped canonical form so our optimistic
        // row groups with the same reaction arriving from any client
        // (and with the ship's echo of this very poke).
        val glyph = ReactionPalette.display(emoji)
        val canonical = ReactionPalette.normalize(glyph)
        // Shown now, and the ship told after: a poke waits for the ship's
        // ack, up to fifteen seconds, and since pokes began waiting the
        // reaction waited with it. A refusal puts back what was there; a
        // ship out of reach leaves it shown, queued.
        // Rows are keyed on the undotted id (ReactionDao.upsert normalizes); look them up the same way.
        val rowId = postId.replace(".", "")
        val before = db.reactions().get(whom, rowId, ourPatp)
        db.reactions().upsert(ReactionEntity(whom, postId, ourPatp, canonical))
        try {
            sendReactOrQueue(QueuedReact(whom, postId, parentId, glyph))
        } catch (t: Throwable) {
            if (before != null) db.reactions().upsert(before) else db.reactions().delete(whom, rowId, ourPatp)
            throw t
        }
        runCatching { db.reactionUsage().bump(canonical) }
        joinedThread(whom, parentId ?: postId)
    }

    /** A reaction's poke, or its taking off's ([QueuedReact.glyph] null). */
    private suspend fun sendReact(ch: UrbitChannel, r: QueuedReact) {
        val glyph = r.glyph
        if (glyph == null) {
            pokeAt(ch, r.whom, r.postId, r.parentId, buildJsonObject { put("del-react", ourPatp) }, buildJsonObject {
                put("del-react", buildJsonObject {
                    put("id", dotAtom(r.postId))
                    // See note on add-react below: same schema mismatch.
                    put("ship", ourPatp)
                })
            })
        } else {
            pokeAt(ch, r.whom, r.postId, r.parentId, buildJsonObject {
                put("add-react", buildJsonObject {
                    put("author", ourPatp)
                    put("react", glyph)
                })
            }, buildJsonObject {
                put("add-react", buildJsonObject {
                    put("id", dotAtom(r.postId))
                    // %channels c-react expects `ship`, not
                    // `author` — sending `author` produces a
                    // poke-as cast fail on the server and the
                    // reaction is silently dropped (only the
                    // local optimistic upsert sticks, and
                    // other devices never see the vote).
                    put("ship", ourPatp)
                    put("react", glyph)
                })
            })
        }
    }

    /**
     * Send [r] on the repo's scope; the ship's refusal is thrown, and a
     * ship out of reach queues it, the newest intention per post, for
     * [drainQueue].
     */
    private suspend fun sendReactOrQueue(r: QueuedReact) = pushScope.async {
        val ch = channel
        if (ch == null) {
            queuedReacts.update { it + (r.key to r) }
            return@async stillSlow(IllegalStateException("not connected to the ship"))
        }
        try {
            sendReact(ch, r)
            // Sent: an older intention for this post is no longer the one.
            queuedReacts.update { it - r.key }
            shipAnswered()
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            if (t is PokeNacked) throw t
            queuedReacts.update { it + (r.key to r) }
            stillSlow(t)
        }
    }.await()

    /** Remove our reaction from a post. */
    suspend fun unreact(whom: String, postId: String, parentId: String? = null) {
        // Gone now, as react() shows at once; a refusal brings it back,
        // and a ship out of reach leaves it gone, queued.
        val rowId = postId.replace(".", "")
        val before = db.reactions().get(whom, rowId, ourPatp)
        db.reactions().delete(whom, rowId, ourPatp)
        try {
            sendReactOrQueue(QueuedReact(whom, postId, parentId, glyph = null))
        } catch (t: Throwable) {
            before?.let { db.reactions().upsert(it) }
            throw t
        }
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
        if (whomNeedsOptimisticDelete(whom)) {
            // Mirror what react/unreact do — apply the local change
            // immediately so the message disappears regardless of
            // whether the SSE echo arrives. The DM/club `del`
            // round-trip has been unreliable across mark drift and
            // we'd previously regressed by trusting it.
            db.messages().softDeleteWithMedia(db.messageMedia(), whom, postId)
            db.reactions().clearForPost(whom, postId)
        }
        // Channel-action-2 `id` / `del` fields dejs through
        // `slav %ud`, which requires dot-grouped decimals.
        pokeAt(
            ch, whom, postId, parentId,
            buildJsonObject { put("del", JsonNull) },
            buildJsonObject { put("del", JsonPrimitive(dotAtom(postId))) },
        )
    }

    /**
     * Poke a change to [postId]: [writDelta] in a DM or club, [postAction]
     * in a channel. A reply's change goes through its parent, [parentId],
     * as the ship's reply action; sent as a change to a post, it named
     * no post, and reactions on replies landed nowhere. The chat parser
     * requires a reply's `meta`, null or not: without it the ship
     * refused every reply delete in a DM or club.
     */
    private suspend fun pokeAt(
        ch: UrbitChannel,
        whom: String,
        postId: String,
        parentId: String?,
        writDelta: JsonObject,
        postAction: JsonObject,
    ) {
        val writ = if (parentId == null) writDelta else buildJsonObject {
            put("reply", buildJsonObject {
                put("id", redotWritId(postId))
                put("meta", JsonNull)
                put("delta", writDelta)
            })
        }
        when {
            whom.startsWith("~") -> ch.poke(
                app = "chat", mark = "chat-dm-action-2",
                payload = dmAction(whom, parentId ?: postId, writ),
            )
            whom.startsWith("0v") -> ch.poke(
                app = "chat", mark = "chat-club-action-2",
                payload = clubAction(whom, parentId ?: postId, writ),
            )
            whom.startsWith("chat/") ||
                whom.startsWith("diary/") ||
                whom.startsWith("heap/") -> ch.poke(
                app = "channels", mark = "channel-action-2",
                payload = channelAction(whom, buildJsonObject {
                    put("post", if (parentId == null) postAction else buildJsonObject {
                        put("reply", buildJsonObject {
                            put("id", dotAtom(parentId))
                            put("action", postAction)
                        })
                    })
                }),
            )
            else -> error("unsupported whom: $whom")
        }
    }

    /**
     * Reply to a top-level post. `parentId` is the post being replied to.
     * Returns the minted reply id so callers can match the echo.
     */
    suspend fun reply(whom: String, parentId: String, text: String): String =
        replyContent(whom, parentId, textToStory(text))

    /** Reply with a structured image block, so it renders inline in the
     *  thread instead of as the markdown link the old text fallback
     *  produced. A reply's content is a full story, same as a post's —
     *  there was never a wire reason to degrade it. */
    suspend fun replyImage(
        whom: String,
        parentId: String,
        src: String,
        width: Int,
        height: Int,
        alt: String,
        caption: String = "",
    ): String = replyContent(whom, parentId, imageStory(src, width, height, alt, caption))

    private suspend fun replyContent(
        whom: String,
        parentId: String,
        content: JsonArray,
    ): String {
        val sent = nowMs()
        val da = UrbitTime.unixMsToDa(sent)
        // Same local-sentinel id rule as postContent — %channels assigns
        // unpredictable post ids so we can't pre-compute them.
        val replyId = if (
            whom.startsWith("chat/") ||
            whom.startsWith("diary/") ||
            whom.startsWith("heap/")
        ) "local_${da}" else UrbitTime.formatPostId(ourPatp, da)
        val replyEssay = buildJsonObject {
            put("content", content)
            put("author", ourPatp)
            put("sent", sent)
            put("blob", JsonNull)
        }

        val out = replyPoke(whom, parentId, replyId, replyEssay)
        // In the thread now, and the ship told after: the poke waits for
        // the ship's ack, and the reply used to wait with it. A channel
        // reply is pending until its echo, which replaces it; a DM or
        // club reply's echo carries the same id. A refusal marks it
        // failed, a ship out of reach queues it.
        db.messages().upsertWithMedia(
            db.messageMedia(),
            toReplyEntity(whom, parentId, replyId, replyEssay)
                .let { if (isChannelNest(whom)) it.copy(status = "pending") else it },
        )
        sendOrQueue(whom, replyId, out)
        joinedThread(whom, parentId)
        return replyId
    }

    /**
     * Edit a channel message's text content. %chat (DMs and clubs)
     * rejects edit actions on the ships we've tested even though the
     * action mold in newer Hoon sources includes an `%edit` variant —
     * so we only offer this for %channels chat-channels.
     *
     * When `parentId` is non-null the post is a thread reply: the
     * edit is wrapped in the parent's `%reply` action, mirroring the
     * reply-delete shape (see [delete]). `parentId == null` is the
     * top-level case.
     */
    suspend fun edit(
        whom: String,
        postId: String,
        text: String,
        originalSentMs: Long,
        parentId: String? = null,
        /** The post's current contentJson. Blocks the text editor can't
         *  represent — a quoted post's cite, an image, a link preview —
         *  are carried across the edit instead of being flattened into
         *  literal text. Null re-parses [text] alone (legacy callers). */
        originalContentJson: String? = null,
    ) {
        val ch = channel ?: error("not connected")
        if (!whom.startsWith("chat/")) error("edit only supported on channel chats")
        val content = originalContentJson
            ?.let { editedStory(it, text) }
            ?: textToStory(text)
        // Preserve the original `sent` — the server sorts by it and
        // re-using our current time would bump the post to "just now".
        // Tlon keeps the original essay shell and only swaps content.
        val inner = if (parentId != null) {
            // channel-action-2 reply.action.edit expects `reply-essay`
            // (the leaner shape — no kind/meta), NOT the full `essay`.
            // The agent's dejs NACKs with `[%key 'reply-essay']` on
            // the latter. Mirrors the `reply-essay` reads in the SSE
            // ingest path (see classifyReply / applyChatReplyDelta).
            val replyEssay = buildJsonObject {
                put("content", content)
                put("author", ourPatp)
                put("sent", originalSentMs)
                put("blob", JsonNull)
            }
            buildJsonObject {
                put("post", buildJsonObject {
                    put("reply", buildJsonObject {
                        put("id", dotAtom(parentId))
                        put("action", buildJsonObject {
                            put("edit", buildJsonObject {
                                put("id", dotAtom(postId))
                                put("reply-essay", replyEssay)
                            })
                        })
                    })
                })
            }
        } else {
            val essay = buildEssay(content, originalSentMs)
            buildJsonObject {
                put("post", buildJsonObject {
                    // channel-action-2's `id` dejs is `(se %ud)` which runs
                    // `slav %ud` → `dem:ag`, and dem:ag demands Urbit-style
                    // dot-grouped decimals for numbers ≥ 1000.
                    put("edit", buildJsonObject {
                        put("id", dotAtom(postId))
                        put("essay", essay)
                    })
                })
            }
        }
        ch.poke(
            app = "channels", mark = "channel-action-2",
            payload = channelAction(whom, inner),
        )
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
        // The post as the ship has it: cites and image dimensions have
        // no markdown form and come back from here (mergeEdit), and
        // description / cover are not stored locally at all. Without it
        // the edit blanked all of those for every reader, so no answer
        // is no edit: the words stay in the composer to try again.
        val prior = runCatching {
            ch.scry("channels", "/v5/$nest/posts/post/${dotAtom(postId)}") as? JsonObject
        }.getOrNull()?.get("essay") as? JsonObject
            ?: error("The ship did not send the post as it stands, so nothing was changed. Try again.")
        val priorMeta = prior["meta"] as? JsonObject
        val content = MarkdownBlocks.mergeEdit(
            prior = prior["content"] as? JsonArray,
            parsed = MarkdownBlocks.toStory(bodyMarkdown, tables = false),
        )
        val meta = buildJsonObject {
            put("title", title)
            put("image", image)
            put("description", priorMeta?.get("description").asStr().orEmpty())
            put("cover", priorMeta?.get("cover").asStr().orEmpty())
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
        Log.i(TAG, "pinPost nest=$nest post=$postId")
        // Pinned now, the ship told after; a refusal puts back what was pinned.
        ensureChannelGroupRow(nest)
        val affected = db.groups().setPinnedPostId(nest, postId)
        if (affected == 0) {
            Log.w(TAG, "pinPost local UPDATE matched 0 rows for nest=$nest — channel_groups row missing despite ensureChannelGroupRow")
        }
        try {
            ch.poke(
                app = "channels", mark = "channel-action-2",
                payload = channelAction(nest, channelOrderAction(next)),
            )
        } catch (t: Throwable) {
            db.groups().setPinnedPostId(nest, current)
            throw t
        }
    }

    /** Unpin whatever is currently pinned in [nest]. */
    suspend fun unpinPost(nest: String) {
        require(nest.startsWith("chat/")) { "pin only supported on chat channels: $nest" }
        val ch = channel ?: error("not connected")
        Log.i(TAG, "unpinPost nest=$nest")
        val current = db.groups().pinnedPostIdFor(nest)
        ensureChannelGroupRow(nest)
        val affected = db.groups().setPinnedPostId(nest, null)
        if (affected == 0) {
            Log.w(TAG, "unpinPost local UPDATE matched 0 rows for nest=$nest — channel_groups row missing despite ensureChannelGroupRow")
        }
        try {
            ch.poke(
                app = "channels", mark = "channel-action-2",
                payload = channelAction(nest, channelOrderAction(emptyList())),
            )
        } catch (t: Throwable) {
            db.groups().setPinnedPostId(nest, current)
            throw t
        }
    }

    /**
     * Make sure a [channel_groups] row exists for [nest] before we try
     * to update its `pinnedPostId`. Without this, a chat the user has
     * been using but whose group bootstrap hadn't run / had partially
     * failed would silently drop pin writes (UPDATE matches 0 rows).
     *
     * The row needs a `groupFlag`. We derive it from the nest itself —
     * `chat/~host/group-name` maps to flag `~host/group-name`. That
     * matches what %groups would have inserted; later bootstraps
     * upsert by primary key so they overwrite this stub cleanly.
     *
     * Visible to tests so the regression guard around silent UPDATE
     * no-ops can exercise this path without a live UrbitChannel.
     */
    internal suspend fun ensureChannelGroupRow(nest: String) {
        val existing = db.groups().channelGroupFor(nest)
        if (existing != null) return
        val flag = nest.substringAfter("chat/", missingDelimiterValue = "")
            .ifBlank { return }
        Log.i(TAG, "ensureChannelGroupRow inserting stub for nest=$nest flag=$flag")
        db.groups().upsertChannelGroupsKeepingPin(
            listOf(
                io.nisfeb.talon.data.ChannelGroupEntity(
                    nest = nest,
                    groupFlag = flag,
                    title = null,
                    pinnedPostId = null,
                    ordinal = 0,
                )
            )
        )
    }

    // ───────── ingest ─────────

    /**
     * Pull the user's recent post history off the ship.
     *
     * `count` controls how many entries the `groups-ui /init-posts/N/N`
     * scry returns per source — 10 for the fast first-paint pass, 50
     * for the deep follow-up fill. The two are upserted into the same
     * tables; the second pass overwrites/augments the first, so the UI
     * gracefully gains older history without flickering or losing
     * scroll position. Caller controls the timeout via
     * [BOOTSTRAP_TIMEOUT_SECS] — heavy ships need it.
     */
    private suspend fun bootstrap(channel: UrbitChannel, count: Int) {
        val body = channel.scry(
            "groups-ui",
            "/v6/init-posts/$count/$count",
            BOOTSTRAP_TIMEOUT_SECS,
        )
        ingestPosts(body, "init-posts (count=$count)")
    }

    /**
     * When the last read of every chat's posts began, init-posts or
     * /changes; 0 before the first. In memory only: a launch reads
     * init-posts, as it always has.
     */
    @Volatile private var postsReadMs = 0L

    /** This ship answered no /changes path: init-posts, as before. */
    @Volatile private var changesUnserved = false

    /**
     * What every chat gained since the last such read: one /changes read
     * while that read is under three days old, as Tlon's client catches
     * up; init-posts' newest ten of each otherwise. /changes has all of
     * them, where ten a chat left a gap in a busy one.
     */
    private suspend fun catchUpPosts(ch: UrbitChannel) {
        val start = nowMs()
        val since = postsReadMs
        if (useChanges(since, start, changesUnserved)) {
            try {
                readChanges(ch, since - CHANGES_OVERLAP_MS)
                postsReadMs = start
                return
            } catch (t: Throwable) {
                // A slow ship is not asked for init-posts on top.
                if (t is kotlinx.coroutines.CancellationException || !notServed(t)) throw t
                Log.w(TAG, "no /changes on this ship; init-posts instead")
                changesUnserved = true
            }
        }
        bootstrap(ch, count = INITIAL_PAGE_COUNT)
        postsReadMs = start
    }

    /**
     * groups-ui's /changes since [sinceMs]. From /v8 its chat and channels
     * parts are init-posts' own (chat /v4, channels /v6), so they go in
     * the same way; its activity part leaves out reads made elsewhere, so
     * the activity read stays.
     */
    private suspend fun readChanges(ch: UrbitChannel, sinceMs: Long) {
        val da = UrbitTime.unixMsToDaText(sinceMs)
        val body = scryNewest(
            ch, "groups-ui",
            "/v11/changes/$da", "/v10/changes/$da", "/v9/changes/$da", "/v8/changes/$da",
            timeoutSecs = BOOTSTRAP_TIMEOUT_SECS,
        )
        ingestPosts(body, "changes since $da")
    }

    /**
     * What a watch the ship dropped would have carried while it was down,
     * read back once it is watched again. Presence has nothing to read.
     */
    private suspend fun catchUpAfterQuit(ch: UrbitChannel, app: String, path: String) {
        when {
            path == "/dm/invited" -> bootstrapDmInvites(ch, notify = true)
            app == "chat" || app == "channels" -> catchUpPosts(ch)
            app == "activity" -> {
                bootstrapActivity(ch).also { notificationHealth.markReconcileSuccess() }
                bootstrapFollowedThreads(ch)
            }
            app == "contacts" -> bootstrapContacts(ch)
            app == "groups" -> {
                bootstrapGroups(ch)
                refreshInvites(notify = false)
            }
        }
    }

    /** A chat-and-channels post map, init-posts' or /changes', into the tables. */
    private suspend fun ingestPosts(body: JsonElement, what: String) {
        val obj = body as? JsonObject
        if (obj == null) {
            // Used to silently `return` here. Real ships have returned
            // a non-JsonObject response in the wild (~ricsul-bilwyt
            // running an older Tlon hoon, etc.) and the empty UI was
            // indistinguishable from "ship genuinely has no chats."
            // Surface it.
            Log.w(
                TAG,
                "$what returned non-object: ${body::class.simpleName} " +
                    "preview=${body.toString().take(200)}",
            )
            return
        }
        val chatPeers = (obj["chat"] as? JsonObject)?.size ?: 0
        val channelNests = (obj["channels"] as? JsonObject)?.size ?: 0
        Log.i(
            TAG,
            "$what: chat-peers=$chatPeers " +
                "channel-nests=$channelNests top-keys=${obj.keys}",
        )

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
        Log.i(
            TAG,
            "$what ingested: messages=${messages.size} reactions=${reactions.size}",
        )

        if (messages.isNotEmpty()) db.messages().upsertAllWithMedia(db.messageMedia(), messages)
        if (reactions.isNotEmpty()) db.reactions().upsertAll(reactions)

        // Reap our own optimistic grey twins against this scry, not just
        // against live SSE deltas. If the SSE echo for a just-sent post
        // was missed (stream dropped between poke and echo), the grey
        // `local_*` row would otherwise linger while this re-scry re-adds
        // the same post under its real id — the two-rows duplicate. The
        // real row carries the same (whom, author, sentMs), so an exact
        // reap clears the twin on the next reconnect regardless of SSE
        // health. Exact-match only (not reapOwnEchoTwin) so the FIFO
        // fallback can't clobber a *different* still-pending post's twin
        // when this snapshot lands mid-flight. Idempotent once gone.
        messages.filter { it.author == ourPatp && it.parentId == null && !it.id.startsWith("local_") }
            .forEach {
                db.messages().reapLocalTwin(it.whom, ourPatp, it.sentMs)
                db.messageMedia().reapLocalTwinMedia(it.whom, ourPatp, it.sentMs)
            }
    }

    // internal, not private: repo tests feed it eyre-shaped facts directly.
    internal suspend fun applyEvent(event: JsonElement) {
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
                // A rejected subscribe may just mean the ship is older
                // than the wire version we asked for. Retry the path we
                // registered as this request's fallback, once.
                if (response == "subscribe" && pokeIdLong != null) {
                    subPaths.remove(pokeIdLong)
                    subFallbacks.remove(pokeIdLong)?.let { (app, paths) ->
                        val path = paths.first()
                        // Remembered by the family's newest path, for the next connect.
                        subFamilyOf(app, path)?.let { (family, index) -> subServed[family] = index }
                        Log.w(TAG, "$app subscribe rejected; falling back to $path")
                        scope.launch {
                            runCatching { channel?.subscribe(app, path) }
                                .onSuccess { id ->
                                    if (id != null) subPaths[id] = app to path
                                    if (id != null && paths.size > 1) subFallbacks[id] = app to paths.drop(1)
                                }
                                .onFailure { Log.e(TAG, "$app fallback subscribe failed", it) }
                        }
                    }
                }
            }
            return
        }
        if (response == "quit") {
            // The ship dropped a subscription: a clog while the stream was
            // down, or an agent's upgrade. One of ours is watched again on
            // this channel, as Tlon's client and the calls loop do, and what
            // it would have carried meanwhile is read: an upgrade of one
            // agent cost a new channel, every watch and the whole read.
            val now = nowMs()
            val sub = outer["id"].asLong()?.let { subPaths.remove(it) }
            val live = channel?.takeIf { !it.gone }
            val before = sub?.let { lastQuitMs["${it.first}${it.second}"] }
            sub?.let { lastQuitMs["${it.first}${it.second}"] = now }
            if (sub != null && live != null && resubscribeAfterQuit(before, now)) {
                Log.w(TAG, "${sub.first}${sub.second} dropped by the ship; watching it again")
                scope.launch {
                    runCatching {
                        subPaths[live.subscribe(sub.first, sub.second)] = sub
                        catchUpAfterQuit(live, sub.first, sub.second)
                    }.onFailure { Log.w(TAG, "${sub.first}${sub.second} not watched again", it) }
                }
                return
            }
            // Not one of ours (a settings watch), or dropped again at once:
            // the next pass is a new channel, every watch and the whole read.
            channel?.let { old ->
                Log.w(TAG, "subscription ${outer["id"]} dropped by the ship; opening a new channel")
                old.deleteSoon(kotlinx.coroutines.CoroutineScope(io.nisfeb.talon.util.ioDispatcher))
                lastBootstrapMs = 0L
                sessionJob?.cancel()
            }
            return
        }
        if (response != "diff") return
        // %chat pushes the full pending-DM-invite ship list as a bare
        // JSON array on its /dm/invited subscription (the other agents
        // only ever emit objects, so an array is unambiguously this). Handle
        // it before the object cast below drops it on the floor — that
        // drop is why brand-new DMs were invisible.
        (outer["json"] as? JsonArray)?.let { applyDmInvites(it, notify = true); return }
        val payload = outer["json"] as? JsonObject ?: return
        dmStatusOf(payload)?.let { (ship, net) -> applyDmStatus(ship, net); return }

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
        if (payload.containsKey("activity") || payload.containsKey("read") || payload.containsKey("del") || payload.containsKey("adjust")) {
            applyActivityUpdate(payload)
            return
        }

        // %contacts /v1/news — {page}, {wipe}, {peer} or {self} envelope.
        if (payload.containsKey("page") || payload.containsKey("wipe") ||
            payload.containsKey("peer") || payload.containsKey("self")
        ) {
            applyContactsNews(payload)
            return
        }

        // %presence /v1 — {init}, {here}, or {gone}.
        if (
            payload.containsKey("init") ||
            payload.containsKey("here") ||
            payload.containsKey("gone")
        ) {
            applyPresence(payload)
            return
        }

        // %settings events. The agent emits them wrapped —
        // {"settings-event": {"put-entry": …}} — but older builds sent
        // the action bare, so accept both. Matching only the bare shape
        // is why live settings changes never reached a second device:
        // the fact arrived and fell through to the "unknown" branch.
        val settingsAction =
            (payload["settings-event"] as? JsonObject) ?: payload
        if (
            settingsAction.containsKey("put-entry") ||
            settingsAction.containsKey("del-entry") ||
            settingsAction.containsKey("put-bucket") ||
            settingsAction.containsKey("del-bucket")
        ) {
            settingsSync?.applySettingsEvent(settingsAction)
            return
        }

        // %notes /v0/notes/<host>/<name>/stream — notebook, folder, note
        // and membership deltas. Facts don't name their agent, so this
        // matches on the shape only %notes emits (type + host + flagName).
        if (NotesRepo.isNotesEvent(payload)) {
            notes.applyNotesEvent(payload)
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

        // %groups /v1/foreigns (foreigns-1) or, on an older ship,
        // /gangs/updates: a bare map of flag → that group's invites,
        // preview and join progress. Someone invited us, or a join moved.
        // Applied to the badge and list, and the new ones toasted.
        if (looksLikeGangsFact(payload)) {
            scope.launch {
                runCatching { applyForeigns(payload) }
                    .onFailure { Log.w(TAG, "invites on gang update failed", it) }
            }
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
                    db.groups().upsertChannelGroupsKeepingPin(intent.channels.map { (nest, title) ->
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
                db.groups().upsertChannelGroupsKeepingPin(listOf(
                    ChannelGroupEntity(
                        nest = intent.nest,
                        groupFlag = intent.flag,
                        title = intent.title,
                    )
                ))
            }
            is GroupEventIntent.EditChannel -> {
                db.groups().upsertChannelGroupsKeepingPin(listOf(
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
            db.messages().upsertWithMedia(db.messageMedia(), entity)
            if (entity.author != ourPatp) messageListener?.invoke(entity, false)
            return
        }
        response["del"]?.let {
            db.messages().softDeleteWithMedia(db.messageMedia(), whom, id)
            db.reactions().clearForPost(whom, id)
            return
        }
        (response["add-react"] as? JsonObject)?.let { ar ->
            val author = ar["author"].asAuthorShip() ?: return@let
            val react = ar["react"].asStr() ?: return@let
            db.reactions().upsert(ReactionEntity(whom, id, author, ReactionPalette.normalize(react)))
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
            db.messages().upsertWithMedia(db.messageMedia(), entity)
            // For the owner, whatever the chat's level: following is asking.
            if (entity.author != ourPatp && replyCounts(entity)) messageListener?.invoke(entity, true)
            return
        }
        delta["del"]?.let {
            db.messages().softDeleteWithMedia(db.messageMedia(), whom, replyId)
            db.reactions().clearForPost(whom, replyId)
            return
        }
        (delta["add-react"] as? JsonObject)?.let { ar ->
            val author = ar["author"].asAuthorShip() ?: return@let
            val react = ar["react"].asStr() ?: return@let
            db.reactions().upsert(ReactionEntity(whom, replyId, author, ReactionPalette.normalize(react)))
            return
        }
        delta["del-react"].asStr()?.let { author ->
            db.reactions().delete(whom, replyId, author)
        }
    }

    /**
     * Remove our optimistic grey (`local_*`) twin once the host echoes
     * the post back under its real id, so the message flips grey→white
     * in place instead of showing as a grey+white duplicate.
     *
     * The exact (whom, author, sentMs) match is the normal path. If the
     * host didn't round-trip essay.sent byte-for-byte it misses, so for
     * a *fresh* echo (the recency guard keeps a re-delivered old history
     * item from clobbering a different in-flight post) fall back to
     * reaping the oldest still-pending twin — echoes arrive in send
     * order, so oldest-first is the right one. The log distinguishes a
     * sent-skew miss from a merely slow reap.
     */
    private suspend fun reapOwnEchoTwin(whom: String, sentMs: Long) {
        val reaped = db.messages().reapLocalTwin(whom, ourPatp, sentMs)
        db.messageMedia().reapLocalTwinMedia(whom, ourPatp, sentMs)
        if (reaped > 0) {
            // Round-trip: sentMs is stamped at poke time, so this is the
            // full send→echo→grey-clears latency, which investigation
            // showed is the ship's poke-processing time, not our wire.
            val latencyMs = nowMs() - sentMs
            Log.i(TAG, "echo reaped twin whom=$whom latencyMs=$latencyMs")
        } else if (nowMs() - sentMs < 60_000) {
            val fallback = db.messages().reapOldestLocalTwin(whom, ourPatp)
            if (fallback > 0) {
                Log.w(TAG, "reap exact miss whom=$whom sentMs=$sentMs latencyMs=${nowMs() - sentMs}; FIFO fallback cleared grey twin")
            }
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
                if (messages.isNotEmpty()) db.messages().upsertAllWithMedia(db.messageMedia(), messages)
                if (reactions.isNotEmpty()) db.reactions().upsertAll(reactions)
                // Reap our own grey twins for any echo that arrived batched
                // (SSE reconnect / paginated catch-up) rather than as a
                // singular PostSet — otherwise those grey duplicates linger
                // until the next full bootstrap. reapOwnEchoTwin's exact-
                // sentMs match no-ops on re-delivered history, so this is
                // safe to run over every own post in the batch.
                messages.filter { it.author == ourPatp && it.parentId == null }
                    .forEach { reapOwnEchoTwin(nest, it.sentMs) }
            }
            is ChannelDeltaIntent.PostSet -> {
                val msgs = mutableListOf<MessageEntity>()
                val rx = mutableListOf<ReactionEntity>()
                ingestPost(nest, intent.post, msgs, rx)
                db.messages().upsertAllWithMedia(db.messageMedia(), msgs)
                if (rx.isNotEmpty()) {
                    db.reactions().clearForPost(nest, intent.id)
                    db.reactions().upsertAll(rx)
                }
                msgs.firstOrNull { it.id == intent.id && it.author == ourPatp }?.let {
                    reapOwnEchoTwin(nest, it.sentMs)
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
                db.messages().softDeleteWithMedia(db.messageMedia(), nest, id)
                db.reactions().clearForPost(nest, id)
            }
            is ChannelDeltaIntent.PostReactions -> {
                db.reactions().clearForPost(nest, intent.id)
                val rx = intent.reacts.entries.mapNotNull { (author, emoji) ->
                    val e = emoji.asStr() ?: return@mapNotNull null
                    ReactionEntity(nest, intent.id, author, ReactionPalette.normalize(e))
                }
                if (rx.isNotEmpty()) db.reactions().upsertAll(rx)
            }
            is ChannelDeltaIntent.PostEssay -> {
                val entity = toEntity(nest, intent.id, intent.essay)
                db.messages().upsertWithMedia(db.messageMedia(), entity)
                // Edits don't trigger a notification.
            }
            is ChannelDeltaIntent.Reply ->
                applyReplyIntent(nest, intent.parentId, intent.replyId, intent.inner)
            is ChannelDeltaIntent.OrderUpdate -> {
                val pinId = intent.postIds.firstOrNull()
                ensureChannelGroupRow(nest)
                val affected = db.groups().setPinnedPostId(nest, pinId)
                if (affected == 0) {
                    Log.w(TAG, "OrderUpdate UPDATE matched 0 rows for nest=$nest pin=$pinId")
                }
            }
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
                db.messages().upsertWithMedia(db.messageMedia(), entity)
                if (entity.author == ourPatp) {
                    reapOwnEchoTwin(whom, entity.sentMs)
                } else if (replyCounts(entity)) {
                    messageListener?.invoke(entity, true)
                }
            }
            is ReplyIntent.Tombstone, is ReplyIntent.Deleted -> {
                db.messages().softDeleteWithMedia(db.messageMedia(), whom, replyId)
                db.reactions().clearForPost(whom, replyId)
            }
            is ReplyIntent.Reactions -> {
                db.reactions().clearForPost(whom, replyId)
                val rx = inner.reacts.entries.mapNotNull { (author, emoji) ->
                    val e = emoji.asStr() ?: return@mapNotNull null
                    ReactionEntity(whom, replyId, author, ReactionPalette.normalize(e))
                }
                if (rx.isNotEmpty()) db.reactions().upsertAll(rx)
            }
            is ReplyIntent.Unknown -> Unit
        }
    }

    // ───────── activity / unreads ─────────

    private suspend fun bootstrapActivity(channel: UrbitChannel) {
        // Activity scry covers every conversation's unread state — also
        // grows with chat count. Same generous timeout as init-posts
        // (see [BOOTSTRAP_TIMEOUT_SECS]). Far less common to hit but
        // the budget is cheap.
        //
        // `/v4/activity/full` (NOT plain `/v4/activity`) — the latter
        // calls `strip-threads activity` in the agent's peek arm,
        // returning only conversation-level (channel/ship/club)
        // summaries. Without `full` the per-thread `thread/<nest>/<da>`
        // and `dm-thread/<whom>/<author>/<da>` sources never surface,
        // and ThreadUnreadEntity never populates — leaving the
        // per-row indicator tint and in-thread "New" divider perma-off.
        // /v6 (12.1.0) has notebook sources; an older ship has only /v4.
        // Not known again until this read of it lands: what came in while
        // the connection was down is not in the counts here.
        activityKnown = false
        val body = scryNewest(channel, "activity", "/v6/activity/full", "/v4/activity/full", timeoutSecs = BOOTSTRAP_TIMEOUT_SECS)
        val obj = body as? JsonObject
        obj?.let { baseNotifyCount(it) }?.let { _notifiedTotal.value = it }
        if (obj == null) {
            Log.w(
                TAG,
                "activity scry returned non-object: ${body::class.simpleName} " +
                    "preview=${body.toString().take(200)}",
            )
            return
        }
        val focused = focusedWhom()
        val rows = obj.entries.mapNotNull { (sourceKey, summary) ->
            toUnread(sourceKey, summary as? JsonObject ?: return@mapNotNull null)
        }.map { row ->
            // Respect the focus-override so a post-mark-read scry race
            // doesn't re-bump the badge while the user is still looking.
            // Nor one read here that the ship has not heard yet.
            if (row.whom == focused || readHere(row.whom, row.recencyMs)) row.copy(count = 0, notifyCount = 0) else row
        }
        // Per-thread breakdown — separate table so the message-row
        // thread indicator can tint when its specific thread has
        // unread events, and the in-thread view can render the "New"
        // divider above the first unread reply. The channel-level
        // [rows] above already reflects channel-direct activity from
        // the `channel/<nest>` source; thread rows are independent
        // (no double-counting because `sourceKeyToWhom` returns null
        // for `thread/` / `dm-thread/` now).
        //
        // No focus-override on thread rows: the user being focused on
        // the *channel* doesn't mean they've opened the *thread*. The
        // tint should appear under the parent message even while the
        // channel is open, and only clear when they tap into the
        // specific thread (`markThreadReadLocal`).
        val threadRows = obj.entries.mapNotNull { (sourceKey, summary) ->
            toThreadUnread(sourceKey, summary as? JsonObject ?: return@mapNotNull null)
        }.map { row ->
            if (readHere("${row.whom}#${row.parentPostId}", row.recencyMs)) row.copy(count = 0, notifyCount = 0) else row
        }
        Log.i(
            TAG,
            "activity scry: total-keys=${obj.size} unread-rows=${rows.size} " +
                "thread-rows=${threadRows.size}",
        )
        if (rows.isNotEmpty()) db.unreads().upsertAll(rows)
        if (threadRows.isNotEmpty()) db.threadUnreads().upsertAll(threadRows)
        activityKnown = true
    }

    /** %activity's base notify-count: what the app-icon badge shows, the
     *  number the ship's %trunk sends with an iPhone's alerts. Null until a
     *  summary carries it. */
    private val _notifiedTotal = MutableStateFlow<Int?>(null)
    val notifiedTotal: StateFlow<Int?> = _notifiedTotal.asStateFlow()

    internal suspend fun applyActivityUpdate(obj: JsonObject) {
        val focused = focusedWhom()
        (obj["activity"] as? JsonObject)?.let { map -> baseNotifyCount(map)?.let { _notifiedTotal.value = it } }
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
                localView(row, focused)
            }
            // See bootstrapActivity for the no-focus-override rationale —
            // thread indicator should tint even while the channel is open.
            val focusedThreadNow = focusedThread()
            val threadRows = map.entries.mapNotNull { (key, summary) ->
                toThreadUnread(key, summary as? JsonObject ?: return@mapNotNull null)
            }.map { row ->
                // The thread the user is reading: replies landing now
                // are read, here and on the ship, like the focused chat.
                if (focusedThreadNow != null && row.whom == focusedThreadNow.first &&
                    row.parentPostId == focusedThreadNow.second && row.count > 0
                ) {
                    markReadSoon("${row.whom}#${row.parentPostId}") { markThreadRead(row.whom, row.parentPostId, force = true) }
                    row.copy(count = 0, notifyCount = 0)
                } else if (readHere("${row.whom}#${row.parentPostId}", row.recencyMs)) {
                    row.copy(count = 0, notifyCount = 0)
                } else row
            }
            if (rows.isNotEmpty()) db.unreads().upsertAll(rows)
            if (threadRows.isNotEmpty()) db.threadUnreads().upsertAll(threadRows)
            return
        }
        (obj["read"] as? JsonObject)?.let { read ->
            val source = read["source"] as? JsonObject ?: return@let
            val summary = read["activity"] as? JsonObject ?: return@let
            // A thread read on another client (or our own poke echoed
            // back): mirror the ship's count for that one thread.
            sourceToThreadSource(source)?.let { src ->
                db.threadUnreads().upsert(threadUnreadOf(src, summary))
                return
            }
            val whom = sourceToWhom(source) ?: return@let
            toUnread(sourceKey = null, summary = summary, overrideWhom = whom)
                ?.let { row ->
                    val adjusted = localView(row, focused)
                    db.unreads().upsert(adjusted)
                    // The whole of it, threads too: a channel caught up on its
                    // own stream may still have a reply notified here.
                    if (summary["count"].asInt() == 0 && summary["notify-count"].asInt() == 0) readListener?.invoke(row.whom)
                }
            return
        }
        // A thread followed or unfollowed, here or on another client, or by
        // the ship itself when a reply lands in a thread the owner is in.
        (obj["adjust"] as? JsonObject)?.let { applyAdjust(it); return }
        (obj["del"] as? JsonObject)?.let { source ->
            sourceToThreadSource(source)?.let { src ->
                db.threadUnreads().deleteOne(src.whom, src.parentPostId)
                return
            }
            sourceToWhom(source)?.let { db.unreads().delete(it) }
            return
        }
    }

    // ───────── pending DM requests ─────────

    /**
     * Scry the full pending-DM-invite list at (re)connect. `%chat`
     * `/dm/invited` returns a JSON array of inviter patps. A session's
     * first pass doesn't notify — a launch shouldn't fire a balloon for
     * every already-pending request; a reconnect does, because a
     * request new since the last pass arrived while we were down and
     * nobody has been told.
     */
    private suspend fun bootstrapDmInvites(channel: UrbitChannel, notify: Boolean) {
        val body = runCatching { channel.scry("chat", "/dm/invited") }
            .onFailure { Log.w(TAG, "dm/invited scry failed", it) }
            .getOrNull() as? JsonArray ?: return
        applyDmInvites(body, notify = notify)
    }

    /**
     * Reconcile the local pending-invite table against a fresh snapshot
     * (the complete list `%chat` sends each time). Ships new to us are
     * inserted (and, when [notify] is true, surfaced to
     * [dmInviteListener]); ships no longer present — accepted/declined
     * elsewhere — are removed.
     */
    internal suspend fun applyDmInvites(arr: JsonArray, notify: Boolean) {
        val incoming = arr.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            .filter { it.startsWith("~") }
            .toSet()
        val existing = db.dmInvites().allShips().toSet()
        val added = incoming - existing
        val removed = existing - incoming
        if (added.isNotEmpty()) {
            val now = nowMs()
            db.dmInvites().upsertAll(added.map { DmInviteEntity(ship = it, receivedMs = now) })
        }
        removed.forEach { db.dmInvites().delete(it) }
        if (notify) added.forEach { ship -> runCatching { dmInviteListener?.invoke(ship) } }
        // A request withdrawn by a live fact was answered on another
        // client. Accepted, the conversation may have no rows here: the
        // init scry leaves invited DMs out, and a writ that came while
        // this session was down is gone. Fetch it, or it stays unseen
        // until the next full bootstrap. Declined, the DM is gone and
        // the accepted-DM scry does not name it.
        if (notify && removed.isNotEmpty()) scope.launch { adoptAcceptedDms(removed) }
    }

    /**
     * %chat's word on one DM (12.3.0, chat-dm-status on /v4): it began,
     * changed, or is gone. Gone is our own decline or leave, made on
     * another device: %chat has dropped the DM and its messages, and so
     * does this one; it used to linger until reinstalled. Started or
     * accepted elsewhere, its messages are read now rather than at the
     * next full bootstrap. An invite needs nothing here: the invite list
     * fact comes with it ([applyDmInvites]).
     */
    internal suspend fun applyDmStatus(ship: String, net: String?) {
        when (net) {
            null -> {
                db.dmInvites().delete(ship)
                db.messages().deleteConversation(ship)
                db.unreads().delete(ship)
            }
            "inviting", "done" -> if (!db.messages().hasConversation(ship)) {
                scope.launch {
                    runCatching { refreshConversation(ship) }
                        .onFailure { Log.w(TAG, "refreshConversation($ship) after dm-status $net failed", it) }
                }
            }
        }
    }

    private suspend fun adoptAcceptedDms(ships: Set<String>) {
        val ch = channel ?: return
        val accepted = runCatching { ch.scry("chat", "/dm") }
            .onFailure { Log.w(TAG, "dm scry failed", it) }
            .getOrNull()
        for (ship in acceptedAmong(ships, accepted)) {
            runCatching { refreshConversation(ship) }
                .onFailure { Log.w(TAG, "refreshConversation($ship) after an rsvp elsewhere failed", it) }
        }
    }

    /**
     * Accept a pending DM request: poke `chat-dm-rsvp` with `ok=true`.
     * The DM becomes a real conversation whose writs then flow normally;
     * drop the local invite row immediately (the next `/v4` snapshot also
     * reflects it).
     */
    suspend fun acceptDmInvite(ship: String) = rsvpDmInvite(ship, accept = true)

    /** Decline a pending DM request: poke `chat-dm-rsvp` with `ok=false`. */
    suspend fun declineDmInvite(ship: String) = rsvpDmInvite(ship, accept = false)

    private suspend fun rsvpDmInvite(ship: String, accept: Boolean) {
        val ch = channel ?: error("not connected")
        ch.poke(
            app = "chat",
            mark = "chat-dm-rsvp",
            payload = buildJsonObject {
                put("ship", ship)
                put("ok", accept)
            },
        )
        db.dmInvites().delete(ship)
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
    suspend fun markRead(whom: String, force: Boolean = false) {
        val ch = channel ?: return
        // What the ship last said of it. Nothing unread, the poke said
        // nothing new: it went on every open, every exit and every focus.
        // Should the ship disagree, its next activity raises the count and
        // the next read goes.
        val had = db.unreads().getOne(whom)
        // Told the ship anyway where the counts here are not yet the ship's
        // (this connection has not read them) or a read of it is owed.
        val unread = owesRead(whom, had != null && (had.count > 0 || had.notifyCount > 0), force)

        // Clear the badge locally immediately so the list flips the moment
        // the user enters the conversation. The server fact will confirm.
        //
        // The chat list's own cache has to hear about this too: it is
        // torn down while the conversation is open, so it cannot see
        // the write below and would replay the old count on the way
        // back.
        io.nisfeb.talon.ui.screens.noteConversationRead(whom)
        db.unreads().upsert(
            UnreadEntity(
                whom = whom,
                count = 0,
                notifyCount = 0,
                recencyMs = nowMs(),
            )
        )
        // NOTE: deliberately does NOT clear per-thread unread rows here.
        // markRead fires when a CHANNEL is opened (setOpenChat), but
        // the user has to open the channel to even see the message
        // whose thread we want to tint — wiping thread_unreads on
        // channel-open made the accent tint structurally impossible to
        // observe (it cleared before render). Per-thread rows clear
        // only when the user opens the specific thread, via
        // [markThreadReadLocal] (ThreadList wires this). A thread the
        // user reads on another client self-heals: the next
        // /v4/activity/full scry returns that source with count=0 and
        // the upsert overwrites the stale row.

        val groupFlag = if (
            whom.startsWith("chat/") ||
            whom.startsWith("diary/") ||
            whom.startsWith("heap/")
        ) {
            db.groups().channelGroupFor(whom)?.groupFlag ?: run {
                Log.w(TAG, "markRead: no group flag for $whom; skipping poke")
                return
            }
        } else if (whom.startsWith("notes/")) {
            // A notebook may be in no group: its source says so with null.
            db.groups().channelGroupFor(whom)?.groupFlag
        } else null
        if (!unread) return
        val source = activityReadSource(whom, groupFlag) ?: return
        pokeActivityRead(ch, whom, source)
    }

    /**
     * Tell the ship one thread is read. The local row goes first so the
     * parent row's tint and the in-thread divider clear at once; the
     * poke keeps other clients and the next activity scry in agreement.
     * Channel parents are keyed by bare da, so the author comes from
     * the parent row; a parent we have not mirrored yet cannot be read
     * (nothing to show the user either).
     */
    /**
     * Whether the ship is told of a read of [key]: asked to, its counts
     * are not known yet on this connection, a read is owed, or it last
     * said there was something unread.
     */
    private fun owesRead(key: String, hadUnread: Boolean, force: Boolean): Boolean =
        force || !activityKnown || owedReads.value.containsKey(key) || hadUnread

    suspend fun markThreadRead(whom: String, parentPostId: String, force: Boolean = false) {
        val had = db.threadUnreads().getOne(whom, parentPostId)
        db.threadUnreads().deleteOne(whom, parentPostId)
        // Nothing unread here, as the ship last said: nothing to tell it.
        // Unless what it last said is not known yet, or a read is owed.
        if (!owesRead("$whom#$parentPostId", had != null && (had.count > 0 || had.notifyCount > 0), force)) return
        val ch = channel ?: return
        val source = threadSource(whom, parentPostId) ?: return
        pokeActivityRead(ch, "$whom#$parentPostId", source)
    }

    /**
     * %activity's source for the thread under [parentPostId]: a channel's
     * needs its group and the parent's author, so null while either is
     * unknown here.
     */
    private suspend fun threadSource(whom: String, parentPostId: String): JsonObject? {
        val isChannel = whom.startsWith("chat/") || whom.startsWith("diary/") || whom.startsWith("heap/")
        val groupFlag = if (isChannel) {
            db.groups().channelGroupFor(whom)?.groupFlag ?: run {
                Log.w(TAG, "no group flag for $whom; no thread source")
                return null
            }
        } else null
        val author = if (isChannel) {
            db.messages().getOne(whom, parentPostId)?.author ?: return null
        } else null
        return activityThreadReadSource(whom, parentPostId, author, groupFlag)
    }

    // ───────── followed threads ─────────

    /**
     * The threads the ship follows and unfollows, from %activity's volume
     * settings: Tlon's own record, made when a reply lands in a thread the
     * owner wrote, replied in or was named in, and by Talon when they
     * react or reply. A row the ship no longer names goes, bar a change
     * made here that has not reached it.
     */
    private suspend fun bootstrapFollowedThreads(ch: UrbitChannel) {
        val start = nowMs()
        val body = scryNewest(ch, "activity", "/v6/volume-settings", "/v5/volume-settings", "/v4/volume-settings") as? JsonObject ?: return
        val named = followedThreadsOf(body)
        val dao = db.followedThreads()
        val have = dao.all().associateBy { ThreadSource(it.whom, it.parentPostId) }
        // A change made here and not yet sent stands; one already stored as
        // the ship has it is not written again. One write each way: every
        // write re-reads the followed-threads list on each screen.
        dao.upsertAll(
            named.mapNotNull { (src, follow) ->
                val held = have[src]
                if (held != null && (!held.sent || held.follow == follow)) null
                else FollowedThreadEntity(src.whom, src.parentPostId, follow, sent = true, atMs = start)
            },
        )
        dao.deleteAll(have.values.filter { it.sent && it.atMs < start && ThreadSource(it.whom, it.parentPostId) !in named })
    }

    /** An adjust fact: one thread's follow changed, here or on another client. */
    private suspend fun applyAdjust(adjust: JsonObject) {
        val src = (adjust["source"] as? JsonObject)?.let(::sourceToThreadSource) ?: return
        val dao = db.followedThreads()
        val follow = (adjust["volume"] as? JsonObject)?.let(::followOf)
        if (follow == null) {
            // Back to the defaults, unless a change made here is on its way.
            if (dao.get(src.whom, src.parentPostId)?.sent != false) dao.delete(src.whom, src.parentPostId)
            return
        }
        dao.upsert(FollowedThreadEntity(src.whom, src.parentPostId, follow, sent = true, atMs = nowMs()))
    }

    /**
     * Follow a thread, or stop: its replies unread and notifying, or
     * neither, here at once and on the ship for the owner's other
     * clients and Tlon's apps. Carried on the repo's scope, so leaving the
     * screen does not stop it; a slow ship gets it with the queued
     * writes; a refusal puts back what was there and is thrown. Stopping
     * reads it too, or its count stays in Tlon's apps.
     */
    suspend fun setFollow(whom: String, parentPostId: String, follow: Boolean) = carry {
        val id = parentPostId.replace(".", "")
        val dao = db.followedThreads()
        val before = dao.get(whom, id)
        dao.upsert(FollowedThreadEntity(whom, id, follow, sent = false, atMs = nowMs()))
        try {
            val ch = channel ?: throw IllegalStateException("not connected to the ship")
            sendFollow(ch, whom, id, follow)
            shipAnswered()
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            if (isShipSlow(t)) {
                stillSlow(t)
                return@carry
            }
            if (before != null) dao.upsert(before) else dao.delete(whom, id)
            throw t
        }
        if (!follow) runCatching { markThreadRead(whom, id, force = true) }
    }

    /**
     * Read every one of [threads] (whom to parent post), as "mark all
     * read" asks: one read each, on the repo's scope, so leaving the
     * screen does not stop them.
     */
    fun markThreadsRead(threads: List<Pair<String, String>>) {
        pushScope.launch {
            threads.forEach { (whom, parent) ->
                runCatching { markThreadRead(whom, parent, force = true) }
                    .onFailure { Log.w(TAG, "thread not read: $whom $parent", it) }
            }
        }
    }

    /** One follow's adjust poke, marked sent once the ship takes it. */
    private suspend fun sendFollow(ch: UrbitChannel, whom: String, parentPostId: String, follow: Boolean) {
        val source = threadSource(whom, parentPostId)
            ?: throw IllegalStateException("that thread's channel or post is not known here")
        val action = buildJsonObject {
            put("adjust", buildJsonObject {
                put("source", source)
                put("volume", followVolume(whom, follow))
            })
        }
        // An older ship has only the original mark, as with reads.
        pokeNewest(ch, "activity", ACTIVITY_MARKS, action)
        db.followedThreads().get(whom, parentPostId)?.takeIf { it.follow == follow }
            ?.let { db.followedThreads().upsert(it.copy(sent = true)) }
    }

    /**
     * The owner is in a thread now, by a reply or a reaction: followed,
     * unless they already chose. A DM's thread counts already, but only a
     * row keeps it in the Threads lists once read, and Tlon's agents never
     * write one for a DM.
     */
    private fun joinedThread(whom: String, parentPostId: String) {
        val id = parentPostId.replace(".", "")
        pushScope.launch {
            if (db.followedThreads().get(whom, id) != null) return@launch
            runCatching { setFollow(whom, id, true) }.onFailure { Log.w(TAG, "not followed: $whom $id", it) }
        }
    }

    /** Whether a reply that just came counts for the owner (see [threadCounts]). */
    private suspend fun replyCounts(reply: MessageEntity): Boolean {
        val parentId = reply.parentId ?: return true
        val isDm = isDirect(reply.whom)
        val follow = db.followedThreads().get(reply.whom, parentId)?.follow
        if (follow != null || isDm) return threadCounts(follow, isDm, ours = false)
        val ours = io.nisfeb.talon.notify.isMentioned(reply.contentJson, ourPatp) ||
            db.messages().getOne(reply.whom, parentId)?.author == ourPatp ||
            db.messages().hasReplyBy(reply.whom, parentId, ourPatp)
        return threadCounts(null, isDm, ours)
    }

    /**
     * Retry on transient errors (channel cycle, socket reset
     * mid-poke). Mirrors Tlon's TS client (`backOff(...,
     * numOfAttempts: 4)` in activityApi.ts) — without retry, a
     * poke that races with a reconnect just dropped, leaving the
     * ship's unread count stuck and Tlon's UI showing a stale
     * badge after Talon already cleared it locally.
     */
    private suspend fun pokeActivityRead(ch: UrbitChannel, label: String, source: JsonObject) {
        val readAt = nowMs()
        val maxAttempts = 4
        var attempt = 0
        var lastErr: Throwable? = null
        while (attempt < maxAttempts) {
            val outcome = runCatching { sendActivityRead(ch, source) }
            if (outcome.isSuccess) {
                // Heard: whatever was owed for it is paid.
                owedReads.update { it - label }
                return
            }
            lastErr = outcome.exceptionOrNull()
            val transient = isTransientNetworkError(lastErr)
            if (!transient) break
            attempt += 1
            if (attempt < maxAttempts) {
                kotlinx.coroutines.delay(1_000L * (1 shl (attempt - 1)))
            }
        }
        lastErr?.let { err ->
            val transient = isTransientNetworkError(err)
            if (transient) {
                // Not heard: owed, and sent with the queued writes once the
                // ship answers, up to when it was read.
                Log.i(TAG, "markRead $label owed after $attempt attempts: ${err.message}")
                owedReads.update { it + (label to OwedRead(label, source, readAt)) }
                stillSlow(err)
            } else {
                owedReads.update { it - label }
                Log.w(TAG, "markRead poke failed for $label", err)
            }
        }
    }

    /** One read of [source] to %activity, under the mark this ship takes; up to [upTo] ms, or now. */
    private suspend fun sendActivityRead(ch: UrbitChannel, source: JsonObject, upTo: Long? = null) {
        // A notebook's badge is its notes': only a deep read clears them.
        val notebook = source.containsKey("notebook")
        val action = activityReadAction(source, deep = notebook, upTo = upTo)
        // activity-action-2 (12.1.0) is the mark Tlon's client sends,
        // and the only one that names a notebook. A ship that is
        // older refuses it; the original mark says the same of
        // anything else.
        if (notebook) ch.poke(app = "activity", mark = ACTIVITY_MARKS.first(), payload = action)
        else pokeNewest(ch, "activity", ACTIVITY_MARKS, action)
    }

    /**
     * One subscription, and the path to retry on if the ship rejects
     * it. Lets us ask for a newer wire version without betting the
     * whole stream on the ship being new enough to serve it.
     */
    private data class SubSpec(
        val app: String,
        val path: String,
        /** Older paths to try in turn, each on the one before's nack. */
        val fallbacks: List<String> = emptyList(),
    )

    /** subscribe request id → (app, the fallback paths left), pending a nack. */
    private val subFallbacks = ConcurrentMap<Long, Pair<String, List<String>>>()

    /** What each of the session's watches is, app to path, by request id: a dropped one is watched again. */
    private val subPaths = ConcurrentMap<Long, Pair<String, String>>()

    /** When the ship last dropped each app+path, so one dropped again at once opens a new channel instead. */
    private val lastQuitMs = ConcurrentMap<String, Long>()

    /** The subscriptions with fallbacks, so a nack's path can be placed in its family. */
    private val subSpecs = listOf(
        SubSpec("activity", "/v6", fallbacks = listOf("/v5", "/v4")),
        SubSpec("groups", "/v3/groups", fallbacks = listOf("/v1/groups")),
        SubSpec("groups", "/v1/foreigns", fallbacks = listOf("/gangs/updates")),
    )

    /** app + newest path → the index of the path this ship took, kept for the login. */
    private val subServed = ConcurrentMap<String, Int>()

    /** Per kind of conversation read ("chat/writs"), the version and mark that answered last. */
    private val walkServed = ConcurrentMap<String, Pair<String, String>>()

    private fun subFamilyOf(app: String, path: String): Pair<String, Int>? =
        subSpecs.firstOrNull { it.app == app && path in it.fallbacks }
            ?.let { "${it.app}${it.path}" to (it.fallbacks.indexOf(path) + 1) }

    // ───────── presence (typing indicators) ─────────

    /** One peer's live presence in a context. */
    private data class Live(val topic: String, val label: String, val expiresAtMs: Long)

    /** A peer can announce more than one topic at once, so the inner
     *  map is keyed by both. */
    private data class PresenceKey(val ship: String, val topic: String)

    /** context → ((ship, topic) → what they're doing, and until when). */
    private val _presence = MutableStateFlow<Map<String, Map<PresenceKey, Live>>>(emptyMap())
    private var presenceReaper: Job? = null

    /**
     * Who is doing what in [whom], as `ship → human label` ("typing…",
     * "recording audio", "uploading an image"). Empty when nobody is,
     * when the conversation has no presence context, or when the ship
     * predates %presence.
     */
    fun presenceIn(whom: String): Flow<Map<String, String>> {
        val context = Presence.contextFor(whom) ?: return flowOf(emptyMap())
        return _presence.map { places ->
            places[context].orEmpty()
                .entries
                .groupBy { it.key.ship }
                .mapValues { (_, entries) ->
                    // Show the most immediate thing they're doing.
                    entries.minBy { Presence.topicPriority(it.value.topic) }.value.label
                }
        }.distinctUntilChanged()
    }

    /**
     * When a group was last active — the newest message across any of
     * its channels — as a Flow so the home-list row updates live. Null
     * until it has any message. This is durable message history, not
     * transient presence: it needs no v11.4.0 peer and doesn't flicker,
     * unlike the typing signal [presenceIn] drives inside a channel.
     */
    fun groupLastActive(flag: String): Flow<Long?> =
        groupsLastActive.map { it[flag] }.distinctUntilChanged()

    /**
     * Every group's newest top-level post, from the one shared newest-per-
     * conversation read: each group row on the home list ran two queries
     * of its own, again on every write.
     */
    private val groupsLastActive: Flow<Map<String, Long>> by lazy {
        combine(
            db.latestPerConversation(),
            db.groups().streamChannelGroups(),
        ) { latest, links ->
            val groupOf = links.associate { it.nest to it.groupFlag }
            val out = HashMap<String, Long>()
            for (m in latest) {
                val g = groupOf[m.whom] ?: continue
                if (m.sentMs > (out[g] ?: Long.MIN_VALUE)) out[g] = m.sentMs
            }
            out as Map<String, Long>
        }.shareIn(scope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), replay = 1)
    }

    /** When we last announced a given (context, topic). */
    private val lastPresencePoke = ConcurrentMap<Pair<String, String>, Long>()

    /**
     * Announce that we're doing [topic] in [whom]. Safe to call on every
     * keystroke: the entry lives 30s server-side, so we re-poke at most
     * once per [Presence.REANNOUNCE_MS] and let it lapse on its own if
     * the user walks away.
     */
    suspend fun announcePresence(
        whom: String,
        topic: String = Presence.TOPIC_TYPING,
        text: String? = null,
    ) {
        val context = Presence.contextFor(whom) ?: return
        val key = context to topic
        val now = nowMs()
        val last = lastPresencePoke[key]
        if (last != null && now - last < Presence.REANNOUNCE_MS) return
        lastPresencePoke[key] = now
        val ch = channel ?: return
        // The next keystroke cancels this while it waits for its ack:
        // that is not an old ship, and was taken for one, switching the
        // typing line off for a minute while the user went on typing.
        io.nisfeb.talon.util.runSuspendCatching {
            ch.poke("presence", "presence-action-1", Presence.setAction(context, ourPatp, topic, text))
        }.onFailure {
            // Pre-v11.4.0 ship, or we're not a participant. Don't retry
            // on every keystroke.
            lastPresencePoke[key] = now + 60_000L
            Log.w(TAG, "presence set failed for $context/$topic", it)
        }
    }

    /**
     * Retract our entry — on send, when the composer empties, when an
     * upload finishes. A timeout does not propagate to watchers, so
     * without this the peer keeps seeing us for up to 30s.
     */
    suspend fun retractPresence(whom: String, topic: String = Presence.TOPIC_TYPING) {
        val context = Presence.contextFor(whom) ?: return
        if (lastPresencePoke.remove(context to topic) == null) return
        val ch = channel ?: return
        runCatching {
            ch.poke("presence", "presence-action-1", Presence.clearAction(context, ourPatp, topic))
        }.onFailure { Log.w(TAG, "presence clear failed for $context/$topic", it) }
    }

    /** Fire-and-forget [retractPresence] for callers that can't suspend —
     *  notably a composer being disposed as the screen goes away. */
    fun retractPresenceNow(whom: String, topic: String = Presence.TOPIC_TYPING) {
        scope.launch { retractPresence(whom, topic) }
    }

    /** Apply one `%presence-response-1` fact. */
    private fun applyPresence(payload: JsonObject) {
        val update = Presence.parseResponse(payload) ?: return
        val now = nowMs()
        val next = if (update.snapshot) mutableMapOf() else _presence.value.toMutableMap()

        update.gone.forEach { e ->
            val people = next[e.context]?.toMutableMap() ?: return@forEach
            people.remove(PresenceKey(e.ship, e.topic))
            if (people.isEmpty()) next.remove(e.context) else next[e.context] = people
        }
        update.here.forEach { e ->
            // Our own entry echoes back on contexts we host; the UI must
            // not tell the user that they are typing.
            if (e.ship == ourPatp) return@forEach
            val people = next[e.context]?.toMutableMap() ?: mutableMapOf()
            people[PresenceKey(e.ship, e.topic)] = Live(e.topic, e.label, now + e.timeoutMs)
            next[e.context] = people
        }
        _presence.value = next
        startPresenceReaper()
    }

    /**
     * The host emits `%gone` only for an explicit `%clear` — an entry
     * that simply times out is never retracted over the wire. So expire
     * locally, or a peer who closes their app types forever.
     */
    private fun startPresenceReaper() {
        if (presenceReaper?.isActive == true) return
        presenceReaper = scope.launch {
            while (isActive && _presence.value.isNotEmpty()) {
                delay(2_000L)
                val now = nowMs()
                val pruned = _presence.value
                    .mapValues { (_, people) -> people.filterValues { it.expiresAtMs > now } }
                    .filterValues { it.isNotEmpty() }
                if (pruned != _presence.value) _presence.value = pruned
            }
        }
    }

    // ───────── contacts ─────────

    /**
     * Load peer contact directory + self profile. Each entry's fields are
     * typed values like `{type: 'text', value: 'Alice'}`; we pluck just
     * nickname, bio and avatar for the UI.
     *
     * The directory scry moved in Tlon v11.4.0 (2026-07-02): `/v1/all`
     * was renamed `/v1/directory` with no back-compat arm, and the entry
     * shape changed from a flat field map to `{isContact, contact, mod}`
     * (tloncorp/tlon-apps@c2be4a06). Probe the new path first and fall
     * back — ships on v11.3.0 and earlier only answer the old one, and
     * both are live in the wild. A silent `runCatching`-to-null here is
     * what let the rename go unnoticed: every peer outside the contact
     * book just lost its nickname. scryFirstMatching logs on total
     * failure, so the next rename is loud.
     */
    internal suspend fun bootstrapContacts(channel: UrbitChannel) = coroutineScope {
        // The directory: every known peer, and our own card with them,
        // as Tlon's client reads it. /v1/self, which Tlon's client no
        // longer calls (removable under its N-1 policy, 12.3.0), is asked
        // only where a directory leaves us out.
        val allJob = async {
            scryFirstMatching(
                channel,
                "contacts",
                listOf("/v1/directory", "/v1/all"),
                "contacts directory",
                perCallSecs = DIRECTORY_SCRY_SECS,
                budgetMs = DIRECTORY_BUDGET_MS,
            )
        }
        // /v1/book is our curated contact book (kip -> [con, mod] page),
        // a subset of the directory. Drives `bookContacts` + Contacts screen.
        val bookJob = async { runCatching { channel.scry("contacts", "/v1/book") }.getOrNull() }
        val allBody = allJob.await()
        val selfBody = if ((allBody as? JsonObject)?.get(ourPatp) != null) null
        else runCatching { channel.scry("contacts", "/v1/self") }.getOrNull()
        val bookBody = bookJob.await()

        // One row per ship: its published profile from the directory (ours
        // too, or from /v1/self where it lacks us), with its book page laid over it. Two rows for one
        // ship, written in turn, let an empty book row stand over the
        // directory's, which is why the merge kept old values at all.
        val fields = LinkedHashMap<String, JsonObject>()
        val modAt = mutableMapOf<String, Long?>()
        (allBody as? JsonObject)?.forEach { (ship, entry) ->
            val obj = entry as? JsonObject ?: return@forEach
            fields[ship] = directoryFields(obj)
            modAt[ship] = parseContactModAt(obj)
        }
        (selfBody as? JsonObject)?.let { obj ->
            fields[ourPatp] = directoryFields(obj)
            modAt[ourPatp] = parseContactModAt(obj)
        }

        // Book: keys are `~ship` (or `0v<cid>` for id-pages, which we
        // skip — Talon only books ships). Each value is the [con, mod]
        // page; we display mod-over-con so a local pet-name wins.
        val book = mutableSetOf<String>()
        // Book members we have no profile of at all: their row says
        // nothing of one, so what is stored stays.
        val unknown = mutableSetOf<String>()
        (bookBody as? JsonObject)?.forEach { (kip, page) ->
            if (!kip.startsWith("~")) return@forEach
            book.add(kip)
            val overlay = pageFields(page)
            when {
                overlay != null -> fields[kip] = JsonObject(fields[kip].orEmpty() + overlay)
                // Always seed a row (even for an empty page) so every book
                // member is renderable in the Contacts screen, which filters
                // the contacts table by the book set.
                kip !in fields -> { fields[kip] = JsonObject(emptyMap()); unknown += kip }
            }
        }

        if (fields.isNotEmpty()) {
            // Read once and write only what changed: a ship that knows a
            // few thousand peers asked a row of the database for each, and
            // rewrote every one, on every reconcile.
            val stored = db.contacts().all().associateBy { it.ship }
            val changed = fields.map { (ship, f) -> mergeContact(parseContact(ship, f, modAt[ship]), stored[ship], full = ship !in unknown) }
                .filter { it != stored[it.ship] }
            if (changed.isNotEmpty()) db.contacts().upsertAll(changed)
        }
        // No answer is not an empty book: read as one, every contact
        // left the Contacts screen until the next connect.
        if (bookBody is JsonObject) _bookContacts.value = book
    }

    /**
     * A %contacts book `page` serializes as a 2-element JSON array
     * `[con, mod]` (see lib/contacts/json-1.hoon ++page). Merge the
     * peer's published `con` with our local `mod` overlay (mod wins)
     * into the flat field map parseContact expects.
     */
    private fun pageFields(page: JsonElement?): JsonObject? {
        val arr = page as? JsonArray ?: return null
        val con = arr.getOrNull(0) as? JsonObject ?: JsonObject(emptyMap())
        val mod = arr.getOrNull(1) as? JsonObject ?: JsonObject(emptyMap())
        if (con.isEmpty() && mod.isEmpty()) return null
        return JsonObject(con + mod)
    }

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
            // A `page` fact means this kip is in our book — keep the
            // book set in sync so the Contacts screen reflects adds /
            // edits made from other clients.
            _bookContacts.value = _bookContacts.value + kip
            // Prefer the server-provided mod-at. Fall back to our own
            // observation time — we know the status just changed since
            // this fact is the change event itself.
            val modAt = parseContactModAt(page) ?: nowMs()
            // The peer's profile with our own overlay on it, mod winning,
            // as the bootstrap reads a book page: the overlay was dropped
            // here, so a pet name set in another client waited for a restart.
            val mod = page["mod"] as? JsonObject
            db.contacts().upsert(mergeContact(parseContact(kip, JsonObject(contact + mod.orEmpty()), modAt), full = true))
            return
        }
        (event["wipe"] as? JsonObject)?.let { wipe ->
            // Contact page deleted (here or on another client) — drop it
            // from the book set. The contacts table row stays (the peer
            // may still be in /v1/all); only book membership changes.
            val kip = wipe["kip"].asStr() ?: return
            _bookContacts.value = _bookContacts.value - kip
            return
        }
        (event["peer"] as? JsonObject)?.let { peer ->
            val who = peer["who"].asStr() ?: return
            if (!who.startsWith("~")) return
            val contact = peer["contact"] as? JsonObject ?: return
            val modAt = parseContactModAt(peer) ?: nowMs()
            db.contacts().upsert(mergeContact(parseContact(who, contact, modAt), full = true))
            return
        }
        // Our own profile's change, `{"self":{"contact":{…}}}`: applied
        // as it comes, unwrapped as a directory entry is, so the two
        // cannot drift. It was read back from /v1/self, a path Tlon's
        // client no longer calls.
        (event["self"] as? JsonObject)?.let { applySelf(it); return }
    }

    /**
     * Re-read this ship's own profile.
     *
     * Editing it in another client of the same ship left Talon showing
     * the old name until a sign-out and back in: the change arrives on
     * the contacts feed in an envelope of its own, and nothing here
     * listened for it. A re-read is one scry and is what the bootstrap
     * does, so the two cannot disagree.
     */
    suspend fun refreshSelf() {
        val ch = channel ?: return
        // /v1/self: our own card alone. The directory, which Tlon's client
        // reads it from, is every contact we know, read whole after each
        // profile save; only a ship without /v1/self is asked for it.
        val entry = runCatching { ch.scry("contacts", "/v1/self") }
            .getOrElse { t -> if (notServed(t)) null else return }
            as? JsonObject
            ?: (runCatching { ch.scry("contacts", "/v1/directory") }.getOrNull() as? JsonObject)?.get(ourPatp) as? JsonObject
            ?: return
        applySelf(entry)
    }

    /** Our own card, from a directory entry, a self fact or /v1/self alike. */
    private suspend fun applySelf(entry: JsonObject) {
        db.contacts().upsert(mergeContact(parseContact(ourPatp, directoryFields(entry), parseContactModAt(entry)), full = true))
    }

    /**
     * Merge an incoming contact record with what we already have.
     *
     * A [full] record is a whole published profile (the directory, our
     * own, a peer's or a book page's update): %contacts keeps a profile
     * as a map and deletes a field by dropping it, so a nickname, bio or
     * avatar it lacks was removed, and goes here too. Keeping the old one
     * left a removed nickname on everybody else's screen for good. Only
     * a record that says nothing of the profile (a book entry for a ship
     * we have no profile of) keeps what we have.
     *
     * Resolves `statusUpdatedMs` against the existing row so
     * a re-bootstrap on app upgrade can't blow away timestamps we
     * already trust.
     *
     * Resolution order, highest priority first:
     *  1. Status was cleared → null.
     *  2. Server gave us a fresh `mod-at` on this update → use it.
     *  3. We already have a stored timestamp for this ship → keep it
     *     (this is the upgrade-install path: don't relabel everyone
     *     to "now" just because the ship's `/v1/all` didn't echo a
     *     `mod-at` field).
     *  4. First time we've seen this contact have a status, with
     *     nothing on the wire to anchor it → stamp now so the feed
     *     can sort recent updates above silent old entries.
     */
    internal suspend fun mergeContact(incoming: ContactEntity, full: Boolean = false): ContactEntity =
        mergeContact(incoming, db.contacts().get(incoming.ship), full)

    /** [mergeContact] over a row already read. */
    private fun mergeContact(incoming: ContactEntity, existing: ContactEntity?, full: Boolean): ContactEntity =
        incoming.copy(
            nickname = if (full) incoming.nickname else incoming.nickname ?: existing?.nickname,
            bio = if (full) incoming.bio else incoming.bio ?: existing?.bio,
            avatarUrl = if (full) incoming.avatarUrl else incoming.avatarUrl ?: existing?.avatarUrl,
            statusUpdatedMs = when {
                incoming.status.isNullOrBlank() -> null
                incoming.statusUpdatedMs != null -> incoming.statusUpdatedMs
                existing?.statusUpdatedMs != null -> existing.statusUpdatedMs
                else -> nowMs()
            },
        )

    /**
     * Visible to tests so the regression guard around the
     * "every reinstall stamps statuses to now" bug can drive the
     * full bootstrap-shape parse path. Production callers stay
     * inside TlonChatRepo via applyContactsNews + bootstrapContacts.
     */
    internal fun parseContact(
        ship: String,
        fields: JsonObject,
        modAtMs: Long? = null,
    ): ContactEntity {
        // Every contact path comes through here, so a bot's liveness does too.
        BotLiveness.record(ship, fields)
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
        val da = runCatching { BigInteger.parseString(digits) }.getOrNull()
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
        var matched = 0
        var missing = 0
        for ((nest, ch) in obj) {
            if (!nest.startsWith("chat/")) continue
            val chObj = ch as? JsonObject ?: continue
            val order = chObj["order"] as? kotlinx.serialization.json.JsonArray
            val pinned = order?.firstOrNull().asStr()?.replace(".", "")
            ensureChannelGroupRow(nest)
            val affected = db.groups().setPinnedPostId(nest, pinned)
            if (affected == 0) missing++ else matched++
        }
        Log.i(TAG, "bootstrapChannelOrders: matched=$matched missing-row=$missing")
    }

    /**
     * Load group metadata + channel→group mapping. %groups /v2/groups
     * returns Record<flag, {meta?, channels?: Record<nest, {meta?}>}>.
     * We store title + image for each group and a nest→flag index so
     * list rows can pluck the enclosing group's image in O(1).
     */
    /** Reconcile the group list from the ship now. For callers that
     *  just asked the ship to join a group and want to see it land. */
    suspend fun refreshGroups() {
        val ch = channel ?: return
        bootstrapGroups(ch)
    }

    private suspend fun bootstrapGroups(channel: UrbitChannel) {
        val body = scryNewest(channel, "groups", "/v3/groups", "/v2/groups")
        val obj = body as? JsonObject ?: return
        // Pure parse extracted to GroupsScryParser.kt for unit-test
        // coverage of the wire-shape contract — including the
        // iteration-order-as-ordinal invariant the home-list "host
        // order" sort relies on.
        val parsed = parseGroupsScry(obj)

        // Reconcile: drop local rows the ship no longer reports.
        // Without this, groups the user left / hosts deleted while
        // Talon was offline linger in the home list forever.
        val plan = planGroupReconcile(
            existingGroups = db.groups().allGroups(),
            existingChannels = db.groups().allChannelGroups(),
            liveGroupFlags = obj.keys,
            liveChannelNests = parsed.channelGroups.map { it.nest }.toSet(),
        )
        for (flag in plan.deletedGroupFlags) {
            db.groups().deleteChannelsForGroup(flag)
            db.groups().deleteGroup(flag)
        }
        for (nest in plan.deletedChannelNests) {
            db.groups().deleteChannelGroup(nest)
        }

        if (parsed.groups.isNotEmpty()) db.groups().upsertGroups(parsed.groups)
        if (parsed.channelGroups.isNotEmpty()) {
            db.groups().upsertChannelGroupsKeepingPin(parsed.channelGroups)
        }
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
        // Covers the essay and every reply, which toEntity does not see.
        rememberBots(botAuthorsIn(post))
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
            db.messages().softDeleteWithMedia(db.messageMedia(), whom, id)
            db.reactions().clearForPost(whom, id)
        }
    }


    /**
     * Give a bot a contact row so it renders like anyone else.
     *
     * A bot is not in %contacts and never will be — it carries its
     * nickname and avatar inline on every post instead. ContactEntity
     * already has exactly those fields, and ContactMap already reads
     * nickname for the label and avatarUrl for the icon, so writing
     * one row lights up the whole display stack with no schema change
     * and no special case at any call site.
     *
     * Merged, not overwritten. A bot supplies only the two fields it
     * knows about; everything %contacts owns — bio, status, colour —
     * is carried through, and a field the bot left empty never blanks
     * an existing one. The equality check matters more than it looks:
     * this runs on every ingested post, and without it a busy bot
     * channel would rewrite the same row hundreds of times and wake
     * every contacts observer each time.
     */
    private suspend fun rememberBots(authors: List<PostAuthor>) {
        for (a in authors) {
            if (!a.isBot) continue
            val existing = runCatching { db.contacts().get(a.ship) }.getOrNull()
            val merged = ContactEntity(
                ship = a.ship,
                nickname = a.nickname ?: existing?.nickname,
                bio = existing?.bio,
                avatarUrl = a.avatarUrl ?: existing?.avatarUrl,
                status = existing?.status,
                statusUpdatedMs = existing?.statusUpdatedMs,
                color = existing?.color,
            )
            if (merged != existing) {
                runCatching { db.contacts().upsert(merged) }
                    .onFailure { Log.w(TAG, "could not record bot ${a.ship}", it) }
            }
        }
    }

    private suspend fun toEntity(whom: String, id: String, essay: JsonObject): MessageEntity {
        // Thin wrapper so the two applyChannelDelta ingestion branches
        // (full post set vs full post ingest) produce identical rows.
        rememberBots(listOfNotNull(essay["author"].asAuthor()?.takeIf { it.isBot }))
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
        memo: String? = null,
        perCallSecs: Long = SCRY_PROBE_PER_CALL_SECS,
        budgetMs: Long = SCRY_PROBE_BUDGET_MS,
    ): JsonElement? {
        val deadline = nowMs() + budgetMs
        var lastErr: Throwable? = null
        // The version and mark that answered last time first: a ship that
        // stopped serving the first ones was walked through them per read.
        val served = memo?.let { walkServed[it] }
        val order = if (served == null) paths else paths.sortedBy { p ->
            (if (p.split('/').getOrNull(1) == served.first) 0 else 2) + (if (p.substringAfterLast('/') == served.second) 0 else 1)
        }
        for (path in order) {
            if (nowMs() >= deadline) {
                Log.w(TAG, "$label: ${budgetMs}ms budget exhausted, giving up; last err: ${lastErr?.message}")
                return null
            }
            val attempt = io.nisfeb.talon.util.runSuspendCatching { ch.scry(app, path, perCallSecs) }
            if (attempt.isSuccess) {
                memo?.let { walkServed[it] = path.split('/')[1] to path.substringAfterLast('/') }
                return attempt.getOrNull()
            }
            val err = attempt.exceptionOrNull()
            lastErr = err
            if (isTransientNetworkError(err)) {
                Log.w(TAG, "$label: network timeout, giving up; err: ${err?.message}")
                return null
            }
        }
        Log.w(TAG, "$label: all ${paths.size} probes failed; last err: ${lastErr?.message}")
        return null
    }

    companion object {
        /** At most one read this often for the chat or thread in view. */
        const val FOCUSED_READ_EVERY_MS = 3_000L

        /** A stream with bytes this recently is live: eyre sends at least every ~20 s. */
        const val STREAM_FRESH_MS = 30_000L

        /** Posts read as a conversation opens. */
        const val OPEN_READ_COUNT = 50

        /** The first wait before a queued write is tried again; it doubles to [MAX_DRAIN_PAUSE_MS]. */
        private const val FIRST_DRAIN_PAUSE_MS = 2_000L
        private const val MAX_DRAIN_PAUSE_MS = 60_000L
        /** How many of a channel's newest posts are read to see whether a queued one landed. */
        private const val LANDED_CHECK_COUNT = 30

        /** How long a message menu waits to ask for the admin groups again after a read failed. */
        private const val ADMIN_RETRY_MS = 60_000L

        /**
         * `#FF5050` → `ff.5050` for a profile tint.
         *
         * The json-1 mark decodes a tint as `(slav %ux (cat 3 '0x' s))`
         * -- it prepends the `0x` itself, so the value must not carry
         * one. And slav wants the canonical @ux rather than merely a
         * parseable one: four-digit groups counted from the right, and
         * no leading zero on the group at the front.
         *
         * Padding every colour to six digits and cutting it 2+4 met
         * that for bright colours and missed it for any colour whose
         * red channel was below 0x10: `0xff.5050` parses, `0x0a.1b2c`
         * is refused. The mark's grab then crashed and the whole
         * profile save nacked with `gall: poke-as: cast: key=%self`,
         * which says nothing whatsoever about colours.
         *
         * Both forms checked against slav in a dojo.
         */
        internal fun urbitHexColor(hex: String): String {
            val digits = hex.trim().removePrefix("#").lowercase()
                .padStart(6, '0').takeLast(6)
                .trimStart('0')
            if (digits.isEmpty()) return "0"
            val head = digits.length % 4
            val groups = buildList {
                if (head != 0) add(digits.substring(0, head))
                for (i in head until digits.length step 4) add(digits.substring(i, i + 4))
            }
            return groups.joinToString(".")
        }

        private const val TAG = "TlonChatRepo"

        /**
         * Pure parser for the `%activity /v5/feed/init/30` scry
         * response. Returns the parsed feed items in newest-first
         * order. `internal` so the test source set can drive
         * wire-shape variants (null body, missing `all`, malformed
         * bundles) without spinning up a fake UrbitChannel.
         *
         * Robustness contract:
         *   - null / non-JsonObject body → empty list
         *   - missing or non-Array `all` → empty list
         *   - any individual bundle / event with the wrong shape is
         *     skipped (not an error); the rest of the feed renders
         */
        /**
         * `feed/init` answers with all three views in one round-trip:
         * `{all, mentions, replies, summaries}`. Split them so the UI
         * can show the tabs Tlon's client does, rather than folding
         * everything into one undifferentiated list.
         */
        internal fun parseActivityFeed(body: JsonObject?): ActivityFeed = ActivityFeed(
            all = parseActivityFeedBody(body, "all"),
            mentions = parseActivityFeedBody(body, "mentions"),
            replies = parseActivityFeedBody(body, "replies"),
        )

        internal fun parseActivityFeedBody(
            body: JsonObject?,
            key: String = "all",
        ): List<ActivityFeedItem> {
            if (body == null) return emptyList()
            val all = body[key] as? JsonArray ?: return emptyList()
            val items = mutableListOf<ActivityFeedItem>()
            for (bundleEl in all) {
                val bundle = bundleEl as? JsonObject ?: continue
                val sourceKey = bundle["source-key"].asStr()
                // sourceKeyToWhom returns null for thread / dm-thread
                // keys now (those are routed to ThreadUnreadEntity);
                // for the Activity feed's title we still want the
                // conversation name, so fall back to the parent whom
                // via sourceKeyToThreadSource.
                val sourceWhom = sourceKey?.let { key ->
                    sourceKeyToWhom(key) ?: sourceKeyToThreadSource(key)?.whom
                }
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
                        // %react / %dm-react reach us only on the v5+
                        // wire; the v4 down-conversion drops them.
                        "react", "dm-react" ->
                            eventObj["react"].asStr()
                                ?.let { "Reacted ${ReactionPalette.display(it)}" }
                                ?: "Reacted"
                        "flag-post", "flag-reply" -> "Reported a post"
                        "group-kick" -> "Removed from group"
                        "group-join" -> "Joined the group"
                        "group-role" -> "Role changed"
                        // Unknown wire tags: humanize rather than leaking
                        // a raw hyphenated key into the row header.
                        else -> tag.replace('-', ' ')
                            .replaceFirstChar { it.uppercase() }
                    }
                    val author = eventObj["mention-author"].asStr()
                        ?: eventObj["author"].asStr()
                        ?: (eventObj["key"] as? JsonObject)?.get("id").asStr()
                            ?.substringBefore('/')
                    val content = eventObj["content"]?.let { it.toString() }
                    val timeStr = wrap["time"].asStr() ?: ""
                    val sentMs = parseActivityEventTimeMs(timeStr, eventObj)
                    val target = parseActivityEventTarget(tag, eventObj)
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

        /** Pure event-time parser hoisted out of the instance method
         *  so the companion's [parseActivityFeedBody] can call it. */
        internal fun parseActivityEventTimeMs(timeStr: String, eventObj: JsonObject): Long {
            val keyTime = (eventObj["key"] as? JsonObject)?.get("time").asStr()
            val raw = keyTime ?: timeStr
            return runCatching {
                val digits = raw.replace(".", "")
                if (digits.all { it.isDigit() }) digits.toLong() else 0L
            }.getOrDefault(0L)
        }

        // 5 minutes — refresh on screen entry if older, instant paint
        // from cache within the window.
        private const val ADMIN_CACHE_TTL_MS = 5L * 60_000L
        // Heavy first-run scries (init-posts, full activity bootstrap)
        // can stream for well over UrbitChannel.RPC_TIMEOUT_SECS (30s)
        // on busy ships. Use a 3-minute budget for those calls only —
        // ordinary RPCs keep the tighter default so a hung poke
        // doesn't spin for that long.
        private const val BOOTSTRAP_TIMEOUT_SECS = 180L
        // Two-stage bootstrap (see runSessionOnce). Fast pass runs in
        // the foreground and unblocks first paint; deep pass fills
        // older history in the background. 10 + 50 mirrors what Tlon's
        // own UI does on web — 10 is enough to render every chat's
        // most-recent state, 50 covers the typical scrollback depth
        // a user would expect "to be there already" on open.
        private const val INITIAL_PAGE_COUNT = 10
        private const val DEEP_PAGE_COUNT = 50
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
        // The contacts directory is every peer the ship knows, a few
        // thousand on a busy ship. The ship builds it in half a second,
        // but its JSON took longer than 6 s to arrive on every read for
        // two days (~ricsul, ~3,400 peers, 2026-10-05), so no one outside
        // the contact book ever got an avatar. Tlon's client waits 60 s.
        private const val DIRECTORY_SCRY_SECS = 60L
        private const val DIRECTORY_BUDGET_MS = 90_000L
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

/**
 * Flatten one entry of a %contacts directory scry into the flat field
 * map `parseContact` expects. Three shapes are live in the wild:
 *
 *  - v11.4.0 `/v1/directory`: `{isContact, contact: {…}, mod: {…}}`,
 *    where `mod` is our local overlay — a pet-name we set for them.
 *  - older `/v1/all` variants: `{contact: {…}, mod-at: "…"}`.
 *  - oldest `/v1/all`: the flat field map itself.
 *
 * `mod` wins over `contact`, the same precedence `pageFields` gives the
 * book's `[con, mod]` page.
 */
internal fun directoryFields(entry: JsonObject): JsonObject {
    val con = entry["contact"] as? JsonObject ?: return entry
    val mod = entry["mod"] as? JsonObject ?: return con
    return JsonObject(con + mod)
}

/**
 * A `%groups /v1/foreigns` (foreigns-1) or `/gangs/updates` fact is the
 * only diff shaped as a bare `flag → object` map. Every other agent's fact carries literal keys
 * (`whom`, `nest`, `flag`, `put-entry`, `init`, `here`…), whereas a
 * gang fact's keys are the group flags themselves (`~ship/name`). So
 * "every key is flag-shaped" identifies it without colliding with any
 * other event the SSE stream delivers.
 */
internal fun looksLikeGangsFact(payload: JsonObject): Boolean =
    payload.isNotEmpty() && payload.keys.all { it.startsWith("~") && '/' in it }

/**
 * A one-verse story carrying a single image block — the structured form
 * both top-level posts (repo.sendImage) and thread replies
 * (repo.replyImage) use, so the image renders inline instead of as the
 * `[alt](url)` markdown link the old thread fallback produced. [caption],
 * text written with the image, follows it in the same message.
 */
internal fun imageStory(src: String, width: Int, height: Int, alt: String, caption: String = ""): JsonArray =
    buildJsonArray {
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
    }.let { if (caption.isBlank()) it else JsonArray(it + chatTextToStory(caption.trim())) }

/**
 * Which of [ships] the ship now counts as DMs: `%chat /dm` lists the
 * accepted ones (net inviting or done), so a request that left the
 * pending list and is named here was accepted, and one that is not was
 * declined.
 */
internal fun acceptedAmong(ships: Set<String>, dmScry: JsonElement?): Set<String> {
    val accepted = (dmScry as? JsonArray).orEmpty()
        .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
    return ships.intersect(accepted.toSet())
}

/**
 * The ship answered that it has no such path: eyre's 404, or the 500 a
 * scry crash gives (a scry path an agent does not serve). Not a timeout,
 * a dropped connection, or a proxy's 502 for a ship that is down: those
 * say nothing of the path.
 */
internal fun notServed(t: Throwable): Boolean =
    generateSequence(t) { it.cause }.take(4).any { e -> NOT_SERVED.containsMatchIn(e.message.orEmpty()) }

private val NOT_SERVED = Regex("HTTP (404|500)\\b")

/** Newest first: Tlon's client sends the first; an older ship takes only the next. */
private val GROUP_ACTION_MARKS = listOf("group-action-5", "group-action-4")
private val ACTIVITY_MARKS = listOf("activity-action-2", "activity-action")

/** Drop [key] if it still holds [v]: a newer entry put there meanwhile stays. */
private fun <V> kotlinx.coroutines.flow.MutableStateFlow<Map<String, V>>.dropIfSame(key: String, v: V) =
    update { m -> if (m[key] == v) m - key else m }

