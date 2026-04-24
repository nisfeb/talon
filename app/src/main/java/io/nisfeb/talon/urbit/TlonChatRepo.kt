@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

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
class TlonChatRepo(
    private val db: AppDatabase,
    private val aiSettings: io.nisfeb.talon.ai.AiSettings,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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

    /**
     * Mirrors UI-organizational state (pins, group order, folders,
     * notify prefs) to the user's %settings agent so it survives app
     * reinstall and syncs across devices. Exposed so UI layers can
     * call the sync-aware mutations instead of the raw DAOs.
     */
    val settingsSync: SettingsSync = SettingsSync(db, aiSettings)

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

    /**
     * One admin-visible group: metadata, fleet (members + sects), and
     * cordon shape so we know which invite/ban path to use.
     */
    data class AdminGroup(
        val flag: String,
        val title: String?,
        val description: String?,
        val image: String?,
        val cover: String?,
        val members: List<AdminMember>,
        /** Derived privacy — "open" (public) or "shut" (private/secret).
         *  Kept for back-compat with legacy invite/ban pokes. */
        val cordonKind: String,
        /** Raw privacy string from the new `admissions.privacy` field
         *  when available: "public" | "private" | "secret" | etc. */
        val privacy: String?,
        /** Banned ships (under `admissions.banned.ships` in the new agent). */
        val bannedShips: Set<String>,
        /** Ships invited via token — revoke by deleting the token. */
        val invitedTokenByShip: Map<String, String>,
        /** Ships with a direct (no-token) invite — revoke via `entry.pending.del`. */
        val directInvitedShips: Set<String>,
        /** Ships that have asked to join. */
        val pendingShips: Set<String>,
        /** Sect names that confer admin powers — from `bloc`/`admins`. */
        val adminSects: Set<String>,
    )

    data class AdminMember(
        val ship: String,
        val sects: Set<String>,
        val isAdmin: Boolean,
    )

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
        Log.d(TAG, "fetchAdminGroups: ${body.size} total groups, me=$me")
        // The `/v2/groups` scry can return a lightweight listing where
        // `fleet`/`bloc` are absent. Re-scry each flag individually via
        // `/v2/groups/<flag>` to get the full group state every time.
        val out = mutableListOf<AdminGroup>()
        for ((flag, _) in body) {
            val full = runCatching {
                ch.scry("groups", "/v2/groups/$flag") as? JsonObject
            }.getOrNull()
            if (full == null) {
                Log.w(TAG, "  $flag: full scry returned null, skipping")
                continue
            }
            if (flag == body.keys.first()) {
                Log.d(TAG, "  sample keys for $flag: ${full.keys}")
                Log.d(TAG, "  sample admins for $flag: ${full["admins"]}")
                val seats = full["seats"] as? JsonObject
                val firstSeat = seats?.entries?.firstOrNull()
                Log.d(TAG, "  sample seat for $flag: " +
                    "${firstSeat?.key} -> ${firstSeat?.value?.toString()?.take(500)}")
                Log.d(TAG, "  total seats: ${seats?.size}")
            }
            val g = parseAdminGroup(flag, full)
            val host = flag.substringBefore('/')
            val isHost = host == me
            val memberSects = g.members.firstOrNull { it.ship == me }?.sects.orEmpty()
            val amAdmin = isHost || g.adminSects.any { it in memberSects } ||
                "admin" in memberSects
            Log.d(TAG, "  $flag host=$host members=${g.members.size} " +
                "admin=$amAdmin sects=$memberSects bloc=${g.adminSects}")
            if (amAdmin) out += g
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

    private fun parseAdminGroup(flag: String, obj: JsonObject): AdminGroup {
        val meta = obj["meta"] as? JsonObject
        fun metaStr(key: String) = meta?.get(key)?.jsonPrimitive?.contentIfString()
            ?.takeIf { it.isNotBlank() }

        val host = flag.substringBefore('/')
        // Schema renamed: `bloc` → `admins`, `fleet` → `seats`,
        // `cordon` → `admissions`, `sects` → `roles`. Keep fallbacks
        // to the older names for ships still on the legacy agent.
        val adminArr = (obj["admins"] ?: obj["bloc"]) as? JsonArray
        val adminSects = adminArr?.mapNotNull { it.jsonPrimitive.contentIfString() }
            ?.toSet() ?: emptySet()

        val members = (obj["seats"] ?: obj["fleet"]) as? JsonObject
        val mems = members?.mapNotNull { (ship, entry) ->
            val entryObj = entry as? JsonObject ?: return@mapNotNull null
            val rolesArr = (entryObj["roles"] ?: entryObj["sects"]) as? JsonArray
                ?: JsonArray(emptyList())
            val sects = rolesArr.mapNotNull {
                it.jsonPrimitive.contentIfString()
            }.toSet()
            AdminMember(
                ship = ship,
                sects = sects,
                isAdmin = ship == host || "admin" in sects ||
                    adminSects.any { it in sects },
            )
        }?.sortedBy { it.ship } ?: emptyList()

        val admissions = (obj["admissions"] ?: obj["cordon"]) as? JsonObject
        // New flat admissions schema has: banned, invited, pending,
        // privacy, tokens, requests — no tagged union. Privacy drives
        // whether we treat the group as open (public) or shut
        // (private/secret) for back-compat with legacy pokes.
        val privacy = admissions?.get("privacy")?.jsonPrimitive?.contentIfString()
        val cordonKind = when {
            // New schema path — derive from privacy.
            privacy == "public" -> "open"
            privacy != null -> "shut"
            // Old tagged-union fallback for legacy agent:
            admissions?.get("open") is JsonObject -> "open"
            admissions?.get("shut") is JsonObject -> "shut"
            else -> "shut"
        }
        val bannedSet = ((admissions?.get("banned") as? JsonObject)
            ?.get("ships") as? JsonArray)
            ?: ((admissions?.get("open") as? JsonObject)?.get("ships") as? JsonArray)
        val bannedShips = bannedSet?.mapNotNull { it.jsonPrimitive.contentIfString() }
            ?.toSet() ?: emptySet()
        val seatKeys = (obj["seats"] as? JsonObject)?.keys ?: emptySet()
        // `admissions.invited` = ships invited via a token (shareable
        // link). Each value is `{token, at}`. Revoke by deleting the
        // token via `entry.token.del: <tokenstr>`.
        val invitedMap = (admissions?.get("invited") as? JsonObject)
            ?.mapNotNull { (ship, v) ->
                if (ship in seatKeys) return@mapNotNull null
                val token = (v as? JsonObject)?.get("token")
                    ?.jsonPrimitive?.contentIfString() ?: return@mapNotNull null
                ship to token
            }?.toMap() ?: emptyMap()
        // `admissions.pending` = direct invites (no token). Revoke
        // via `entry.pending.a-pending.del` with ships list.
        val directInvitedShips = (admissions?.get("pending") as? JsonObject)
            ?.keys?.filterNot { it in seatKeys }?.toSet() ?: emptySet()
        // `admissions.requests` = inbound join requests. Approve/deny
        // via `entry.ask.a-ask = "approve"|"deny"`.
        val pendingShips = (admissions?.get("requests") as? JsonObject)
            ?.keys?.toSet() ?: emptySet()

        return AdminGroup(
            flag = flag,
            title = metaStr("title"),
            description = metaStr("description"),
            image = metaStr("image"),
            cover = metaStr("cover"),
            members = mems,
            cordonKind = cordonKind,
            privacy = privacy,
            bannedShips = bannedShips,
            invitedTokenByShip = invitedMap,
            directInvitedShips = directInvitedShips,
            pendingShips = pendingShips,
            adminSects = adminSects,
        )
    }

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

    /** Update a group's title/description/image/cover via %meta poke. */
    suspend fun updateGroupMeta(
        flag: String,
        title: String,
        description: String,
        image: String,
        cover: String,
    ) {
        pokeAGroup(flag, buildJsonObject {
            put("meta", buildJsonObject {
                put("title", title)
                put("description", description)
                put("image", image)
                put("cover", cover)
            })
        })
    }

    /**
     * Invite one or more ships to a group. Works for all privacy
     * levels — the ship gets an invite token in `admissions.invited`.
     */
    suspend fun inviteToGroup(flag: String, ship: String): Boolean {
        val ch = channel ?: error("not connected")
        val p = if (!ship.startsWith("~")) "~$ship" else ship
        ch.poke(
            app = "groups",
            mark = "group-action-4",
            payload = buildJsonObject {
                put("invite", buildJsonObject {
                    put("flag", flag)
                    put("ships", buildJsonArray { add(JsonPrimitive(p)) })
                    put("a-invite", buildJsonObject {
                        put("token", JsonNull)
                        put("note", JsonNull)
                    })
                })
            },
        )
        return true
    }

    /** Remove a ship from the group (kick). */
    suspend fun kickFromGroup(flag: String, ship: String) {
        val p = if (!ship.startsWith("~")) "~$ship" else ship
        pokeAGroup(flag, buildJsonObject {
            put("seat", buildJsonObject {
                put("ships", buildJsonArray { add(JsonPrimitive(p)) })
                put("a-seat", buildJsonObject { put("del", JsonNull) })
            })
        })
    }

    /** Ban a ship from re-joining. */
    suspend fun banFromGroup(flag: String, ship: String): Boolean {
        val p = if (!ship.startsWith("~")) "~$ship" else ship
        pokeAGroup(flag, buildJsonObject {
            put("entry", buildJsonObject {
                put("ban", buildJsonObject {
                    put("add-ships", buildJsonArray { add(JsonPrimitive(p)) })
                })
            })
        })
        return true
    }

    /** Remove a ship from the ban list. */
    suspend fun unbanFromGroup(flag: String, ship: String, cordonKind: String): Boolean {
        val p = if (!ship.startsWith("~")) "~$ship" else ship
        pokeAGroup(flag, buildJsonObject {
            put("entry", buildJsonObject {
                put("ban", buildJsonObject {
                    put("del-ships", buildJsonArray { add(JsonPrimitive(p)) })
                })
            })
        })
        return true
    }

    /**
     * Toggle a role on a single member via `seat.a-seat.{add,del}-roles`.
     */
    suspend fun setMemberRole(flag: String, ship: String, role: String, add: Boolean) {
        val p = if (!ship.startsWith("~")) "~$ship" else ship
        val key = if (add) "add-roles" else "del-roles"
        pokeAGroup(flag, buildJsonObject {
            put("seat", buildJsonObject {
                put("ships", buildJsonArray { add(JsonPrimitive(p)) })
                put("a-seat", buildJsonObject {
                    put(key, buildJsonArray { add(JsonPrimitive(role)) })
                })
            })
        })
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
        Log.d(TAG, "refreshInvites: ${foreigns.size} foreigns")
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
            val inviter = firstValid["ship"]?.jsonPrimitive?.contentIfString()
            val preview = f["preview"] as? JsonObject
            val meta = preview?.get("meta") as? JsonObject
            fun metaStr(k: String) = meta?.get(k)?.jsonPrimitive?.contentIfString()
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
     * Revoke a token-based invite by deleting the token from
     * `admissions.tokens` — ships in `admissions.invited` all have
     * a token; the map maps ship → token.
     */
    suspend fun revokeTokenInvite(flag: String, token: String) {
        pokeAGroup(flag, buildJsonObject {
            put("entry", buildJsonObject {
                put("token", buildJsonObject {
                    put("del", token)
                })
            })
        })
    }

    /**
     * Revoke a direct (no-token) invite: drop the ship from
     * `admissions.pending` via `entry.pending.a-pending.del`.
     */
    suspend fun revokeDirectInvite(flag: String, ship: String) {
        val p = if (!ship.startsWith("~")) "~$ship" else ship
        pokeAGroup(flag, buildJsonObject {
            put("entry", buildJsonObject {
                put("pending", buildJsonObject {
                    put("ships", buildJsonArray { add(JsonPrimitive(p)) })
                    put("a-pending", buildJsonObject { put("del", JsonNull) })
                })
            })
        })
    }

    /** Accept a join request: `ask` → `approve`. */
    suspend fun approveRequest(flag: String, ship: String) {
        val p = if (!ship.startsWith("~")) "~$ship" else ship
        pokeAGroup(flag, buildJsonObject {
            put("entry", buildJsonObject {
                put("ask", buildJsonObject {
                    put("ships", buildJsonArray { add(JsonPrimitive(p)) })
                    put("a-ask", "approve")
                })
            })
        })
    }

    /** Deny a join request: `ask` → `deny`. */
    suspend fun denyRequest(flag: String, ship: String) {
        val p = if (!ship.startsWith("~")) "~$ship" else ship
        pokeAGroup(flag, buildJsonObject {
            put("entry", buildJsonObject {
                put("ask", buildJsonObject {
                    put("ships", buildJsonArray { add(JsonPrimitive(p)) })
                    put("a-ask", "deny")
                })
            })
        })
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
            payload = buildJsonObject {
                put("group", buildJsonObject {
                    put("flag", flag)
                    put("a-group", aGroup)
                })
            },
        )
    }

    private fun wrapGroupDiff(flag: String, diff: JsonObject): JsonObject =
        buildJsonObject {
            put("flag", flag)
            put("update", buildJsonObject {
                put("time", JsonNull)
                put("diff", diff)
            })
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
        when {
            whom.startsWith("~") -> {
                val payload = if (isReply) {
                    dmAction(whom, parentId!!, buildJsonObject {
                        put("reply", buildJsonObject {
                            put("id", postId)
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
                            put("id", postId)
                            put("delta", buildJsonObject { put("del", JsonNull) })
                        })
                    })
                } else {
                    clubAction(whom, postId, buildJsonObject { put("del", JsonNull) })
                }
                ch.poke(app = "chat", mark = "chat-club-action-2", payload = payload)
            }
            whom.startsWith("chat/") -> {
                val inner = if (isReply) {
                    // channel-action-2 reply-delete mirrors reply-add:
                    // nests under post.reply.{id, action.del}.
                    buildJsonObject {
                        put("post", buildJsonObject {
                            put("reply", buildJsonObject {
                                put("id", parentId!!)
                                put("action", buildJsonObject {
                                    put("del", JsonPrimitive(postId))
                                })
                            })
                        })
                    }
                } else {
                    buildJsonObject {
                        put("post", buildJsonObject {
                            put("id", JsonPrimitive(postId))
                            put("u-post", buildJsonObject { put("set", JsonNull) })
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
        val response = outer["response"]?.jsonPrimitive?.contentIfString()
        if (response == "poke" || response == "subscribe") {
            val err = outer["err"]
            if (err != null && err !is JsonNull) {
                Log.w(TAG, "$response nack id=${outer["id"]} err=$err")
            }
            return
        }
        if (response != "diff") return
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
            val messages = mutableListOf<MessageEntity>()
            val reactions = mutableListOf<ReactionEntity>()
            posts.forEach { (_, post) -> ingestPost(nest, post, messages, reactions) }
            if (messages.isNotEmpty()) db.messages().upsertAll(messages)
            if (reactions.isNotEmpty()) db.reactions().upsertAll(reactions)
            return
        }
        (response["post"] as? JsonObject)?.let { wrap ->
            val id = wrap["id"]?.jsonPrimitive?.contentIfString() ?: return@let
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
                if (row.whom == focused) row.copy(count = 0, notifyCount = 0) else row
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
            (body as? JsonObject)?.forEach { (ship, entry) ->
                val obj = entry as? JsonObject ?: return@forEach
                // Newer ships wrap each entry as {contact: {...fields},
                // mod-at: "..."}; older ones emit the flat field map.
                val wrapped = obj["contact"] as? JsonObject
                val fields = wrapped ?: obj
                val modAt = parseContactModAt(obj)
                fresh.add(parseContact(ship, fields, modAt))
            }
        }

        runCatching { channel.scry("contacts", "/v1/self") }.getOrNull()?.let { body ->
            (body as? JsonObject)?.let { obj ->
                val wrapped = obj["contact"] as? JsonObject
                val fields = wrapped ?: obj
                val modAt = parseContactModAt(obj)
                fresh.add(parseContact(ourPatp, fields, modAt))
            }
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
            // Prefer the server-provided mod-at. Fall back to our own
            // observation time — we know the status just changed since
            // this fact is the change event itself.
            val modAt = parseContactModAt(page) ?: System.currentTimeMillis()
            db.contacts().upsert(mergeContact(parseContact(kip, contact, modAt)))
            return
        }
        (event["peer"] as? JsonObject)?.let { peer ->
            val who = peer["who"]?.jsonPrimitive?.contentIfString() ?: return
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
        val status = textField("status")
        return ContactEntity(
            ship = ship,
            nickname = textField("nickname"),
            bio = textField("bio"),
            avatarUrl = textField("avatar"),
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
        val raw = envelope?.get("mod-at")?.jsonPrimitive?.contentIfString()
            ?: return null
        val digits = raw.replace(".", "")
        if (digits.isEmpty() || !digits.all { it.isDigit() }) return null
        val value = digits.toLongOrNull() ?: return null
        // Sanity-bound: 2020-01-01 .. 2100-01-01 in unix-ms.
        return if (value in 1_577_836_800_000L..4_102_444_800_000L) value else null
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
        // 5 minutes — refresh on screen entry if older, instant paint
        // from cache within the window.
        private const val ADMIN_CACHE_TTL_MS = 5L * 60_000L
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

    /**
     * Urbit's `dem:ag` parser (used by `slav %ud`) requires decimal
     * atoms to be dot-grouped in threes from the right for values
     * ≥ 1000. Tlon does this when sending ids as strings over the
     * wire; we need to match. "170141184505..." → "170.141.184.505..."
     */
    private fun dotAtom(decimal: String): String {
        if (decimal.length <= 3) return decimal
        // Only dot-format pure digit strings — leave already-dotted or
        // non-numeric values alone.
        if (!decimal.all { it.isDigit() }) return decimal
        val out = StringBuilder()
        var i = decimal.length
        while (i > 3) {
            out.insert(0, "." + decimal.substring(i - 3, i))
            i -= 3
        }
        out.insert(0, decimal.substring(0, i))
        return out.toString()
    }
}
