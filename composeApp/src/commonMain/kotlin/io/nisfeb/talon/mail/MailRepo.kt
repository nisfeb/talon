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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 * There is no local mirror and no stream. Auspex owns every fact here,
 * and a client that invented its own copy would be a second source of
 * truth for something already stored in exactly one place. So this
 * holds the last answer and knows when to ask again.
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
) {
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
            _page.value = null
            scope.launch { refresh() }
        }
    }

    /** What the ship is asked for, read off the one folder value. */
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
        if (ourShip == null) ourShip = runCatching { a.whoami() }.getOrNull()
        announce(p)
    }

    /** One listing read for the open folder, or null after [onFailure]. */
    private suspend fun read(a: AuspexApi, limit: Int): InboxPage? {
        _loading.value = true
        try {
            val (view, label) = viewAndLabel()
            val p = a.inbox(
                view = view,
                label = label,
                query = _query.value.takeIf { it.isNotEmpty() },
                limit = limit.coerceAtMost(MAX_PAGE),
            )
            _page.value = p
            _error.value = null
            return p
        } catch (e: AuspexError) {
            onFailure(e)
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
     * Work out why a read failed, and in particular tell "this ship has
     * no mail app" apart from "mail is broken". Auspex has no
     * unauthenticated surface, so unlike lattice this cannot be probed
     * without a session — which is also why a dead session has to be its
     * own answer rather than being read as an absent app.
     */
    private suspend fun onFailure(e: AuspexError) {
        when {
            e.isSignedOut -> {
                _availability.value = MailAvailability.SIGNED_OUT
                _error.value = "Signed out of the ship."
                // Nothing retries its way back from this one.
                poller?.cancel()
                poller = null
            }
            e is AuspexError.Refused && e.status == AuspexApi.NOT_FOUND -> {
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
        return call { it.thread(id).also { _error.value = null } }
    }

    /**
     * Mark messages read, then re-read the listing.
     *
     * The refresh is the point, not politeness: a write answers when the
     * ship's writer accepts the poke, so the listing is the only thing
     * that can say the mark landed. Read marks are invisible to every
     * other client, so nothing else will ever tell us.
     */
    suspend fun markRead(msgIds: List<String>) = write { it.markRead(msgIds) }

    /** Put a thread back to unread, so it stands out again on return.
     *  Also local, so this refreshes its own view like the rest. */
    suspend fun markUnread(msgIds: List<String>) = write { it.markUnread(msgIds) }

    suspend fun setArchived(threadId: String, archived: Boolean) =
        write { it.setArchived(threadId, archived) }

    suspend fun deleteThread(threadId: String) = write { it.deleteThread(threadId) }

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
        call { it.send(to, subject, body, prev, attachments) } ?: return false
        refresh()
        return true
    }

    /** Store one file, answering the address a send will name. */
    suspend fun uploadBlob(bytes: ByteArray): String {
        val a = api ?: error("not signed in")
        return a.uploadBlob(bytes)
    }

    /**
     * One request against the ship, or null after [onFailure]. Eleven
     * callers used to spell this out, in three different ways.
     */
    private suspend fun <T> call(block: suspend (AuspexApi) -> T): T? {
        val a = api ?: return null
        return try {
            block(a)
        } catch (e: AuspexError) {
            onFailure(e)
            null
        }
    }

    private suspend fun write(block: suspend (AuspexApi) -> Unit) {
        val a = api ?: return
        try {
            block(a)
        } catch (e: AuspexError) {
            // A 404 on a write is the thing being written to having
            // gone -- a thread another client deleted -- not the nexus
            // being absent. onFailure reads every 404 as the latter and
            // replaced the whole inbox with an install prompt. Refresh
            // instead, which drops the vanished row.
            if (e is AuspexError.Refused && e.status == AuspexApi.NOT_FOUND) {
                refresh()
                return
            }
            onFailure(e)
            return
        }
        refresh()
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
        _page.value = null
        scope.launch { refresh() }
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

    suspend fun setLabel(threadId: String, label: String, add: Boolean) =
        write { it.setLabel(threadId, label, add) }

    suspend fun refreshRules() {
        call { _rules.value = it.rules() }
    }

    suspend fun saveRule(r: Rule) {
        call { it.saveRule(r) } ?: return
        refreshRules()
    }

    suspend fun deleteRule(id: String) {
        call { it.deleteRule(id) } ?: return
        refreshRules()
    }

    suspend fun refreshLists() {
        call { _lists.value = it.lists() }
    }

    suspend fun saveList(l: MailingList) {
        call { it.saveList(l) } ?: return
        refreshLists()
    }

    suspend fun deleteList(name: String) {
        call { it.deleteList(name) } ?: return
        refreshLists()
    }

    // ---- drafts --------------------------------------------------------

    private val _drafts = MutableStateFlow<List<Draft>>(emptyList())
    val drafts: StateFlow<List<Draft>> = _drafts.asStateFlow()

    suspend fun refreshDrafts() {
        call { _drafts.value = it.drafts() }
    }

    /** Store a draft. The id comes from the caller and stays the same
     *  across saves, so the second save overwrites the first. */
    suspend fun saveDraft(d: Draft) {
        call { it.saveDraft(d) } ?: return
        refreshDrafts()
    }

    suspend fun deleteDraft(id: String) {
        call { it.deleteDraft(id) } ?: return
        refreshDrafts()
    }

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
            refresh()
            while (isActive) {
                delay(jittered(pollIntervalMs))
                if (foreground && _availability.value != MailAvailability.SIGNED_OUT) refresh()
            }
        }
    }

    companion object {
        private const val TAG = "MailRepo"

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
