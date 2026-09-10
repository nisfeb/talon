package io.nisfeb.talon.mail

import io.ktor.client.HttpClient
import io.nisfeb.talon.urbit.LatticeInstall
import io.nisfeb.talon.urbit.jittered
import io.nisfeb.talon.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
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

    private val _view = MutableStateFlow(MailView.INBOX)
    val view: StateFlow<MailView> = _view.asStateFlow()

    // ---- lifecycle -----------------------------------------------------

    /** Point at a signed-in ship. Safe to call again on a re-login. */
    fun attach(baseUrl: String) {
        if (shipUrl == baseUrl && api != null) return
        shipUrl = baseUrl
        api = AuspexApi(http, baseUrl)
        _availability.value = MailAvailability.UNKNOWN
        _page.value = null
        _error.value = null
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

    /** Which slice of the mailbox the list is showing. */
    fun setView(v: MailView) {
        if (_view.value == v) return
        _view.value = v
        _page.value = null
        scope.launch { refresh() }
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
        _loading.value = true
        try {
            val p = a.inbox(view = _view.value)
            _page.value = p
            _error.value = null
            _availability.value = MailAvailability.PRESENT
        } catch (e: AuspexError) {
            onFailure(e)
        } finally {
            _loading.value = false
        }
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
    }
}
