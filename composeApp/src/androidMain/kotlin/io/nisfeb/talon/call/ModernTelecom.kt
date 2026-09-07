package io.nisfeb.talon.call

import android.content.Context
import android.net.Uri
import android.os.Build
import android.telecom.DisconnectCause
import androidx.core.content.edit
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallEndpointCompat
import androidx.core.telecom.CallsManager
import io.nisfeb.talon.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The Android 16.1+ side of the call log.
 *
 * From 16.1 the system logs a VoIP call only when it was added through
 * TelecomManager.addCall — which core-telecom wraps — and files it as a
 * VoIP row that dialers show once the user turns the app on under
 * Settings › Calling accounts › Integrated call logs (17) or the dialer
 * opts in (16.1). A call-back from that history arrives as
 * ACTION_CALL_BACK carrying the call's UUID, which only this path ever
 * learns (CallControlScope.callId); [CallBackTargets] keeps the UUID →
 * ship map it needs. Below 16.1 none of this logs anything, and
 * [TalonTelecom]'s own self-managed account does instead.
 *
 * Same shape as [TalonTelecom]: process-global, because the push
 * receiver registers a ring before the app is up, and the app adopts
 * that call by id when it answers.
 */
object ModernTelecom {
    /** True on Android 16.1+, where this backend is the one that logs. */
    val active: Boolean
        get() = Build.VERSION.SDK_INT >= 36 &&
            Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1

    private var manager: CallsManager? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val calls = HashMap<String, ModernCall>()
    private val waiters = HashMap<String, CompletableDeferred<ModernCall?>>()
    private val pending = HashSet<String>()
    private const val TAG = "ModernTelecom"
    private const val SCHEME = "urbit"

    fun register(context: Context): Boolean {
        manager?.let { return true }
        return runCatching {
            CallsManager(context.applicationContext).also {
                it.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE)
            }
        }.onSuccess { manager = it; TalonTelecom.note("core-telecom registered (16.1+ call log)") }
            .onFailure { TalonTelecom.note("core-telecom registration failed: ${it.message}") }
            .isSuccess
    }

    /** Ask telecom for the call. True if it is (already) on its way;
     *  the call itself arrives through [await]. [target] is what a
     *  call-back should dial: the ship, or "party" for a line. */
    fun start(context: Context, id: String, target: String, name: String, incoming: Boolean): Boolean {
        synchronized(this) {
            if (id in calls || id in pending) return true
        }
        if (!register(context)) return false
        val m = manager ?: return false
        synchronized(this) { pending.add(id) }
        TalonTelecom.note("${if (incoming) "incoming" else "outgoing"} $id: asked (16.1+)")
        val attrs = CallAttributesCompat(
            name,
            Uri.fromParts(SCHEME, target, null),
            if (incoming) CallAttributesCompat.DIRECTION_INCOMING else CallAttributesCompat.DIRECTION_OUTGOING,
            CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
            CallAttributesCompat.SUPPORTS_SET_INACTIVE,
            null,
            false, // isLogExcluded: the point of this class is the log
        )
        val app = context.applicationContext
        scope.launch {
            val ended = CompletableDeferred<Unit>()
            val added = runCatching {
                m.addCall(
                    attrs,
                    onAnswer = { TalonTelecom.hooks?.controls(id)?.onAnswer() },
                    onDisconnect = { TalonTelecom.hooks?.controls(id)?.onDisconnect() },
                    onSetActive = { TalonTelecom.hooks?.controls(id)?.onUnhold() },
                    onSetInactive = { TalonTelecom.hooks?.controls(id)?.onHold() },
                ) {
                    val call = ModernCall(id, this, ended)
                    CallBackTargets.remember(app, getCallId().uuid.toString(), target)
                    adopt(id, call)
                    if (incoming) call.armRingTimeout(CallController.DEFAULT_RING_TIMEOUT_MS)
                }
            }.onFailure {
                Log.w(TAG, "telecom would not take $id", it)
                TalonTelecom.note("$id: telecom refused it (${it.message})")
                refuse(id)
            }.isSuccess
            if (added) ended.await()
        }
        return true
    }

    suspend fun await(id: String, timeoutMs: Long = 10_000): ModernCall? {
        val waiter = synchronized(this) {
            calls[id]?.let { return it }
            waiters.getOrPut(id) { CompletableDeferred() }
        }
        return withTimeoutOrNull(timeoutMs) { waiter.await() }
    }

    fun call(id: String): ModernCall? = synchronized(this) { calls[id] }

    private fun adopt(id: String, call: ModernCall) = synchronized(this) {
        TalonTelecom.note("$id: call added (uuid ${call.uuid.take(8)}…)")
        pending.remove(id)
        calls[id] = call
        waiters.remove(id)?.complete(call)
        (TalonTelecom.hooks?.route as? ModernTelecomRoute)?.bind(call)
    }

    private fun refuse(id: String) = synchronized(this) {
        pending.remove(id)
        waiters.remove(id)?.complete(null)
    }

    internal fun forget(call: ModernCall) = synchronized(this) {
        calls.entries.removeAll { it.value === call }
        (TalonTelecom.hooks?.route as? ModernTelecomRoute)?.unbind(call)
    }
}

/** One core-telecom call: the scope telecom handed us, driven from
 *  outside with the same verbs [TalonConnection] answers to. */
class ModernCall(
    val id: String,
    internal val control: CallControlScope,
    private val ended: CompletableDeferred<Unit>,
) {
    val uuid: String = control.getCallId().uuid.toString()
    @Volatile private var over = false
    @Volatile var answered = false
        private set

    fun setActive() { answered = true; control.launch { control.setActive() } }
    fun answer() { answered = true; control.launch { control.answer(CallAttributesCompat.CALL_TYPE_AUDIO_CALL) } }

    fun disconnect(cause: Int) {
        if (over) return
        over = true
        control.launch {
            runCatching { control.disconnect(DisconnectCause(cause)) }
            TalonTelecom.note("$id: DISCONNECTED (${DisconnectCause(cause)})")
            ModernTelecom.forget(this@ModernCall)
            ended.complete(Unit)
        }
    }

    fun endRing(answeredElsewhere: Boolean) =
        disconnect(if (answeredElsewhere) DisconnectCause.ANSWERED_ELSEWHERE else DisconnectCause.MISSED)

    fun armRingTimeout(ms: Long) {
        control.launch {
            delay(ms)
            if (!answered) disconnect(DisconnectCause.MISSED)
        }
    }
}

/** The registered call's endpoints, for the audio picker. */
class ModernTelecomRoute : CallRoute {
    private val endpoints = MutableStateFlow<List<CallEndpointCompat>>(emptyList())
    private val current = MutableStateFlow<CallEndpointCompat?>(null)
    @Volatile private var call: ModernCall? = null

    override val active: Boolean get() = call != null
    override val selected: String? get() = current.value?.identifier?.toString()

    override fun devices(): List<AudioDevice> =
        endpoints.value.map { AudioDevice(id = it.identifier.toString(), label = it.name.toString()) }

    override fun select(id: String?) {
        val c = call?.control ?: return
        val target = endpoints.value.firstOrNull { it.identifier.toString() == id } ?: return
        c.launch { c.requestEndpointChange(target) }
    }

    internal fun bind(c: ModernCall) {
        call = c
        c.control.launch { c.control.availableEndpoints.collect { endpoints.value = it } }
        c.control.launch { c.control.currentCallEndpoint.collect { current.value = it } }
    }

    internal fun unbind(c: ModernCall) {
        if (call === c) { call = null; endpoints.value = emptyList(); current.value = null }
    }
}

/**
 * UUID → what to dial, for ACTION_CALL_BACK. On disk, because the
 * call-back can come days later to a process that never saw the call.
 * Bounded; a call-back into a forgotten call just opens the app.
 */
object CallBackTargets {
    private const val PREFS = "talon.callbacks"
    private const val MAX = 100

    fun remember(context: Context, uuid: String, target: String) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit {
            putString(uuid, target)
            val all = p.all.keys
            if (all.size > MAX) {
                // Drop the oldest; keys carry no time, so drop arbitrarily
                // beyond the cap — a hundred call-backs of history is plenty.
                all.take(all.size - MAX).forEach { remove(it) }
            }
        }
    }

    fun lookup(context: Context, uuid: String?): String? {
        if (uuid.isNullOrBlank()) return null
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(uuid, null)
    }
}
