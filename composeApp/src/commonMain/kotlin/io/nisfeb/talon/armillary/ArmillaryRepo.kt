package io.nisfeb.talon.armillary

import io.ktor.client.HttpClient
import io.nisfeb.talon.ai.ARMILLARY_PROVIDER
import io.nisfeb.talon.ai.AiProfile
import io.nisfeb.talon.ai.AiProvider
import io.nisfeb.talon.ai.AiSettingsRepository
import io.nisfeb.talon.ai.ModelInfo
import io.nisfeb.talon.ai.allOn
import io.nisfeb.talon.ai.ProviderKind
import io.nisfeb.talon.ai.profile
import io.nisfeb.talon.ai.without
import io.nisfeb.talon.ui.platformLabel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import io.nisfeb.talon.notify.Notifier
import io.nisfeb.talon.notify.NoopNotifier
import io.nisfeb.talon.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.nisfeb.talon.util.runSuspendCatching

/**
 * Armillary on the owner's own ship: the balance, the plans, the
 * checkouts and the inference config the AI features run on.
 *
 * Off until the owner adds the Armillary provider under Settings > AI.
 * Adding it asks the ship for a key, and from then on every read of the
 * inference config is written onto that provider row, so the clients
 * find the base, the key and the models where they already look. The
 * key belongs to this ship and this device: it never travels in the
 * synced profile.
 */
class ArmillaryRepo(
    private val http: HttpClient,
    private val scope: CoroutineScope,
    /** Where the provider row lives. Null in a test that only reads. */
    private val aiSettings: AiSettingsRepository? = null,
    /** Told once when a payment lands, for a window behind the browser. The Noop one says nothing. */
    private val notifier: Notifier = NoopNotifier,
    /** Records the credit in the Activity feed's Account domain. Null in a test. */
    private val noticeRepo: io.nisfeb.talon.notify.NoticeRepo? = null,
    /** How often the vendor's picks are read off the ship while attached; a test's own. */
    private val picksEveryMs: Long = PICKS_EVERY_MS,
    /** The clock coming back to the front is measured on; a test's own. */
    private val now: () -> Long = { io.nisfeb.talon.util.nowMs() },
    /** The first pause before a failed check of the ship is tried again; a test's own. */
    private val probeRetryFirstMs: Long = io.nisfeb.talon.orrery.PROBE_RETRY_FIRST_MS,
) {
    private val _availability = MutableStateFlow(ArmillaryAvailability.UNKNOWN)
    val availability: StateFlow<ArmillaryAvailability> = _availability.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _account = MutableStateFlow<Account?>(null)
    val account: StateFlow<Account?> = _account.asStateFlow()
    private val _plans = MutableStateFlow<List<Plan>>(emptyList())
    val plans: StateFlow<List<Plan>> = _plans.asStateFlow()
    /** What the vendor offers, read for its zero-retention flags; nothing shows it. */
    /** Null until read: which models are ZDR is not "none" before the ship has said. */
    private var catalog: List<CatalogRow>? = null
    private val _inference = MutableStateFlow<Inference?>(null)
    val inference: StateFlow<Inference?> = _inference.asStateFlow()
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()
    private val _payment = MutableStateFlow<Payment?>(null)

    /** The checkout this session opened last, while the card has something to say about it. */
    val payment: StateFlow<Payment?> = _payment.asStateFlow()

    private val _settingUp = MutableStateFlow(false)

    /** [setUpDevice] is waiting for this device's key. */
    val settingUp: StateFlow<Boolean> = _settingUp.asStateFlow()

    private val _keyProblem = MutableStateFlow<String?>(null)

    /**
     * Why this device still has no key, in the owner's terms: the last
     * ask failed, or the ship did not answer whether it holds one. Null
     * once a key is read. The first ask used to run on the screen and
     * drop its failure, and the card said only "No models yet".
     */
    val keyProblem: StateFlow<String?> = _keyProblem.asStateFlow()

    private val _noPublicUrl = MutableStateFlow(false)

    /**
     * The ship is its own vendor and has no public URL, so its inference
     * base reads http://localhost:8080, which no other device reaches.
     * Talon uses the ship address it signed in with instead, and the card
     * asks the owner to set Public URL in Armillary's Payments view.
     */
    val noPublicUrl: StateFlow<Boolean> = _noPublicUrl.asStateFlow()

    private val _deletion = MutableStateFlow<Deletion?>(null)

    /**
     * The account deletion the ship took this session. The card goes
     * with the row, so the screen says what happened from this instead.
     */
    val deletion: StateFlow<Deletion?> = _deletion.asStateFlow()

    private var api: ArmillaryApi? = null
    private var shipUrl: String? = null

    /**
     * Armillary's own page on this ship, where the owner picks their own
     * models or brings their own provider key (the AI tab's "Manage models
     * in Armillary"). Null until attached.
     */
    val pageUrl: String? get() = shipUrl?.trimEnd('/')?.let { "$it/apps/armillary#catalog" }
    private var ship: String? = null
    private var watching: Job? = null
    private var adopting: Job? = null
    private var ticking: Job? = null
    private var retrying: Job? = null
    @kotlin.concurrent.Volatile private var resumedAtMs = 0L

    /** The app is in front ([resumed]) or behind ([paused]); in front until told. */
    @kotlin.concurrent.Volatile private var inFront = true

    // Coroutines alone touch the repo, so one lock is the whole of the
    // guard; commonMain has no synchronized, iOS being native.
    private val lock = Mutex()

    /** Point at a ship. Idempotent: the same ship twice changes nothing. */
    fun attach(shipUrl: String, ship: String) {
        if (this.shipUrl == shipUrl && this.ship == ship) return
        detach()
        this.shipUrl = shipUrl
        this.ship = ship
        api = ArmillaryApi(http, shipUrl)
        current = this
        // Read in full just now: coming to the front at once reads nothing again.
        resumedAtMs = now()
        scope.launch { refresh() }
        // The vendor's picks reach the ship within five minutes, and the
        // row took them only on a refresh: once a process, or a tap. A
        // phone's process lives for days and kept the old models. This
        // reads the ship's own config, so it costs the vendor nothing. Only
        // while it is any use: with no Armillary on the ship, no row on
        // this device, or the app behind, a read is the ship's cost for
        // nobody, every ten minutes for the life of the process.
        ticking = scope.launch {
            while (isActive) {
                delay(picksEveryMs)
                if (inFront && _availability.value == ArmillaryAvailability.PRESENT && aiSettings?.state?.value?.profile()?.armillary() != null) readPicks()
            }
        }
        // The ship already buys from a vendor, set up on some device: this
        // one connects too, with a key of its own, since the row never
        // travels. Pay on the phone and the desktop runs. Not before:
        // nothing reaches a vendor until the owner asks to buy.
        adopting = aiSettings?.let { ai ->
            scope.launch {
                combine(_availability, _account, _deletion, ai.state) { here, acct, gone, cfg ->
                    here == ArmillaryAvailability.PRESENT && gone == null && acct?.vendor?.isNotBlank() == true && cfg.profile().armillary() == null
                }.distinctUntilChanged().collect { if (it) adopt(ai) }
            }
        }
    }

    fun detach() {
        if (current === this) current = null
        watching?.cancel()
        watching = null
        adopting?.cancel()
        adopting = null
        ticking?.cancel()
        ticking = null
        retrying?.cancel()
        retrying = null
        resumedAtMs = 0L
        api = null
        shipUrl = null
        ship = null
        _availability.value = ArmillaryAvailability.UNKNOWN
        _error.value = null
        _account.value = null
        _plans.value = emptyList()
        catalog = null
        _inference.value = null
        _refreshing.value = false
        _payment.value = null
        _deletion.value = null
        _settingUp.value = false
        _keyProblem.value = null
    }

    /**
     * Everything the screen shows, read again in one pass: whether the
     * app is there, the inference config, the account, the plans and
     * the catalog. Each read stands alone, so a route this ship's desk
     * is too old to have leaves its flow as it was.
     */
    suspend fun refresh(fresh: Boolean = false): Result<Unit> = lock.withLock {
        val a = api ?: return Result.failure(IllegalStateException("Not attached to a ship."))
        _refreshing.value = true
        try {
            runSuspendCatching { a.probe() }
                .onSuccess { _availability.value = it; _error.value = null }
                .onFailure {
                    _availability.value = ArmillaryAvailability.UNKNOWN
                    // Said on the card, which only said "Checking your ship".
                    _error.value = checkProblemLine(it)
                    retryCheck()
                }
            if (_availability.value != ArmillaryAvailability.PRESENT) {
                return Result.failure(IllegalStateException(_error.value ?: "Armillary does not answer on this ship."))
            }
            var got: Inference? = null
            runSuspendCatching { a.inference() }
                .onSuccess { if (it is InferenceAnswer.Have) got = it.inference else if (it is InferenceAnswer.NoKey) dropKey() }
                .onFailure {
                    Log.i(TAG, "inference skipped: ${it.message}")
                    if (_inference.value == null) _keyProblem.value = keyProblemLine(it)
                }
            runSuspendCatching { a.account(fresh) }
                .onSuccess { _account.value = it; settle(it) }
                .onFailure { Log.i(TAG, "account skipped: ${it.message}") }
            runSuspendCatching { a.plans() }
                .onSuccess { _plans.value = it }
                .onFailure { Log.i(TAG, "plans skipped: ${it.message}") }
            runSuspendCatching { a.catalog() }
                .onSuccess { catalog = it }
                .onFailure { Log.i(TAG, "catalog skipped: ${it.message}") }
            // The catalog is what says which models are ZDR, and it is
            // read last, so the row is written once everything is in.
            got?.let(::took) ?: _inference.value?.let(::publish)
            return Result.success(Unit)
        } finally {
            _refreshing.value = false
        }
    }

    /** The vendor's picks, the key and the models read off the ship and put on the row; a failed read keeps what the row had. */
    private suspend fun readPicks() = lock.withLock {
        val a = api ?: return@withLock
        runSuspendCatching { a.inference() }
            .onSuccess { if (it is InferenceAnswer.Have) took(it.inference) else if (it is InferenceAnswer.NoKey) dropKey() }
            .onFailure { Log.i(TAG, "picks skipped: ${it.message}") }
    }

    /**
     * A check that failed, tried again further apart each time and never
     * in step with other clients, until the ship answers: the card said
     * "Checking your ship" all session after one 502, and nothing adopted.
     */
    private fun retryCheck() {
        if (retrying?.isActive == true || api == null) return
        retrying = scope.launch {
            var failures = 0
            while (_availability.value == ArmillaryAvailability.UNKNOWN && _error.value != null) {
                delay(io.nisfeb.talon.urbit.jittered(io.nisfeb.talon.orrery.probeRetryMs(++failures, probeRetryFirstMs)))
                refresh()
            }
        }
    }

    /**
     * The ship says it holds no key for this device: a vendor changed, or
     * the key revoked. The row kept the old key and the card said
     * Connected; now the key goes from the row, and the card offers to
     * set this device up. A read that failed says nothing of the kind,
     * and keeps what the row had.
     */
    private fun dropKey() {
        _inference.value = null
        val ai = aiSettings ?: return
        val profile = ai.state.value.savedProfile ?: return
        val row = profile.provider(ARMILLARY_PROVIDER)?.takeIf { it.apiKey.isNotBlank() } ?: return
        ai.setProfile(profile.copy(providers = profile.providers.map { if (it.id == ARMILLARY_PROVIDER) row.copy(apiKey = "") else it }))
    }

    /**
     * The app is in front again: the vendor's picks and the account read
     * now, so a change made while it was away, a payment on another
     * device among them, shows at once. At most once a minute: a window's
     * focus comes back each time a menu or dialog closes. The full
     * [refresh], whose catalog read reaches the vendor, keeps its own
     * triggers.
     */
    fun resumed() {
        inFront = true
        val at = now()
        if (api == null || at - resumedAtMs < RESUME_GAP_MS) return
        resumedAtMs = at
        scope.launch {
            readPicks()
            lock.withLock {
                val a = api ?: return@withLock
                runSuspendCatching { a.account() }
                    .onSuccess { _account.value = it; settle(it) }
                    .onFailure { Log.i(TAG, "account skipped: ${it.message}") }
            }
        }
    }

    /** The app went behind: the picks are not read again until it is back ([resumed] reads them then). */
    fun paused() {
        inFront = false
    }

    /**
     * The inference config, asking the vendor for a key where this ship
     * holds none. A ship with no vendor yet is pointed at the default
     * first, since a key can only come from a vendor. A lease is asked
     * for first, so a vendor that offers one is taken up on it; a
     * vendor without leases says so and the proxy key is minted instead.
     */
    suspend fun ensureKey(deviceName: String): Result<Inference> = askForKey(deviceName)
        .onFailure { _keyProblem.value = keyProblemLine(it) }

    /**
     * [ensureKey] on the repo's scope, for the card: leaving the screen
     * does not drop the wait, and a failure stays on [keyProblem]. A
     * second tap while one waits does nothing.
     */
    fun setUpDevice(deviceName: String) {
        if (!_settingUp.compareAndSet(false, true)) return
        scope.launch {
            try { ensureKey(deviceName) } finally { _settingUp.value = false }
        }
    }

    private suspend fun askForKey(deviceName: String): Result<Inference> = runSuspendCatching {
        val a = api ?: error("Not attached to a ship.")
        _deletion.value = null
        when (val first = a.inference()) {
            is InferenceAnswer.Have -> return@runSuspendCatching took(first.inference)
            InferenceAnswer.Missing -> error("Armillary is not on this ship. Install it from the Grubbery shell on your ship.")
            InferenceAnswer.NoKey -> Unit
        }
        ensureVendor(a)
        runSuspendCatching { a.lease() }.onFailure { Log.i(TAG, "lease skipped: ${it.message}") }
        a.mintKey("Talon on $deviceName")
        var waited = 0L
        while (true) {
            val next = a.inference()
            if (next is InferenceAnswer.Have) return@runSuspendCatching took(next.inference)
            if (waited >= KEY_WAIT_MS) break
            delay(KEY_POLL_MS)
            waited += KEY_POLL_MS
        }
        error("Your ship has asked the vendor for a key and is still waiting. Try Refresh in a moment.")
    }

    /**
     * The inference config read again, for Brave Leo's setup. A read and
     * nothing more: unlike [ensureKey] it never asks for a key, and a
     * failure says what failed instead of leaving the last answer.
     */
    suspend fun readInference(): Result<Inference> = runSuspendCatching {
        val a = api ?: error("Not attached to a ship.")
        when (val got = a.inference()) {
            is InferenceAnswer.Have -> took(got.inference)
            InferenceAnswer.NoKey -> error("Your ship holds no Armillary key for this device yet.")
            InferenceAnswer.Missing -> error("Armillary is not on your ship.")
        }
    }

    /** The vendor as the ship has it, set to [DEFAULT_VENDOR] where it had none. */
    private suspend fun ensureVendor(a: ArmillaryApi) {
        val acct = a.account()
        if (acct.vendor.isNotBlank()) {
            _account.value = acct
            return
        }
        a.setVendor(DEFAULT_VENDOR)
        runSuspendCatching { a.account() }.onSuccess { _account.value = it }
    }

    /**
     * Buy from another ship. Any ship running armillary is a vendor,
     * this ship included. The ship says hello to it, and the key is
     * asked for again, since a key belongs to one vendor.
     */
    suspend fun setVendor(ship: String, deviceName: String): Result<Inference> = scope.async { runSuspendCatching {
        val a = api ?: error("Not attached to a ship.")
        a.setVendor(ship.trim())
        refresh()
        ensureKey(deviceName).getOrThrow()
    } }.await()

    /**
     * Open a checkout and answer the URL to send the person to, or null
     * where the vendor made it and its answer has not come back. The
     * checkout is then watched, for a couple of minutes on a card and a
     * quarter of an hour on bitcoin, which settles after a block, so the
     * payment shows once made without anyone having to tap Refresh, and
     * the card offers its page ([Payment.url], or the vendor's row once it
     * comes). On this repo's scope: a screen left while the vendor answers
     * does not lose the checkout it made.
     */
    suspend fun topUp(rail: String, plan: String?, amountMicro: Long?): Result<String?> = scope.async { runSuspendCatching {
        val a = api ?: error("Not attached to a ship.")
        val first = a.checkout(rail, plan, amountMicro)
        val answer = if (first is CheckoutAnswer.Pending) followCheckout(a, first.nonce) else first
        val start = _account.value?.takeIf { it.hasView }?.balanceMicro ?: 0L
        when (answer) {
            is CheckoutAnswer.Url -> {
                watchBalance(Payment(answer.nonce, rail, start, Payment.Phase.WAITING, url = answer.url))
                answer.url
            }
            // Watched as any other: the card says not to order again, and
            // offers the page once the vendor's row brings it. It used to
            // say the checkout would appear "under your checkouts", which
            // no screen shows.
            is CheckoutAnswer.Pending -> {
                watchBalance(Payment(answer.nonce, rail, start, Payment.Phase.WAITING))
                null
            }
            is CheckoutAnswer.Refused -> error(answer.reason)
        }
    } }.await()

    /**
     * A checkout the ship answered 202 for. The order went out, and the
     * vendor made the checkout, but its answer had not come back inside
     * the ship's thirty seconds, which from a comet it often does not.
     * Ordering again only makes another checkout nobody is sent to, so
     * ask the ship how far this one has got, nudging it to look again,
     * until the vendor's row arrives or [CHECKOUT_FOLLOW_MS] runs out.
     * A desk older than the phase route stays Pending.
     */
    private suspend fun followCheckout(a: ArmillaryApi, nonce: String): CheckoutAnswer {
        var waited = 0L
        while (waited < CHECKOUT_FOLLOW_MS) {
            delay(CHECKOUT_POLL_MS)
            waited += CHECKOUT_POLL_MS
            val p = try { a.checkoutPhase(nonce) } catch (e: ArmillaryError) { continue } ?: break
            if (p.phase != "answered") continue
            return when {
                p.status == "refused" -> CheckoutAnswer.Refused(p.note.ifBlank { "The vendor refused the checkout." })
                p.url.isNotBlank() -> CheckoutAnswer.Url(p.url, nonce)
                else -> CheckoutAnswer.Refused("The vendor answered the checkout as ${p.status}.")
            }
        }
        return CheckoutAnswer.Pending(nonce)
    }

    /** Ask the vendor to stop the subscription renewing. The view says when it is done. */
    suspend fun cancelSubscription(): Result<Unit> = scope.async { runSuspendCatching {
        val a = api ?: error("Not attached to a ship.")
        a.cancelSubscription()
        watchBalance(null)
    } }.await()

    /**
     * Delete this ship's account at its vendor, on the repo's scope, so
     * leaving the screen does not stop it half done. Once the ship has
     * taken it, this device drops the Armillary row and every model on
     * it. The profile travels: left pointing at Armillary, another device
     * would adopt the row and ask for a key, and the vendor opens an
     * account for any ship that asks it anything.
     */
    suspend fun deleteAccount(): Result<DeleteAnswer> = scope.async {
        // Under the refresh's lock: a refresh in flight finishing after
        // forget() put the deleted account back on the screen.
        lock.withLock { runSuspendCatching {
            val a = api ?: error("Not attached to a ship.")
            val vendor = _account.value?.vendor.orEmpty()
            val answer = a.deleteAccount()
            if (answer.taken) forget(Deletion(vendor, answer))
            answer
        } }
    }.await()

    private fun forget(d: Deletion) {
        watching?.cancel()
        watching = null
        _payment.value = null
        _account.value = null
        _inference.value = null
        _plans.value = emptyList()
        catalog = null
        aiSettings?.let { ai -> ai.state.value.savedProfile?.let { ai.setProfile(it.without(ARMILLARY_PROVIDER)) } }
        // Last: whoever sees the deletion finds the provider already gone.
        _deletion.value = d
    }

    /**
     * The balance, read from the vendor every few seconds. A payment
     * lands on the vendor while the person is still in their browser,
     * so the number here catches up by itself. With a [payment] the
     * watch also says what became of it, and gives up as unseen when
     * its rail's wait runs out.
     */
    private fun watchBalance(payment: Payment?) {
        watching?.cancel()
        _payment.value = payment
        val limit = if (payment?.rail == BTC_RAIL) BTC_WATCH_MS else WATCH_MS
        watching = scope.launch {
            var waited = 0L
            while (isActive && waited < limit) {
                val every = watchEvery(waited)
                delay(every)
                waited += every
                val a = api ?: return@launch
                val acct = runSuspendCatching { a.account(fresh = true) }
                    .onFailure { Log.i(TAG, "balance watch: ${it.message}") }
                    .getOrNull() ?: continue
                _account.value = acct
                if (settle(acct)) return@launch
            }
            _payment.value = _payment.value?.takeIf { it.phase == Payment.Phase.WAITING }?.copy(phase = Payment.Phase.UNSEEN)
        }
    }

    /**
     * What an account just read says about the payment in flight. True
     * once it has said something final: paid, or the row says the
     * checkout failed, expired or was refused. Where the view carries the
     * checkout's row, the row says whether it was paid and how much: the
     * balance was measured from one read before the checkout opened, so
     * spending since made a $5 payment $4.97, and a credit from elsewhere
     * read as paid before anyone had. With no row, the balance rising.
     */
    private fun settle(acct: Account): Boolean {
        val p = _payment.value ?: return false
        if (p.phase != Payment.Phase.WAITING && p.phase != Payment.Phase.UNSEEN) return false
        val row = acct.checkouts.firstOrNull { p.nonce.isNotBlank() && it.nonce == p.nonce }
        val rose = if (acct.hasView) acct.balanceMicro - p.startMicro else 0L
        if (row?.status == "paid" || (row == null && rose > 0)) {
            val added = row?.amountMicro?.takeIf { it > 0 } ?: rose.coerceAtLeast(0L)
            val paid = p.copy(phase = Payment.Phase.PAID, addedMicro = added)
            _payment.value = paid
            notifier.notify("Armillary", money(added) + " added", "armillary")
            noticeRepo?.record(
                "armillary", io.nisfeb.talon.notify.NoticeRepo.DOMAIN_ACCOUNT, "armillary-credit",
                "Armillary", money(added) + " added", "armillary",
            )
            scope.launch { afterPayment() }
            scope.launch {
                delay(PAID_SHOWN_MS)
                if (_payment.value === paid) _payment.value = null
            }
            return true
        }
        if (row != null && row.status in ENDED_STATUSES) {
            _payment.value = p.copy(phase = Payment.Phase.ENDED)
            return true
        }
        return false
    }

    /**
     * A payment landed: this device's key, and every feature it pays for
     * on. The payment is the owner's yes (sneagan, 2026-10-16: "paying a
     * few bucks ... should just turn everything on"); the top-up sheet
     * says what the features send.
     */
    private suspend fun afterPayment() {
        val ai = aiSettings ?: return
        ensureKey(platformLabel).onFailure { Log.i(TAG, "no key after the payment: ${it.message}") }
        val p = ai.state.value.savedProfile ?: return
        p.allOn().takeIf { it != p }?.let(ai::setProfile)
    }

    /** This device's own Armillary row and key, for a ship that already buys: what "Set up Armillary" does. */
    private suspend fun adopt(ai: AiSettingsRepository) {
        val p = ai.state.value.profile().takeIf { it.armillary() == null } ?: return
        ai.setProfile(p.copy(providers = p.providers + AiProvider(ARMILLARY_PROVIDER, ProviderKind.Armillary, ProviderKind.Armillary.label)))
        ensureKey(platformLabel).onFailure { Log.i(TAG, "no key yet for the adopted row: ${it.message}") }
    }

    /** An inference config just read: kept, and written onto the provider row. */
    private fun took(inf: Inference): Inference {
        // The row first: whatever waits for the inference reads the key
        // off the row, and found it blank when it woke in between.
        publish(inf)
        _inference.value = inf
        _keyProblem.value = null
        return inf
    }

    /**
     * The base, the key, the models, the vendor's picks and its search
     * onto the `armillary` provider row, and nothing else on the profile. The row is only written
     * where the owner has added it: a ship that sells inference to a
     * person who has not asked Talon to buy any is left alone.
     */
    private fun publish(inf: Inference) {
        val ai = aiSettings ?: return
        val profile: AiProfile = ai.state.value.savedProfile ?: return
        val row = profile.provider(ARMILLARY_PROVIDER) ?: return
        // A catalog the ship has not sent keeps the marks the row had:
        // taken as empty, every model lost its ZDR badge and the rows
        // that read messages warned they may be kept.
        val zdr = catalog?.filter { it.zdr }?.map { it.id }?.toSet()
            ?: row.models.filter { it.zdr }.map { it.id }.toSet()
        // What a payment learnt of tool use and context stays: the ship
        // says neither, and a refresh used to wipe them.
        // Chat models only: a transcription model in a chat picker fails every message.
        val models = io.nisfeb.talon.ai.chatModelsOf(inf.models, inf.suggested).map { id ->
            val had = row.models.firstOrNull { it.id == id }
            ModelInfo(id = id, name = id, zdr = id in zdr, tools = had?.tools, contextLength = had?.contextLength)
        }
        val local = selfVendorOnLocalhost(inf.baseUrl)
        _noPublicUrl.value = local
        val base = reachable(inf.baseUrl, local).trim().trimEnd('/').ifBlank { null }
        val searchUrl = reachable(inf.searchUrl, local).trim().trimEnd('/').ifBlank { null }
        val next = row.copy(baseUrl = base, apiKey = inf.key, models = models, suggested = inf.suggested, suggestedTiers = inf.suggestedTiers, searchUrl = searchUrl, searchKey = inf.searchKey)
        val updated = profile.copy(providers = profile.providers.map { if (it.id == ARMILLARY_PROVIDER) next else it })
        if (updated == profile) return
        ai.setProfile(updated)
    }

    /** The ship is its own vendor and answered a base on its own loopback ([noPublicUrl]). */
    private fun selfVendorOnLocalhost(base: String): Boolean {
        if (!base.trim().startsWith(LOOPBACK_BASE)) return false
        val vendor = _account.value?.vendor?.trim()?.removePrefix("~") ?: return false
        return vendor.isNotBlank() && vendor == ship?.trim()?.removePrefix("~")
    }

    /** [url] on the ship address this device signed in with, where the ship answered its loopback. */
    private fun reachable(url: String, local: Boolean): String {
        val here = shipUrl?.trim()?.trimEnd('/')
        return if (local && here != null && url.trim().startsWith(LOOPBACK_BASE)) here + url.trim().removePrefix(LOOPBACK_BASE) else url
    }

    companion object {
        private const val TAG = "ArmillaryRepo"

        /** What a ship with no public_url answers as its base (armillary 33). */
        private const val LOOPBACK_BASE = "http://localhost:8080"

        /** OpenRouter's public list, which says what each model can do; no key is sent. */

        /** The vendor a ship buys from until its owner names another. */
        const val DEFAULT_VENDOR = "~nisfeb"

        /** How often the vendor's picks are read off the ship: twice its own five-minute peek. */
        const val PICKS_EVERY_MS = 10 * 60_000L

        /** The least time between two reads for the app coming back to the front. */
        const val RESUME_GAP_MS = 60_000L

        /** How long to wait for a minted key, and how often to ask. */
        const val KEY_WAIT_MS = 30_000L
        const val KEY_POLL_MS = 2_000L

        /**
         * How long to follow a checkout past the ship's own thirty
         * seconds, and how often to ask: the armillary page's cadence.
         */
        const val CHECKOUT_FOLLOW_MS = 90_000L
        const val CHECKOUT_POLL_MS = 3_000L

        /** How long the balance is watched after a checkout opens, and how often. */
        const val WATCH_MS = 120_000L
        const val WATCH_EVERY_MS = 5_000L

        /** A bitcoin payment settles after a block, so its watch is longer. */
        const val BTC_WATCH_MS = 900_000L
        /** Past the first two minutes (a bitcoin payment's block is minutes off). */
        const val BTC_WATCH_LATER_EVERY_MS = 30_000L

        /**
         * Every 5 s while a card payment could land, then every 30 s: a
         * 15-minute bitcoin watch at 5 s was 180 fresh reads, each one a
         * call from the ship to the vendor.
         */
        fun watchEvery(waitedMs: Long): Long =
            if (waitedMs < WATCH_MS) WATCH_EVERY_MS else BTC_WATCH_LATER_EVERY_MS
        const val BTC_RAIL = "btcpay"

        /** How long "Paid" stays on the card before the plain balance comes back. */
        const val PAID_SHOWN_MS = 10_000L

        /** A checkout row in one of these states is over, whatever the balance does. */
        val ENDED_STATUSES = setOf("failed", "expired", "refused", "cancelled")

        /** The attached repo, for the places that hold none: the model catalog's test button. */
        @kotlin.concurrent.Volatile
        private var current: ArmillaryRepo? = null

        fun attached(): ArmillaryRepo? = current
    }
}

/**
 * A checkout this session opened, from the moment its URL opened until
 * the card has nothing more to say about it.
 */
data class Payment(
    /** The row's nonce in the account view, which is how its status is found. */
    val nonce: String,
    /** `stripe` or `btcpay`. */
    val rail: String,
    /** The balance when the checkout opened; anything above it is the payment. */
    val startMicro: Long,
    val phase: Phase,
    /** How much the balance rose, once it has. */
    val addedMicro: Long = 0L,
    /** The checkout's page, as the vendor answered it; blank until it has. */
    val url: String = "",
) {
    enum class Phase {
        /** The watch is running and nothing has landed. */
        WAITING,

        /** The balance rose. Shown for a few seconds. */
        PAID,

        /** The watch ran out with no change. */
        UNSEEN,

        /** The row says the checkout failed, expired or was refused. */
        ENDED,
    }
}

/** An account deletion the ship took: from which vendor, and how it answered. */
data class Deletion(val vendor: String, val answer: DeleteAnswer)

/** A failed check for Armillary, said plainly: no status codes and no error pages. */
internal fun checkProblemLine(e: Throwable): String = when (e) {
    is ArmillaryError.Refused -> io.nisfeb.talon.util.readableReason(e.reason)
        ?.let { "Armillary on your ship could not answer: $it." } ?: "Armillary on your ship could not answer."
    is ArmillaryError.Garbled -> "Your ship's answer about Armillary could not be read."
    else -> "Your ship did not answer."
} + " Talon asks again by itself."

/** A failed key ask, said plainly: no HTTP codes or exception names on the card. */
internal fun keyProblemLine(e: Throwable): String = when (e) {
    is ArmillaryError.Refused -> "Your ship refused: ${e.reason.ifBlank { "it gave no reason" }}."
    is ArmillaryError.Unreachable -> "Your ship did not answer. Try Refresh in a moment."
    is ArmillaryError.Garbled -> "Your ship's answer could not be read."
    else -> e.message ?: "Your ship did not answer. Try Refresh in a moment."
}
