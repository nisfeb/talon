package io.nisfeb.talon.armillary

import io.ktor.client.HttpClient
import io.nisfeb.talon.ai.ARMILLARY_PROVIDER
import io.nisfeb.talon.ai.AiProfile
import io.nisfeb.talon.ai.AiSettingsRepository
import io.nisfeb.talon.ai.ModelInfo
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
) {
    private val _availability = MutableStateFlow(ArmillaryAvailability.UNKNOWN)
    val availability: StateFlow<ArmillaryAvailability> = _availability.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _account = MutableStateFlow<Account?>(null)
    val account: StateFlow<Account?> = _account.asStateFlow()
    private val _plans = MutableStateFlow<List<Plan>>(emptyList())
    val plans: StateFlow<List<Plan>> = _plans.asStateFlow()
    private val _catalog = MutableStateFlow<List<CatalogRow>>(emptyList())
    val catalog: StateFlow<List<CatalogRow>> = _catalog.asStateFlow()
    private val _inference = MutableStateFlow<Inference?>(null)
    val inference: StateFlow<Inference?> = _inference.asStateFlow()
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private var api: ArmillaryApi? = null
    private var shipUrl: String? = null
    private var ship: String? = null
    private var watching: Job? = null

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
    }

    fun detach() {
        if (current === this) current = null
        watching?.cancel()
        watching = null
        api = null
        shipUrl = null
        ship = null
        _availability.value = ArmillaryAvailability.UNKNOWN
        _error.value = null
        _account.value = null
        _plans.value = emptyList()
        _catalog.value = emptyList()
        _inference.value = null
        _refreshing.value = false
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
            runCatching { a.probe() }
                .onSuccess { _availability.value = it; _error.value = null }
                .onFailure { _availability.value = ArmillaryAvailability.UNKNOWN; _error.value = it.message }
            if (_availability.value != ArmillaryAvailability.PRESENT) {
                return Result.failure(IllegalStateException(_error.value ?: "Armillary does not answer on this ship."))
            }
            runCatching { a.inference() }
                .onSuccess { if (it is InferenceAnswer.Have) _inference.value = it.inference }
                .onFailure { Log.i(TAG, "inference skipped: ${it.message}") }
            runCatching { a.account(fresh) }
                .onSuccess { _account.value = it }
                .onFailure { Log.i(TAG, "account skipped: ${it.message}") }
            runCatching { a.plans() }
                .onSuccess { _plans.value = it }
                .onFailure { Log.i(TAG, "plans skipped: ${it.message}") }
            runCatching { a.catalog() }
                .onSuccess { _catalog.value = it }
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
    suspend fun ensureKey(deviceName: String): Result<Inference> = runCatching {
        val a = api ?: error("Not attached to a ship.")
        when (val first = a.inference()) {
            is InferenceAnswer.Have -> return@runCatching took(first.inference)
            InferenceAnswer.Missing -> error("Armillary is not on this ship. Install it from the Grubbery shell on your ship.")
            InferenceAnswer.NoKey -> Unit
        }
        ensureVendor(a)
        runCatching { a.lease() }.onFailure { Log.i(TAG, "lease skipped: ${it.message}") }
        a.mintKey("Talon on $deviceName")
        var waited = 0L
        while (true) {
            val next = a.inference()
            if (next is InferenceAnswer.Have) return@runCatching took(next.inference)
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
        runCatching { a.account() }.onSuccess { _account.value = it }
    }

    /**
     * Buy from another ship. Any ship running armillary is a vendor,
     * this ship included. The ship says hello to it, and the key is
     * asked for again, since a key belongs to one vendor.
     */
    suspend fun setVendor(ship: String, deviceName: String): Result<Inference> = runCatching {
        val a = api ?: error("Not attached to a ship.")
        a.setVendor(ship.trim())
        refresh()
        ensureKey(deviceName).getOrThrow()
    }

    /**
     * Open a checkout and answer the URL to send the person to. The
     * balance is then watched for a couple of minutes, so it appears
     * once they have paid without anyone having to tap Refresh.
     */
    suspend fun topUp(rail: String, plan: String?, amountMicro: Long?): Result<String> = runCatching {
        val a = api ?: error("Not attached to a ship.")
        when (val answer = a.checkout(rail, plan, amountMicro)) {
            is CheckoutAnswer.Url -> {
                watchBalance()
                answer.url
            }
            is CheckoutAnswer.Pending ->
                error("Your ship has asked the vendor and is still waiting. The checkout is queued: try again in a moment.")
            is CheckoutAnswer.Refused -> error(answer.reason)
        }
    }

    /** A subscription plan, by card. The same checkout, on a plan rather than an amount. */
    suspend fun subscribe(planId: String, rail: String = "stripe"): Result<String> = topUp(rail, planId, null)

    /** Ask the vendor to stop the subscription renewing. The view says when it is done. */
    suspend fun cancelSubscription(): Result<Unit> = runCatching {
        val a = api ?: error("Not attached to a ship.")
        a.cancelSubscription()
        watchBalance()
    }

    /** Give a direct provider lease back, before the provider row goes. */
    suspend fun dropLease(): Result<Unit> = runCatching {
        val a = api ?: error("Not attached to a ship.")
        a.dropLease()
    }

    /**
     * The balance, read from the vendor every few seconds for a couple
     * of minutes. A payment lands on the vendor while the person is
     * still in their browser, so the number here catches up by itself.
     */
    private fun watchBalance() {
        watching?.cancel()
        watching = scope.launch {
            var waited = 0L
            while (isActive && waited < WATCH_MS) {
                delay(WATCH_EVERY_MS)
                waited += WATCH_EVERY_MS
                val a = api ?: return@launch
                runCatching { a.account(fresh = true) }
                    .onSuccess { _account.value = it }
                    .onFailure { Log.i(TAG, "balance watch: ${it.message}") }
            }
        }
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
        val zdr = _catalog.value.filter { it.zdr }.map { it.id }.toSet()
        val models = inf.models.map { ModelInfo(id = it, name = it, zdr = it in zdr) }
        val base = inf.baseUrl.trim().trimEnd('/').ifBlank { null }
        val profile: AiProfile = ai.state.value.savedProfile ?: return
        val row = profile.provider(ARMILLARY_PROVIDER) ?: return
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

        /** The vendor a ship buys from until its owner names another. */
        const val DEFAULT_VENDOR = "~nisfeb"

        /** How long to wait for a minted key, and how often to ask. */
        const val KEY_WAIT_MS = 30_000L
        const val KEY_POLL_MS = 2_000L

        /** How long the balance is watched after a checkout opens, and how often. */
        const val WATCH_MS = 120_000L
        const val WATCH_EVERY_MS = 5_000L

        /** The attached repo, for the places that hold none: the model catalog's test button. */
        @kotlin.concurrent.Volatile
        private var current: ArmillaryRepo? = null

        fun attached(): ArmillaryRepo? = current
    }
}
