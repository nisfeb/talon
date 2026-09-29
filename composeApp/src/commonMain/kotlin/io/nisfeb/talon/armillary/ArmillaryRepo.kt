package io.nisfeb.talon.armillary

import io.ktor.client.HttpClient
import io.nisfeb.talon.ai.ARMILLARY_PROVIDER
import io.nisfeb.talon.ai.AiFeature
import io.nisfeb.talon.ai.AiProfile
import io.nisfeb.talon.ai.AiProvider
import io.nisfeb.talon.ai.AiSettingsRepository
import io.nisfeb.talon.ai.FeatureSetting
import io.nisfeb.talon.ai.ModelCatalog
import io.nisfeb.talon.ai.ModelInfo
import io.nisfeb.talon.ai.ModelRef
import io.nisfeb.talon.ai.ProviderKind
import io.nisfeb.talon.ai.armillaryBest
import io.nisfeb.talon.ai.filledWithArmillary
import io.nisfeb.talon.ai.shipBase
import io.nisfeb.talon.ai.wantsArmillaryRow
import io.nisfeb.talon.ai.without
import io.nisfeb.talon.orrery.OrreryRepo
import io.nisfeb.talon.ui.isAssistantSupported
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
    /** Where a model's tool use is read, the vendor's list not saying. A test hands in its own. */
    openRouter: ModelCatalog? = null,
    /** The Orrery pipe, whose generator a payment may point at Armillary. */
    private val orrery: () -> OrreryRepo? = { OrreryRepo.attached() },
) {
    private val models by lazy { openRouter ?: ModelCatalog() }
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

    private val _offer = MutableStateFlow<String?>(null)

    /**
     * After a payment, the model catch-up and the assistant are ready on,
     * while either is off. They read messages, so they are turned on by
     * the owner's own tap ([acceptOffer]), never by the payment.
     */
    val offer: StateFlow<String?> = _offer.asStateFlow()

    private val _deletion = MutableStateFlow<Deletion?>(null)

    /**
     * The account deletion the ship took this session. The card goes
     * with the row, so the screen says what happened from this instead.
     */
    val deletion: StateFlow<Deletion?> = _deletion.asStateFlow()

    private var api: ArmillaryApi? = null
    private var shipUrl: String? = null
    private var ship: String? = null
    private var watching: Job? = null
    private var adopting: Job? = null

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
        scope.launch { refresh() }
        // The owner's profile, from another device, may run on Armillary:
        // the row never travels, so this device makes its own.
        adopting = aiSettings?.let { ai ->
            scope.launch {
                combine(_availability, ai.state) { here, cfg ->
                    here == ArmillaryAvailability.PRESENT && cfg.savedProfile?.wantsArmillaryRow() == true
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
        _offer.value = null
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
                .onFailure { _availability.value = ArmillaryAvailability.UNKNOWN; _error.value = it.message }
            if (_availability.value != ArmillaryAvailability.PRESENT) {
                return Result.failure(IllegalStateException(_error.value ?: "Armillary does not answer on this ship."))
            }
            runSuspendCatching { a.inference() }
                .onSuccess { if (it is InferenceAnswer.Have) _inference.value = it.inference }
                .onFailure { Log.i(TAG, "inference skipped: ${it.message}") }
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
            _inference.value?.let(::publish)
            return Result.success(Unit)
        } finally {
            _refreshing.value = false
        }
    }

    /**
     * The inference config, asking the vendor for a key where this ship
     * holds none. A ship with no vendor yet is pointed at the default
     * first, since a key can only come from a vendor. A lease is asked
     * for first, so a vendor that offers one is taken up on it; a
     * vendor without leases says so and the proxy key is minted instead.
     */
    suspend fun ensureKey(deviceName: String): Result<Inference> = runSuspendCatching {
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
    suspend fun setVendor(ship: String, deviceName: String): Result<Inference> = runSuspendCatching {
        val a = api ?: error("Not attached to a ship.")
        a.setVendor(ship.trim())
        refresh()
        ensureKey(deviceName).getOrThrow()
    }

    /**
     * Open a checkout and answer the URL to send the person to. The
     * balance is then watched, for a couple of minutes on a card and a
     * quarter of an hour on bitcoin, which settles after a block, so
     * the payment shows once made without anyone having to tap Refresh.
     */
    suspend fun topUp(rail: String, plan: String?, amountMicro: Long?): Result<String> = runSuspendCatching {
        val a = api ?: error("Not attached to a ship.")
        when (val answer = a.checkout(rail, plan, amountMicro)) {
            is CheckoutAnswer.Url -> {
                val start = _account.value?.takeIf { it.hasView }?.balanceMicro ?: 0L
                watchBalance(Payment(answer.nonce, rail, start, Payment.Phase.WAITING))
                answer.url
            }
            is CheckoutAnswer.Pending ->
                error("Your ship has asked the vendor and is still waiting. The checkout is queued: try again in a moment.")
            is CheckoutAnswer.Refused -> error(answer.reason)
        }
    }

    /** Ask the vendor to stop the subscription renewing. The view says when it is done. */
    suspend fun cancelSubscription(): Result<Unit> = runSuspendCatching {
        val a = api ?: error("Not attached to a ship.")
        a.cancelSubscription()
        watchBalance(null)
    }

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
        _offer.value = null
        _account.value = null
        _inference.value = null
        _plans.value = emptyList()
        catalog = null
        _deletion.value = d
        val ai = aiSettings ?: return
        ai.state.value.savedProfile?.let { ai.setProfile(it.without(ARMILLARY_PROVIDER)) }
    }

    /** Give a direct provider lease back, before the provider row goes. */
    suspend fun dropLease(): Result<Unit> = runSuspendCatching {
        val a = api ?: error("Not attached to a ship.")
        a.dropLease()
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
                delay(WATCH_EVERY_MS)
                waited += WATCH_EVERY_MS
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
     * once it has said something final: the balance rose, or the row
     * says the checkout failed, expired or was refused.
     */
    private fun settle(acct: Account): Boolean {
        val p = _payment.value ?: return false
        if (p.phase != Payment.Phase.WAITING && p.phase != Payment.Phase.UNSEEN) return false
        if (acct.hasView && acct.balanceMicro > p.startMicro) {
            val added = acct.balanceMicro - p.startMicro
            val paid = p.copy(phase = Payment.Phase.PAID, addedMicro = added)
            _payment.value = paid
            notifier.notify("Armillary", money(added) + " added", "armillary")
            scope.launch { afterPayment() }
            scope.launch {
                delay(PAID_SHOWN_MS)
                if (_payment.value === paid) _payment.value = null
            }
            return true
        }
        val row = acct.checkouts.firstOrNull { it.nonce == p.nonce } ?: return false
        if (row.status in ENDED_STATUSES) {
            _payment.value = p.copy(phase = Payment.Phase.ENDED)
            return true
        }
        return false
    }

    /**
     * A payment landed: the AI settings made to work on what was bought
     * (the owner's decisions, 2026-09-28). What does not work is given
     * the strongest model the vendor sells, and what works stays
     * ([filledWithArmillary]). Catch-up and the assistant are readied, not
     * turned on ([offer]). With Orrery on and the ship's generator unable
     * to run, it is pointed at Armillary too.
     */
    private suspend fun afterPayment() {
        val ai = aiSettings ?: return
        ensureKey(platformLabel).onFailure { Log.i(TAG, "no key after the payment: ${it.message}"); return }
        if (catalog == null) api?.let { a -> runSuspendCatching { a.catalog() }.onSuccess { catalog = it } }
        val profile = ai.state.value.savedProfile ?: return
        val row = profile.provider(ARMILLARY_PROVIDER) ?: return
        // Whether a model uses tools, which the vendor's list does not
        // say and OpenRouter's does: a lease runs on OpenRouter's own ids.
        val known = runSuspendCatching { models.fetch(OPENROUTER_ROW).models }
            .onFailure { Log.i(TAG, "tool use not read: ${it.message}") }
            .getOrDefault(emptyList()).associateBy { it.id }
        val listed = row.models.map { m ->
            known[m.id]?.let { k -> m.copy(tools = m.tools ?: k.tools, contextLength = m.contextLength ?: k.contextLength) } ?: m
        }
        val price = catalog.orEmpty().associate { it.id to it.outMicro }
        val best = armillaryBest(listed) { price[it] ?: 0L } ?: return
        val next = profile.copy(providers = profile.providers.map { if (it.id == ARMILLARY_PROVIDER) it.copy(models = listed) else it })
            .filledWithArmillary(best)
        if (next != profile) ai.setProfile(next)
        pointGenerator(ai, best)
        _offer.value = best.takeIf { !next.isOn(AiFeature.CatchUp) || (isAssistantSupported && !next.isOn(AiFeature.Assistant)) }
    }

    /**
     * Orrery's generator on [best], where Orrery is on and the generator
     * has no key or no model to run (the owner's decision, 2026-09-28).
     * One that runs is the owner's and is left alone. The ship's chat and
     * mail readers borrow its key, so they start reading what the Orrery
     * page picked.
     */
    private suspend fun pointGenerator(ai: AiSettingsRepository, best: String) {
        val profile = ai.state.value.savedProfile ?: return
        if (profile.orrery != true) return
        val o = orrery() ?: return
        o.loadGenerator()
        val g = o.generatorSettings.value ?: return
        if (g.keySet && !g.model.isNullOrBlank()) return
        val row = profile.provider(ARMILLARY_PROVIDER) ?: return
        val base = row.shipBase() ?: return
        o.setGenerator(true, base, best, row.apiKey)
            .onSuccess {
                val now = ai.state.value.savedProfile ?: return@onSuccess
                val gen = (now.features[AiFeature.OrreryGenerator] ?: FeatureSetting()).copy(model = ModelRef(ARMILLARY_PROVIDER, best))
                ai.setProfile(now.copy(features = now.features + (AiFeature.OrreryGenerator to gen)))
            }
            .onFailure { Log.i(TAG, "generator not pointed: ${it.message}") }
    }

    /** Catch-up and, where there is one, the assistant turned on, as offered. */
    fun acceptOffer() {
        _offer.value = null
        val ai = aiSettings ?: return
        val p = ai.state.value.savedProfile ?: return
        val on = listOfNotNull(AiFeature.CatchUp, AiFeature.Assistant.takeIf { isAssistantSupported })
        ai.setProfile(p.copy(features = p.features + on.associateWith { (p.features[it] ?: FeatureSetting()).copy(on = true) }))
    }

    fun declineOffer() {
        _offer.value = null
    }

    /**
     * This device's own Armillary row, and its own key, for a profile
     * that runs on Armillary: what adding it by hand does. Without it the
     * default named a provider this device did not have, and nothing ran.
     */
    private suspend fun adopt(ai: AiSettingsRepository) {
        val p = ai.state.value.savedProfile?.takeIf { it.wantsArmillaryRow() } ?: return
        ai.setProfile(p.copy(providers = p.providers + AiProvider(ARMILLARY_PROVIDER, ProviderKind.Armillary, ProviderKind.Armillary.label)))
        ensureKey(platformLabel).onFailure { Log.i(TAG, "no key yet for the adopted row: ${it.message}") }
    }

    /** An inference config just read: kept, and written onto the provider row. */
    private fun took(inf: Inference): Inference {
        _inference.value = inf
        publish(inf)
        return inf
    }

    /**
     * The base, the key and the models onto the `armillary` provider
     * row, and nothing else on the profile. The row is only written
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
        val models = inf.models.map { id ->
            val had = row.models.firstOrNull { it.id == id }
            ModelInfo(id = id, name = id, zdr = id in zdr, tools = had?.tools, contextLength = had?.contextLength)
        }
        val base = inf.baseUrl.trim().trimEnd('/').ifBlank { null }
        if (row.baseUrl == base && row.apiKey == inf.key && row.models == models) return
        ai.setProfile(
            profile.copy(
                providers = profile.providers.map {
                    if (it.id == ARMILLARY_PROVIDER) it.copy(baseUrl = base, apiKey = inf.key, models = models) else it
                },
            ),
        )
    }

    companion object {
        private const val TAG = "ArmillaryRepo"

        /** OpenRouter's public list, which says what each model can do; no key is sent. */
        private val OPENROUTER_ROW = AiProvider("openrouter", ProviderKind.OpenRouter, "OpenRouter")

        /** The vendor a ship buys from until its owner names another. */
        const val DEFAULT_VENDOR = "~nisfeb"

        /** How long to wait for a minted key, and how often to ask. */
        const val KEY_WAIT_MS = 30_000L
        const val KEY_POLL_MS = 2_000L

        /** How long the balance is watched after a checkout opens, and how often. */
        const val WATCH_MS = 120_000L
        const val WATCH_EVERY_MS = 5_000L

        /** A bitcoin payment settles after a block, so its watch is longer. */
        const val BTC_WATCH_MS = 900_000L
        const val BTC_RAIL = "btcpay"

        /** How long "Paid" stays on the card before the plain balance comes back. */
        const val PAID_SHOWN_MS = 10_000L

        /** A checkout row in one of these states is over, whatever the balance does. */
        val ENDED_STATUSES = setOf("failed", "expired", "refused")

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
