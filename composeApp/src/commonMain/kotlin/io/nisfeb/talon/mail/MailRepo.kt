package io.nisfeb.talon.mail

import io.ktor.client.HttpClient
import io.nisfeb.talon.urbit.LatticeInstall
import io.nisfeb.talon.urbit.jittered
import io.nisfeb.talon.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import io.nisfeb.talon.util.nowMs
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import io.nisfeb.talon.util.ioDispatcher
import io.nisfeb.talon.data.MailRowEntity
import okio.Path.Companion.toPath

/**
 * Whether this ship can do mail at all.
 *
 * Auspex is a nexus inside the %grubbery desk rather than a desk of its
 * own, so "not installed" splits in two: a ship with no grubbery, and a
 * ship whose grubbery predates auspex. They need different sentences —
 * one is an install, the other is waiting for a sync — so they are
 * different states rather than one absence.
 */
enum class MailAvailability {
    /** Not probed yet this session. */
    UNKNOWN,

    /** The nexus answered. Mail works. */
    PRESENT,

    /** No grubbery on this ship. Offer to install it. */
    NO_GRUBBERY,

    /** Grubbery is here but carries no auspex, so it wants updating. */
    OLD_GRUBBERY,

    /** The session is over. Not a statement about mail. */
    SIGNED_OUT,
}

/**
 * The mailbox, read from the ship on a timer.
 *
 * There is no mirror and no stream. Auspex owns every fact here, and a
 * client that invented its own copy would be a second source of truth
 * for something already stored in exactly one place. So this holds the
 * last answer, in memory and on disk where the host gives it a place,
 * shows it while it asks again, and knows when to ask.
 *
 * Asking again has four triggers, and the timer is only one of them.
 * A write answers as soon as the ship's writer accepts the poke, not
 * when it applies, so nothing is confirmed until a read shows it — see
 * [refresh] and the callers that follow a write with one.
 */
class MailRepo(
    private val http: HttpClient,
    private val scope: CoroutineScope,
    private val pollIntervalMs: Long = DEFAULT_POLL_MS,
    /** This ship's stored folder listings. Null keeps none. */
    private val rows: io.nisfeb.talon.data.MailRowDao? = null,
    /** This ship's thread directory, from [MailThreadFiles.dirFor]. Null keeps none. */
    threadDir: String? = null,
) {
    private val files = threadDir?.let { MailThreadFiles(it.toPath()) }
    private var api: AuspexApi? = null
    private var shipUrl: String? = null

    /** Our own @p, learned from the nexus on the first good read. It is
     *  what a published note's address is built from. */
    private var ourShip: String? = null
    private var poller: Job? = null
    private var foreground = true
    private val gate = Mutex()

    private val _availability = MutableStateFlow(MailAvailability.UNKNOWN)
    val availability: StateFlow<MailAvailability> = _availability.asStateFlow()

    /** The last page the ship gave us, or null before the first answer. */
    private val _page = MutableStateFlow<InboxPage?>(null)
    val page: StateFlow<InboxPage?> = _page.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** The last failure, in the ship's words where it had any. Cleared
     *  by the next answer, so a stale error never outlives a good read. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Bumped each time a refused write's optimistic edit is rolled
     *  back, so a view holding its own copy of a thread can re-derive
     *  it. A flow of the error text cannot do it: two refusals with the
     *  same reason are one emission. */
    private val _rollbacks = MutableStateFlow(0)
    val rollbacks: StateFlow<Int> = _rollbacks.asStateFlow()

    /**
     * Raised for mail that arrived since the last read. Set by the host,
     * which owns the platform's notifier; null means nothing is
     * listening, which is the correct state for a host that has no way
     * to raise one.
     */
    var onNewMail: ((List<MailNotification>) -> Unit)? = null

    /** Threads that were unread when we last looked. Null until the
     *  first read seeds it, which is what stops a launch from replaying
     *  a backlog somebody has had for days. */
    private var seenUnread: Set<String>? = null

    /**
     * Which mailbox is open. One value rather than a view, a label and a
     * drafts flag kept in step by hand: the sidebar and the list read the
     * same thing, so they cannot disagree about what is showing.
     */
    private val _folder = MutableStateFlow<MailFolder>(MailFolder.View(MailView.INBOX))
    val folder: StateFlow<MailFolder> = _folder.asStateFlow()

    fun selectFolder(f: MailFolder) {
        if (_folder.value == f && _query.value.isEmpty()) return
        _query.value = ""
        _folder.value = f
        if (f == MailFolder.Drafts) {
            scope.launch { refreshDrafts() }
        } else {
            // The folder's last listing, if there is one, while it is read again.
            val key = pageKey()
            _page.value = pageCache.value[key]
            scope.launch { restore(key); refresh() }
        }
    }

    /** What the ship is asked for, read off the one folder value. */
    // ---- what was last seen, shown at once while the ship is asked again ----

    /** A listing's identity: its view, the label of a label view, and the search. */
    internal data class PageKey(val view: MailView, val label: String?, val query: String) {
        /** Its name on disk. Searches are not kept: they are asked, not browsed. */
        val stored: String? get() = when {
            query.isNotEmpty() -> null
            label != null -> "label:$label"
            else -> view.wire
        }
    }
    private fun pageKey(): PageKey = viewAndLabel().let { (v, l) -> PageKey(v, l, _query.value) }

    // This session's copies. [rows] and [files] carry them across a restart.
    private val pageCache = MutableStateFlow<Map<PageKey, InboxPage>>(emptyMap())
    private val threadCache = MutableStateFlow<Map<String, MailThread>>(emptyMap())

    /**
     * When [threadId]'s newest message is, as any listing read this
     * session says. An open thread reads it again once a listing says
     * there is something newer, rather than only when it is reopened.
     */
    fun listedLast(threadId: String): StateFlow<Long?> = io.nisfeb.talon.util.mapState(pageCache) { pages ->
        pages.values.mapNotNull { p -> p.threads.firstOrNull { it.id == threadId }?.last }.maxOrNull()
    }

    /** Whether the inbox as last listed holds unread mail: the Mail section's pip. No request of its own. */
    val inboxUnread: StateFlow<Boolean> = io.nisfeb.talon.util.mapState(pageCache) { pages ->
        pages[PageKey(MailView.INBOX, null, "")]?.threads?.any { it.unread } == true
    }

    /** The last copy of a thread this session read, to show while it is read again. */
    fun cachedThread(id: String): MailThread? = threadCache.value[id]

    /**
     * The thread a message is in, for a draft that says what it answers
     * and not where. What is in hand first, then what is on disk: a
     * reply's thread is always one of those, since it had to be open to
     * be replied to.
     */
    suspend fun threadFor(msgId: String): String? =
        threadCache.value.values.firstOrNull { t -> t.messages.any { it.id == msgId } }?.id
            ?: withContext(ioDispatcher) { files?.holding(msgId)?.also(::keepThread)?.id }

    /** [cachedThread], or else the copy an earlier session left on disk. */
    suspend fun storedThread(id: String): MailThread? =
        cachedThread(id) ?: files?.let { f -> withContext(ioDispatcher) { f.read(id) } }?.also { keepThread(it) }

    /** Show a folder's stored listing, unless the ship has already answered for it. */
    private suspend fun restore(key: PageKey) {
        if (key in pageCache.value) return
        val name = key.stored ?: return
        val stored = runCatching { rows?.listing(name) }.getOrNull().orEmpty()
        if (stored.isEmpty()) return
        val threads = stored.mapNotNull { r ->
            runCatching { AuspexApi.json.decodeFromString(InboxEntry.serializer(), r.json) }.getOrNull()
        }
        val p = InboxPage(total = stored.first().total, limit = threads.size, view = key.view.wire, threads = threads)
        pageCache.update { if (key in it) it else it + (key to p) }
        if (pageKey() == key && _page.value == null) _page.value = p
    }

    private suspend fun store(name: String, p: InboxPage) {
        val d = rows ?: return
        runCatching {
            d.replace(
                name,
                p.threads.mapIndexed { i, t ->
                    MailRowEntity(name, t.id, i, p.total, AuspexApi.json.encodeToString(InboxEntry.serializer(), t))
                },
            )
        }.onFailure { Log.w(TAG, "mail listing not stored", it) }
    }

    /** A thread that is gone leaves nothing of itself on disk. */
    private suspend fun forget(threadId: String) {
        files?.let { f -> withContext(ioDispatcher) { f.delete(threadId) } }
        runCatching { rows?.dropThread(threadId) }
    }

    private fun keepThread(t: MailThread) = threadCache.update { m ->
        (m - t.id + (t.id to t)).let { if (it.size > THREAD_CACHE) it - it.keys.first() else it }
    }

    private fun editThread(id: String, f: (MailThread) -> MailThread) =
        threadCache.update { m -> m[id]?.let { m + (id to f(it)) } ?: m }

    /** Edit every cached listing, and the one on screen with them. */
    private fun editPages(f: (PageKey, InboxPage) -> InboxPage) {
        pageCache.update { m -> m.mapValues { (k, p) -> f(k, p) } }
        _page.value?.let { shown -> _page.value = pageCache.value[pageKey()] ?: f(pageKey(), shown) }
    }

    private fun viewAndLabel(): Pair<MailView, String?> = when (val f = _folder.value) {
        is MailFolder.View -> f.view to null
        is MailFolder.Label -> MailView.LABEL to f.name
        MailFolder.Drafts -> MailView.INBOX to null
    }

    // ---- lifecycle -----------------------------------------------------

    /** Point at a signed-in ship. Safe to call again on a re-login. */
    fun attach(baseUrl: String) {
        if (shipUrl == baseUrl && api != null) return
        shipUrl = baseUrl
        api = AuspexApi(http, baseUrl)
        _availability.value = MailAvailability.UNKNOWN
        _page.value = null
        _error.value = null
        seenUnread = null
        pageCache.value = emptyMap()
        threadCache.value = emptyMap()
        startPolling()
    }

    fun detach() {
        poller?.cancel()
        poller = null
        api = null
        shipUrl = null
        _availability.value = MailAvailability.UNKNOWN
        _page.value = null
        _error.value = null
        pageCache.value = emptyMap()
        threadCache.value = emptyMap()
    }

    /**
     * Trigger three: coming back to the app. A list that is minutes old
     * at the moment somebody looks at it is the case the timer alone
     * cannot cover.
     */
    fun setForeground(on: Boolean) {
        val was = foreground
        foreground = on
        if (on && !was) scope.launch { refresh() }
    }



    // ---- reading -------------------------------------------------------

    /**
     * Trigger four, and the body of the other three: ask the ship what
     * is in the mailbox now.
     *
     * Serialised, because the timer, a foreground return and a tap on
     * the refresh control can all land together and the ship runs its
     * events one at a time.
     */
    suspend fun refresh() = gate.withLock {
        val a = api ?: return@withLock
        // Re-read everything already on screen, not just the first page:
        // a refresh that threw away what somebody had paged into would
        // look like mail disappearing.
        val p = read(a, maxOf(_page.value?.threads?.size ?: 0, AuspexApi.DEFAULT_PAGE))
            ?: return@withLock
        _availability.value = MailAvailability.PRESENT
        if (ourShip == null) {
            ourShip = try {
                a.whoami()
            } catch (e: AuspexError) {
                // Mail still works; filing to Lattice is what breaks,
                // and a quiet null there reads as "no address" rather
                // than a failure. Say which it was.
                _error.value = when (e) {
                    is AuspexError.Refused -> e.reason
                    is AuspexError.Garbled -> "The ship answered something we could not read."
                    is AuspexError.Unreachable -> "No answer from the ship."
                }
                null
            }
        }
        announce(p)
    }

    /**
     * One listing read that leaves the screen alone: a view, a search,
     * or both, for the assistant, which must not move what the reader
     * has open. Null when the ship refused or is out of reach.
     */
    suspend fun listing(view: MailView = MailView.INBOX, query: String? = null, limit: Int = AuspexApi.DEFAULT_PAGE): InboxPage? =
        call(probeAbsentApp = true) { it.inbox(view = view, query = query?.takeIf { q -> q.isNotBlank() }, limit = limit.coerceAtMost(MAX_PAGE)) }

    /** One listing read for the open folder, or null after [onFailure]. */
    private suspend fun read(a: AuspexApi, limit: Int): InboxPage? {
        _loading.value = true
        try {
            val key = pageKey()
            val p = a.inbox(
                view = key.view,
                label = key.label,
                query = key.query.takeIf { it.isNotEmpty() },
                limit = limit.coerceAtMost(MAX_PAGE),
            )
            pageCache.update { it + (key to p) }
            key.stored?.let { store(it, p) }
            // Somebody who changed folder while this was on its way sees the new one.
            if (pageKey() == key) _page.value = p
            _error.value = null
            return p
        } catch (e: AuspexError) {
            onFailure(e, probeAbsentApp = true)
            return null
        } finally {
            _loading.value = false
        }
    }

    /**
     * Tell the host about mail that is new since the last read.
     *
     * Only the inbox: the sent and archived views are places a person
     * goes looking, not places mail arrives, and announcing a thread
     * because they switched tabs would be noise. The first read of a
     * session only seeds the baseline.
     */
    private fun announce(p: InboxPage) {
        if (viewAndLabel().first != MailView.INBOX || _query.value.isNotEmpty()) return
        val before = seenUnread
        if (before == null) {
            seenUnread = seedMailBaseline(p.threads)
            return
        }
        val (fired, now) = diffMailNotifications(p.threads, before, io.nisfeb.talon.ui.ShipNames.resolve)
        seenUnread = now
        if (fired.isNotEmpty()) onNewMail?.invoke(fired)
    }

    /**
     * Work out why a call failed, and in particular tell "this ship has
     * no mail app" apart from "mail is broken". Auspex has no
     * unauthenticated surface, so unlike lattice this cannot be probed
     * without a session — which is also why a dead session has to be its
     * own answer rather than being read as an absent app.
     *
     * [probeAbsentApp] is for the inbox read path ONLY: a 404 there
     * means the nexus is gone, so grubbery is asked whether it ever
     * arrived. Every other call routes a 404 to the ordinary refusal
     * branch — a thread or draft that is gone is the thing being written
     * to having vanished, not the app, and swapping the whole inbox for
     * an install prompt over it is how a deleted thread used to look
     * like an uninstalled one.
     */
    private suspend fun onFailure(e: AuspexError, probeAbsentApp: Boolean = false) {
        when {
            e.isSignedOut -> {
                _availability.value = MailAvailability.SIGNED_OUT
                _error.value = "Signed out of the ship."
                // Nothing retries its way back from this one.
                poller?.cancel()
                poller = null
            }
            probeAbsentApp && e is AuspexError.Refused && e.status == AuspexApi.NOT_FOUND -> {
                val url = shipUrl
                val grubbery = url != null && LatticeInstall.isInstalled(http, url)
                _availability.value =
                    if (grubbery) MailAvailability.OLD_GRUBBERY else MailAvailability.NO_GRUBBERY
                _error.value = null
            }
            else -> {
                _error.value = when (e) {
                    is AuspexError.Refused -> e.reason
                    is AuspexError.Garbled -> "The ship answered something we could not read."
                    is AuspexError.Unreachable -> "No answer from the ship."
                }
                Log.w(TAG, "mail refresh failed", e)
            }
        }
    }

    /** True while there is more of this view than we have asked for. */
    val hasMore: StateFlow<Boolean> = _page
        .map { p -> p != null && p.threads.size < p.total && p.threads.size < MAX_PAGE }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /**
     * Ask for more of the same view.
     *
     * A longer first page rather than a second one appended: the ship
     * caps a listing at two hundred anyway, and re-reading is how every
     * other refresh works, so one shape covers both.
     */
    suspend fun loadMore() = gate.withLock {
        val a = api ?: return@withLock
        val have = _page.value?.threads?.size ?: return@withLock
        if (have >= MAX_PAGE) return@withLock
        read(a, have + AuspexApi.DEFAULT_PAGE)
    }

    // ---- filing to Lattice ---------------------------------------------

    /**
     * Publish a message or a whole thread to this ship's Lattice as a
     * gemtext note, and answer its urb:// address.
     *
     * Null when it did not land, with the reason in [error]. Lattice
     * lives in the same desk as auspex, so a ship with mail has it; a
     * failure here is a real failure rather than a missing app.
     *
     * The slug is derived from the title and a seed, so publishing the
     * same thread twice edits one note rather than piling up copies.
     */
    suspend fun publishToLattice(title: String, seed: String, gemtext: String): String? {
        val url = shipUrl ?: return null
        val ship = ourShip ?: return null
        val slug = io.nisfeb.talon.urbit.LatticePublish.slug(title, seed)
        return runCatching {
            io.nisfeb.talon.urbit.LatticePublish.publish(http, url, ship, slug, gemtext)
        }.onFailure {
            Log.w(TAG, "lattice publish failed", it)
            _error.value = "Could not file to Lattice: ${it.message}"
        }.getOrNull()
    }

    // ---- one thread ----------------------------------------------------

    /** Read one thread. Null when it is gone, which the reader shows
     *  differently from a thread that failed to load. */
    suspend fun loadThread(id: String): MailThread? {
        val t = call { it.thread(id).also { _error.value = null } }
        when {
            t != null -> {
                keepThread(t)
                files?.let { f -> withContext(ioDispatcher) { f.write(t) } }
            }
            // Gone, rather than out of reach: nothing of it stays on disk.
            _error.value == null -> forget(id)
        }
        return t
    }

    /**
     * Mark messages read, then re-read the listing.
     *
     * The refresh is the point, not politeness: a write answers when the
     * ship's writer accepts the poke, so the listing is the only thing
     * that can say the mark landed. Read marks are invisible to every
     * other client, so nothing else will ever tell us.
     */
    fun markRead(msgIds: List<String>, threadId: String? = null) =
        act(threadOf(msgIds, threadId), { readState(msgIds, threadId, read = true) }) { it.markRead(msgIds) }

    /** Put a thread back to unread, so it stands out again on return.
     *  Also local, so this refreshes its own view like the rest. */
    fun markUnread(msgIds: List<String>, threadId: String? = null) =
        act(threadOf(msgIds, threadId), { readState(msgIds, threadId, read = false) }) { it.markUnread(msgIds) }

    /** The thread a read-mark edits on screen, when that can be told.
     *  Null only means the optimistic half has nothing to edit; the
     *  write goes either way. */
    private fun threadOf(msgIds: List<String>, threadId: String?): String? =
        threadId ?: threadCache.value.values.firstOrNull { t -> t.messages.any { it.id in msgIds } }?.id

    private fun readState(msgIds: List<String>, threadId: String?, read: Boolean) {
        val tid = threadId ?: threadCache.value.values.firstOrNull { t -> t.messages.any { it.id in msgIds } }?.id ?: return
        editPages { _, p -> p.editRow(tid) { it.copy(unread = !read) } }
        editThread(tid) { t -> t.copy(messages = t.messages.map { m -> if (m.id in msgIds) m.copy(read = read) else m }) }
    }

    fun setArchived(threadId: String, archived: Boolean) = act(threadId, {
        editPages { key, p -> p.archived(key.view, threadId, archived) }
        editThread(threadId) { it.copy(archived = archived) }
    }) { it.setArchived(threadId, archived) }

    fun deleteThread(threadId: String) = act(threadId, {
        editPages { _, p -> p.without(threadId) }
        threadCache.update { it - threadId }
    }) { it.deleteThread(threadId); forget(threadId) }

    /** Ask the network for an attachment we do not hold. */
    suspend fun fetchBlob(hash: String, from: String) {
        call { it.fetchBlob(hash, from) }
    }

    /** An attachment's bytes, or null while this ship holds no copy. */
    suspend fun blob(hash: String, name: String, mime: String): Blob? {
        return call { it.blob(hash, name, mime) }
    }

    /**
     * Send, then re-read. True only when the ship accepted the poke;
     * that it applied is a thing only the refetch can show, which is why
     * one follows immediately rather than at the next tick.
     */
    suspend fun send(
        to: List<String>,
        subject: String,
        body: String,
        prev: String?,
        attachments: List<AttachRef>,
    ): Boolean {
        // A reply shows in its thread at once, as this ship's own message,
        // until the thread is read again; a refused send takes it back out.
        var optimistic: Pair<String, MailThread>? = null
        if (prev != null) {
            threadCache.value.values.firstOrNull { t -> t.messages.any { it.id == prev } }?.let { t ->
                optimistic = t.id to t
                val now = nowMs()
                editThread(t.id) {
                    it.copy(
                        messages = it.messages + MailMessage(
                            id = "local-$now-${localSeq++}", from = ourShip.orEmpty(), to = to, subject = subject, body = body,
                            sent = now, prev = prev, verdict = Verdict.VERIFIED, read = true,
                            attachments = attachments.map { a -> Attachment(name = a.name, mime = a.mime, hash = a.hash) },
                        ),
                    )
                }
            }
        }
        if (call { it.send(to, subject, body, prev, attachments) } == null) {
            // Only the thread the phantom reply went into goes back; a
            // refresh that landed while the send was out stays.
            optimistic?.let { (id, before) ->
                threadCache.update { it + (id to before) }
                _rollbacks.update { it + 1 }
            }
            return false
        }
        refresh()
        return true
    }

    /**
     * Sign and send a saved draft. The ship drops the draft itself, and
     * only when the send landed, so a send its writer silently refuses
     * keeps the draft, which is the reason to send one this way rather
     * than [send] plus [deleteDraft]. True once the ship has dropped the
     * draft; false when it refused; null when it took the send and has
     * not yet been seen to drop the draft.
     *
     * The route answers before the writer applies, so its yes means taken
     * and nothing more. The drafts list is the proof, and it can lag the
     * answer or fail to read: taking a single look as final reported a
     * sent message as unsent, which then invited sending it twice.
     */
    suspend fun sendDraft(id: String): Boolean? {
        if (call { it.sendDraft(id) } == null) return false
        refresh()
        repeat(4) { i ->
            if (i > 0) kotlinx.coroutines.delay(1_000)
            val now = call { it.drafts() } ?: return@repeat
            _drafts.value = now
            if (now.none { it.id == id }) return true
        }
        return null
    }

    /** Distinguishes two phantom replies minted inside one millisecond. */
    private var localSeq = 0

    /** Store one file, answering the address a send will name. */
    suspend fun uploadBlob(bytes: ByteArray): String {
        val a = api ?: error("not signed in")
        return a.uploadBlob(bytes)
    }

    /** One request against the ship, or null after [onFailure]. The
     *  inbox read path alone passes [probeAbsentApp]; everything else
     *  treats a 404 as an ordinary refusal. */
    private suspend fun <T> call(probeAbsentApp: Boolean = false, block: suspend (AuspexApi) -> T): T? {
        val a = api ?: return null
        return try {
            block(a)
        } catch (e: AuspexError) {
            onFailure(e, probeAbsentApp)
            null
        }
    }

    /** A write, and the re-read that shows it. False when the write failed. */
    private suspend fun mutate(refresh: suspend () -> Unit, block: suspend (AuspexApi) -> Unit): Boolean {
        call(block = block) ?: return false
        refresh()
        return true
    }

    /**
     * An action shows at once: [local] edits what is on screen, the write
     * goes to the ship in the background, and a refused write puts the
     * screen back as it was. Returns straight away, so a view that leaves
     * after the action does not cancel the write by leaving.
     *
     * [tid] is the one thread [local] touches, and the rollback puts
     * back only that thread and its listing rows — never a wholesale
     * snapshot, which would throw away a refresh that landed while the
     * write was out.
     */
    private fun act(tid: String?, local: () -> Unit, write: suspend (AuspexApi) -> Unit) {
        val threadBefore = tid?.let { threadCache.value[it] }
        val rowsBefore = tid?.let { id -> pageCache.value.mapValues { (_, p) -> p.rowAt(id) } }.orEmpty()
        val shownKey = pageKey()
        val shownRowBefore = tid?.let { id -> _page.value?.rowAt(id) }
        local()
        scope.launch {
            val a = api ?: return@launch
            try {
                write(a)
            } catch (e: AuspexError) {
                // A 404 on a write is the thing being written to having
                // gone -- a thread another client deleted -- not the nexus
                // being absent. onFailure reads every 404 as the latter and
                // replaced the whole inbox with an install prompt. Refresh
                // instead, which drops the vanished row.
                if (e is AuspexError.Refused && e.status == AuspexApi.NOT_FOUND) {
                    refresh()
                    return@launch
                }
                if (tid != null) {
                    // Only what local() touched goes back. Anything a
                    // refresh landed meanwhile is newer than the snapshot
                    // and stays.
                    if (threadBefore != null) threadCache.update { it + (tid to threadBefore) }
                    pageCache.update { m -> m.mapValues { (k, p) -> p.restoring(tid, rowsBefore[k]) } }
                    if (pageKey() == shownKey) {
                        _page.value?.let { _page.value = it.restoring(tid, shownRowBefore) }
                    }
                    _rollbacks.update { it + 1 }
                }
                onFailure(e)
                return@launch
            }
            refresh()
        }
    }

    // ---- labels, filters, lists ----------------------------------------

    private val _query = MutableStateFlow("")

    /** What the listing is searching for, or empty. */
    val query: StateFlow<String> = _query.asStateFlow()

    /**
     * Search, which the ship runs over subjects, bodies and rendered
     * senders, case-insensitively.
     *
     * It leaves the current mailbox on purpose: an archived forgery is
     * exactly the thing somebody searches for, and a search that only
     * looked where they already were would not find it.
     */
    fun search(q: String) {
        val trimmed = q.trim()
        if (_query.value == trimmed) return
        _query.value = trimmed
        if (trimmed.isNotEmpty()) _folder.value = MailFolder.View(MailView.ALL)
        val key = pageKey()
        _page.value = pageCache.value[key]
        scope.launch { restore(key); refresh() }
    }

    private val _rules = MutableStateFlow<List<Rule>>(emptyList())
    val rules: StateFlow<List<Rule>> = _rules.asStateFlow()

    private val _lists = MutableStateFlow<List<MailingList>>(emptyList())
    val lists: StateFlow<List<MailingList>> = _lists.asStateFlow()

    /** Every label the current page mentions. The ship keeps no index
     *  of them, so the listing is where they come from. */
    val knownLabels: StateFlow<List<String>> = _page
        .map { page -> page?.threads.orEmpty().flatMap { it.labels }.distinct().sorted() }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun setLabel(threadId: String, label: String, add: Boolean) = act(threadId, {
        editPages { key, p ->
            if (!add && key.view == MailView.LABEL && key.label == label) p.without(threadId)
            else p.editRow(threadId) { r -> r.copy(labels = if (add) (r.labels + label).distinct() else r.labels - label) }
        }
        editThread(threadId) { t -> t.copy(labels = if (add) (t.labels + label).distinct() else t.labels - label) }
    }) { it.setLabel(threadId, label, add) }

    suspend fun refreshRules() {
        call { _rules.value = it.rules() }
    }

    suspend fun saveRule(r: Rule) = mutate(::refreshRules) { it.saveRule(r) }

    suspend fun deleteRule(id: String) = mutate(::refreshRules) { it.deleteRule(id) }

    suspend fun refreshLists() {
        call { _lists.value = it.lists() }
    }

    suspend fun saveList(l: MailingList) = mutate(::refreshLists) { it.saveList(l) }

    suspend fun deleteList(name: String) = mutate(::refreshLists) { it.deleteList(name) }

    // ---- drafts --------------------------------------------------------

    private val _drafts = MutableStateFlow<List<Draft>>(emptyList())
    val drafts: StateFlow<List<Draft>> = _drafts.asStateFlow()

    suspend fun refreshDrafts() {
        call { _drafts.value = it.drafts() }
    }

    /** Store a draft. The id comes from the caller and stays the same
     *  across saves, so the second save overwrites the first. */
    suspend fun saveDraft(d: Draft): Boolean = mutate(::refreshDrafts) { it.saveDraft(d) }

    /**
     * Save a draft from somewhere that cannot wait for it: a screen on
     * its way out of composition, whose own scope dies with it. The
     * repo's scope outlives every screen, so the save still lands.
     */
    fun keepDraft(d: Draft) {
        scope.launch {
            // A save that does not land is said so: silence here is a
            // message the owner believes is kept and is not.
            val ok = runCatching { saveDraft(d) }.getOrDefault(false)
            if (!ok) _error.value = "The draft did not reach the ship; what was written is still here until Talon closes."
        }
    }

    /** Drop a draft from a screen on its way out, like [keepDraft]. */
    fun dropDraft(id: String) {
        scope.launch { runCatching { deleteDraft(id) } }
    }

    suspend fun deleteDraft(id: String) = mutate(::refreshDrafts) { it.deleteDraft(id) }

    // ---- the timer -----------------------------------------------------

    /**
     * Trigger one. Ten minutes, jittered, because a pier restart drops
     * every client at once and a fixed interval marches them all back on
     * the same tick.
     *
     * This costs the user's own ship one authenticated read per tick and
     * fans out to nobody, which is why a poll is the right shape here and
     * was the wrong shape for party-line presence.
     */
    private fun startPolling() {
        poller?.cancel()
        poller = scope.launch {
            restore(pageKey())
            refresh()
            while (isActive) {
                delay(jittered(pollIntervalMs))
                if (foreground && _availability.value != MailAvailability.SIGNED_OUT) refresh()
            }
        }
    }

    companion object {
        private const val TAG = "MailRepo"

        /** Threads kept for a quick return; the oldest read goes first. */
        private const val THREAD_CACHE = 40

        /** Mail is considered correspondence, not chat. The refresh
         *  control covers the case where the reader knows better. */
        const val DEFAULT_POLL_MS = 10 * 60 * 1000L

        /** The ship's own ceiling on a listing. Asking past it answers
         *  the same page, so the control has to stop here and say so. */
        const val MAX_PAGE = 200
    }
}

/**
 * A mailbox in the sidebar. The ship's own views, the local drafts
 * store, and a label used as a folder — which is what a label is, once
 * you can click it.
 */
sealed interface MailFolder {
    data class View(val view: MailView) : MailFolder
    data object Drafts : MailFolder
    data class Label(val name: String) : MailFolder
}

/**
 * Open a mail composer addressed to a ship, from anywhere in the app.
 *
 * Null where this ship has no mail app, and every consumer treats null
 * as "do not offer it". A control that cannot work is worse than one
 * that is not there, and a profile sheet three screens deep should not
 * have to be handed the plumbing to know that.
 */
val LocalMailTo = androidx.compose.runtime.staticCompositionLocalOf<((String) -> Unit)?> { null }

/**
 * Install the desk mail lives in, on the viewer's own ship, and wait
 * for it to arrive. Null where the host cannot do it.
 *
 * A composition local for the same reason [LocalMailTo] is one: the
 * place that needs to offer this is an empty state several screens
 * deep, and it should not have to be handed a ship URL and a poke
 * function to say one sentence.
 */
val LocalGrubberyInstall =
    androidx.compose.runtime.staticCompositionLocalOf<(suspend () -> Result<Unit>)?> { null }

/** A listing without one thread, and one fewer in its total. */
internal fun InboxPage.without(id: String): InboxPage =
    if (threads.none { it.id == id }) this else copy(threads = threads.filter { it.id != id }, total = (total - 1).coerceAtLeast(0))

/** Where a row sits in this listing, and the row — or null when it is not here. */
private fun InboxPage.rowAt(id: String): Pair<Int, InboxEntry>? =
    threads.indexOfFirst { it.id == id }.let { if (it < 0) null else it to threads[it] }

/**
 * A listing with one row put back the way [before] remembers: replaced
 * where it still sits, re-seated at its old place where an optimistic
 * edit took it out, and left alone where it never was — a row that
 * turned up meanwhile is a refresh's news, not a rollback's to take.
 */
internal fun InboxPage.restoring(id: String, before: Pair<Int, InboxEntry>?): InboxPage {
    val at = threads.indexOfFirst { it.id == id }
    return when {
        before == null -> this
        at >= 0 -> copy(threads = threads.mapIndexed { i, r -> if (i == at) before.second else r })
        else -> copy(
            threads = threads.toMutableList().apply { add(before.first.coerceIn(0, size), before.second) },
            total = total + 1,
        )
    }
}

/** A listing with one row changed. */
internal fun InboxPage.editRow(id: String, f: (InboxEntry) -> InboxEntry): InboxPage =
    copy(threads = threads.map { if (it.id == id) f(it) else it })

/**
 * What archiving or unarchiving a thread does to a listing of [view]: it
 * leaves the inbox or the archive it no longer belongs in, and anywhere
 * else it only says so.
 */
internal fun InboxPage.archived(view: MailView, threadId: String, archived: Boolean): InboxPage = when {
    view == MailView.INBOX && archived -> without(threadId)
    view == MailView.ARCHIVED && !archived -> without(threadId)
    else -> editRow(threadId) { it.copy(archived = archived) }
}
