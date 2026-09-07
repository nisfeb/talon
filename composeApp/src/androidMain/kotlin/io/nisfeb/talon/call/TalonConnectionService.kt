package io.nisfeb.talon.call

import android.bluetooth.BluetoothDevice
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.CallAudioState
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import io.nisfeb.talon.MainActivity
import io.nisfeb.talon.Notifications
import io.nisfeb.talon.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The telecom side of every Urbit call, one layer below Jetpack's
 * core-telecom: our own self-managed [PhoneAccount], which is the only
 * way to opt into the system call log. A call registered here shows
 * in the Phone app under Talon, a ring nobody answered as Missed, and
 * the Phone app's call-back on one of them comes to
 * [onCreateOutgoingConnection] with the `urbit:~ship` address, which
 * places the call. core-telecom builds its account without that
 * opt-in and offers no way to add it.
 *
 * Android-only: iOS has CallKit (CallPush.swift); desktop has no
 * telecom stack. [TelecomCalls] drives it from the call and party
 * line state; this file is the framework glue and knows nothing of
 * either beyond the [TalonTelecom.Hooks] it is handed.
 */
class TalonConnectionService : ConnectionService() {

    override fun onCreateIncomingConnection(
        from: PhoneAccountHandle?,
        request: ConnectionRequest,
    ): Connection {
        val extras = request.extras
        val id = extras?.getString(EXTRA_TALON_ID) ?: "incoming"
        val conn = TalonConnection(id).apply {
            setAddress(request.address, TelecomManager.PRESENTATION_ALLOWED)
            setCallerDisplayName(extras?.getString(EXTRA_NAME), TelecomManager.PRESENTATION_ALLOWED)
            setRinging()
        }
        TalonTelecom.adopt(id, conn)
        return conn
    }

    override fun onCreateOutgoingConnection(
        from: PhoneAccountHandle?,
        request: ConnectionRequest,
    ): Connection {
        val extras = request.extras
        val id = extras?.getString(EXTRA_TALON_ID)
        val name = extras?.getString(EXTRA_NAME)
        val ship = request.address?.schemeSpecificPart.orEmpty()
        if (id != null) {
            // Our own placeCall, carrying the id we will drive it by.
            val conn = TalonConnection(id).apply {
                setAddress(request.address, TelecomManager.PRESENTATION_ALLOWED)
                setCallerDisplayName(name, TelecomManager.PRESENTATION_ALLOWED)
                setDialing()
            }
            TalonTelecom.adopt(id, conn)
            return conn
        }
        // No id: the Phone app is calling back one of our logged calls.
        // Hold the connection as dialing, ask the app to place the
        // call, and let the call adopt it when it comes through. A
        // dead app is started on the same path a missed-call notice
        // uses; if nothing adopts it, it is torn down.
        val conn = TalonConnection("redial:$ship").apply {
            setAddress(request.address, TelecomManager.PRESENTATION_ALLOWED)
            setCallerDisplayName(ship, TelecomManager.PRESENTATION_ALLOWED)
            setDialing()
        }
        TalonTelecom.offerRedial(ship, conn)
        val hooks = TalonTelecom.hooks
        if (hooks != null) {
            hooks.callBack(ship)
        } else {
            runCatching {
                startActivity(
                    Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        putExtra(Notifications.EXTRA_CALL_BACK, ship)
                    },
                )
            }.onFailure { Log.w(TAG, "could not start the app for a call-back", it) }
        }
        return conn
    }

    override fun onCreateIncomingConnectionFailed(from: PhoneAccountHandle?, request: ConnectionRequest?) {
        val id = request?.extras?.getString(EXTRA_TALON_ID) ?: return
        Log.w(TAG, "telecom refused the incoming call $id")
        TalonTelecom.refuse(id)
    }

    override fun onCreateOutgoingConnectionFailed(from: PhoneAccountHandle?, request: ConnectionRequest?) {
        val id = request?.extras?.getString(EXTRA_TALON_ID) ?: return
        Log.w(TAG, "telecom refused the outgoing call $id")
        TalonTelecom.refuse(id)
    }

    companion object {
        const val EXTRA_TALON_ID = "io.nisfeb.talon.TALON_ID"
        const val EXTRA_NAME = "io.nisfeb.talon.NAME"
        private const val TAG = "TalonConnectionService"
    }
}

/**
 * One telecom connection. Telecom's requests (answer, end, hold, a
 * new audio route) go to whoever holds [TalonTelecom.hooks]; what the
 * call does is pushed in from outside with the usual setters.
 */
class TalonConnection(val talonId: String) : Connection() {
    init {
        connectionProperties = PROPERTY_SELF_MANAGED
        connectionCapabilities = CAPABILITY_HOLD or CAPABILITY_SUPPORT_HOLD or CAPABILITY_MUTE
        audioModeIsVoip = true
    }

    private val controls: TalonTelecom.Controls? get() = TalonTelecom.hooks?.controls(talonId)

    override fun onAnswer() { controls?.onAnswer() }
    override fun onAnswer(videoState: Int) { controls?.onAnswer() }
    override fun onReject() { controls?.onReject() }
    override fun onDisconnect() { controls?.onDisconnect() }
    override fun onAbort() { controls?.onDisconnect() }

    override fun onHold() {
        controls?.onHold()
        setOnHold()
    }

    override fun onUnhold() {
        controls?.onUnhold()
        setActive()
    }

    /** Self-managed: our own notification is the ring UI. */
    override fun onShowIncomingCallUi() = Unit

    @Deprecated("Deprecated in API 34 but still delivered; see TelecomRoute")
    override fun onCallAudioStateChanged(state: CallAudioState) {
        TalonTelecom.hooks?.route?.update(this, state)
    }

    override fun onStateChanged(state: Int) {
        if (state == STATE_DISCONNECTED) {
            TalonTelecom.forget(this)
            TalonTelecom.hooks?.route?.unbind(this)
        }
    }
}

/**
 * The account, the live connections, and the seam to the app.
 * Process-global because telecom binds the service on its own
 * schedule, including into a process it just started.
 */
object TalonTelecom {
    /** What the app does when telecom asks. Set while a controller lives. */
    interface Controls {
        fun onAnswer()
        fun onReject()
        fun onDisconnect()
        fun onHold()
        fun onUnhold()
    }

    interface Hooks {
        val route: TelecomRoute
        fun controls(id: String): Controls?
        /** The Phone app called back one of our logged calls. */
        fun callBack(ship: String)
    }

    @Volatile var hooks: Hooks? = null

    private val connections = HashMap<String, TalonConnection>()
    private val waiters = HashMap<String, CompletableDeferred<TalonConnection?>>()
    private val redials = HashMap<String, TalonConnection>()
    private var handle: PhoneAccountHandle? = null

    private const val LOG_SELF_MANAGED_CALLS = "android.telecom.extra.LOG_SELF_MANAGED_CALLS"
    private const val TAG = "TalonTelecom"

    fun register(context: Context): Boolean {
        val app = context.applicationContext
        val tm = app.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager ?: return false
        val h = PhoneAccountHandle(ComponentName(app, TalonConnectionService::class.java), "talon")
        return runCatching {
            val account = PhoneAccount.builder(h, "Talon")
                .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                .addSupportedUriScheme(SCHEME)
                // The reason this file exists: without it a self-managed
                // call never reaches the system call log (30+).
                .setExtras(Bundle().apply { putBoolean(LOG_SELF_MANAGED_CALLS, true) })
                .build()
            tm.registerPhoneAccount(account)
            handle = h
            true
        }.onFailure { Log.w(TAG, "phone account registration failed; calls stay app-managed", it) }
            .getOrDefault(false)
    }

    /** Start a call we placed, or adopt the Phone app's call-back for
     *  [ship] if one is waiting. False if telecom would not take it. */
    fun startOutgoing(context: Context, id: String, ship: String, name: String): Boolean {
        synchronized(this) { redials.remove(ship) }?.let { conn ->
            connections[id] = conn
            conn.setCallerDisplayName(name, TelecomManager.PRESENTATION_ALLOWED)
            waiters.remove(id)?.complete(conn)
            return true
        }
        val tm = context.applicationContext.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            ?: return false
        val h = handle ?: return false
        if (!tm.isOutgoingCallPermitted(h)) return false
        val extras = Bundle().apply {
            putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, h)
            putBundle(
                TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS,
                Bundle().apply {
                    putString(TalonConnectionService.EXTRA_TALON_ID, id)
                    putString(TalonConnectionService.EXTRA_NAME, name)
                },
            )
        }
        return runCatching { tm.placeCall(Uri.fromParts(SCHEME, ship, null), extras); true }
            .onFailure { Log.w(TAG, "placeCall refused", it) }
            .getOrDefault(false)
    }

    fun startIncoming(context: Context, id: String, ship: String, name: String): Boolean {
        val tm = context.applicationContext.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            ?: return false
        val h = handle ?: return false
        if (!tm.isIncomingCallPermitted(h)) return false
        val extras = Bundle().apply {
            putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, Uri.fromParts(SCHEME, ship, null))
            putString(TalonConnectionService.EXTRA_TALON_ID, id)
            putString(TalonConnectionService.EXTRA_NAME, name)
        }
        return runCatching { tm.addNewIncomingCall(h, extras); true }
            .onFailure { Log.w(TAG, "addNewIncomingCall refused", it) }
            .getOrDefault(false)
    }

    /** The connection telecom created for [id], once it has. Null if
     *  telecom refused or never answered. */
    suspend fun await(id: String, timeoutMs: Long = 10_000): TalonConnection? {
        val waiter = synchronized(this) {
            connections[id]?.let { return it }
            waiters.getOrPut(id) { CompletableDeferred() }
        }
        return withTimeoutOrNull(timeoutMs) { waiter.await() }
    }

    fun connection(id: String): TalonConnection? = synchronized(this) { connections[id] }

    internal fun adopt(id: String, conn: TalonConnection) = synchronized(this) {
        connections[id] = conn
        waiters.remove(id)?.complete(conn)
        hooks?.route?.bind(conn)
    }

    internal fun refuse(id: String) = synchronized(this) {
        waiters.remove(id)?.complete(null)
    }

    internal fun offerRedial(ship: String, conn: TalonConnection) = synchronized(this) {
        redials.put(ship, conn)?.let { old ->
            old.setDisconnected(DisconnectCause(DisconnectCause.CANCELED))
            old.destroy()
        }
        hooks?.route?.bind(conn)
    }

    internal fun forget(conn: TalonConnection) = synchronized(this) {
        connections.entries.removeAll { it.value === conn }
        redials.entries.removeAll { it.value === conn }
    }

    private const val SCHEME = "urbit"
}

/**
 * The registered call's audio routes, as [AndroidAudioDevices] shows
 * them. While a call is registered telecom owns routing, and setting
 * a communication device behind its back is at best ignored; the
 * picker asks the connection instead.
 */
class TelecomRoute {
    @Volatile private var conn: TalonConnection? = null
    @Volatile private var state: CallAudioState? = null

    val active: Boolean get() = conn != null

    val selected: String? get() = state?.let { s ->
        val bt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) s.activeBluetoothDevice else null
        if (s.route == CallAudioState.ROUTE_BLUETOOTH && bt != null) "bt:" + bt.address else "route:" + s.route
    }

    fun devices(): List<AudioDevice> {
        val s = state ?: return emptyList()
        val out = ArrayList<AudioDevice>()
        val mask = s.supportedRouteMask
        if (mask and CallAudioState.ROUTE_EARPIECE != 0) out += AudioDevice("route:${CallAudioState.ROUTE_EARPIECE}", "Earpiece")
        if (mask and CallAudioState.ROUTE_SPEAKER != 0) out += AudioDevice("route:${CallAudioState.ROUTE_SPEAKER}", "Speaker")
        if (mask and CallAudioState.ROUTE_WIRED_HEADSET != 0) out += AudioDevice("route:${CallAudioState.ROUTE_WIRED_HEADSET}", "Wired headset")
        if (mask and CallAudioState.ROUTE_BLUETOOTH != 0) {
            val devices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) s.supportedBluetoothDevices else emptyList()
            if (devices.isEmpty()) {
                out += AudioDevice("route:${CallAudioState.ROUTE_BLUETOOTH}", "Bluetooth")
            } else {
                for (d in devices) out += AudioDevice("bt:" + d.address, nameOf(d))
            }
        }
        return out
    }

    fun select(id: String?) {
        val c = conn ?: return
        runCatching {
            when {
                id == null -> c.setAudioRoute(CallAudioState.ROUTE_WIRED_OR_EARPIECE)
                id.startsWith("bt:") && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> {
                    val addr = id.removePrefix("bt:")
                    val device = state?.supportedBluetoothDevices?.firstOrNull { it.address == addr } ?: return
                    c.requestBluetoothAudio(device)
                }
                id.startsWith("route:") -> c.setAudioRoute(id.removePrefix("route:").toInt())
            }
        }.onFailure { Log.w("TelecomRoute", "could not set audio route", it) }
    }

    /** The device's own name where we are allowed to read it (31+
     *  needs BLUETOOTH_CONNECT); "Bluetooth" otherwise. */
    private fun nameOf(d: BluetoothDevice): String =
        runCatching { d.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Bluetooth"

    internal fun bind(c: TalonConnection) {
        conn = c
        state = runCatching { @Suppress("DEPRECATION") c.callAudioState }.getOrNull()
    }

    internal fun update(c: TalonConnection, s: CallAudioState) {
        if (conn === c) state = s
    }

    internal fun unbind(c: TalonConnection) {
        if (conn === c) { conn = null; state = null }
    }
}
