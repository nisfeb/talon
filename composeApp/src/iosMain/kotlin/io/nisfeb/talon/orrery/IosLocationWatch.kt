package io.nisfeb.talon.orrery

import io.nisfeb.talon.data.createAppDatabase
import io.nisfeb.talon.ui.LocationControl
import io.nisfeb.talon.urbit.UrbitSession
import io.nisfeb.talon.urbit.createSessionStore
import io.nisfeb.talon.util.Log
import io.nisfeb.talon.util.createAppHttpClient
import io.nisfeb.talon.util.nowMs
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLLocationAccuracyNearestTenMeters
import platform.Foundation.NSError
import platform.Foundation.NSUserDefaults
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIApplication
import platform.UIKit.UIBackgroundTaskInvalid
import platform.darwin.NSObject
import platform.darwin.dispatch_after
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_time
import platform.darwin.DISPATCH_TIME_NOW

/**
 * The phone tells orrery where the owner is, on iOS, as LocationWatch
 * does on Android: on each significant move (about 500 m, which iOS
 * wakes even a closed app for) while "Send where I am" is on, and each
 * minute while a live share runs (orrery 88), with the blue pill in the
 * status bar as its indicator. Always authorization for the moves;
 * a share started from the app keeps running in the background on
 * standard updates with allowsBackgroundLocationUpdates.
 *
 * A fix is sent here, not by a screen: a move can wake the app with no
 * UI, so the login and the ship's database are opened for it
 * ([IosLateMoves] does the same for a notification's buttons). No place
 * names: CLGeocoder is not used, so only places orrery knows are said.
 *
 * Swift calls [resume] at every launch, a location relaunch included
 * (CallPush.swift), so a registration survives the app being closed.
 */
@OptIn(ExperimentalForeignApi::class)
object IosLocationWatch {
    private const val KEY_ON = "talon.orrery.location.on"
    private const val KEY_PAUSED = "talon.orrery.location.paused"
    private const val KEY_LIVE = "talon.orrery.location.live-until"
    private const val TAG = "IosLocationWatch"

    private val defaults get() = NSUserDefaults.standardUserDefaults
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var manager: CLLocationManager? = null
    private var lastSentMs: Long? = null
    private var authorized: ((Boolean) -> Unit)? = null

    private val _on = MutableStateFlow(defaults.boolForKey(KEY_ON))
    val on: StateFlow<Boolean> = _on

    /** The control the orrery pipe holds: off, held, and the live share's minute rate. */
    val control: LocationControl = object : LocationControl {
        override fun stop() = set(false)
        override fun pause(paused: Boolean) {
            defaults.setBool(paused, KEY_PAUSED)
            resume()
        }
        override fun live(untilMs: Long?) {
            defaults.setDouble((untilMs ?: 0L).toDouble(), KEY_LIVE)
            resume()
        }
    }

    /** Location all the time: what hearing a move with the app closed needs. */
    fun allowed(): Boolean = manager().authorizationStatus == kCLAuthorizationStatusAuthorizedAlways

    private fun sharing(): Boolean = defaults.boolForKey(KEY_ON) && allowed() && !defaults.boolForKey(KEY_PAUSED)

    private fun liveUntil(): Long? = defaults.doubleForKey(KEY_LIVE).toLong().takeIf { it > nowMs() }

    /** Ask for Always (iOS asks When In Use first, then offers Always), then listen. False when it was refused. */
    fun start(answer: (Boolean) -> Unit) {
        val m = manager()
        if (allowed()) { set(true); answer(true); return }
        authorized = { ok -> if (ok) set(true); answer(ok) }
        m.requestAlwaysAuthorization()
    }

    fun set(on: Boolean) {
        defaults.setBool(on, KEY_ON)
        _on.value = on
        resume()
    }

    /** Listen to match the switch: significant moves, and a fix a minute while a share runs. */
    fun resume() = onMain {
        val m = manager()
        if (!sharing()) {
            m.stopMonitoringSignificantLocationChanges()
            m.stopUpdatingLocation()
            return@onMain
        }
        m.startMonitoringSignificantLocationChanges()
        val until = liveUntil()
        if (until == null) {
            m.stopUpdatingLocation()
            return@onMain
        }
        m.desiredAccuracy = kCLLocationAccuracyNearestTenMeters
        m.distanceFilter = TRIP_MOVE_M.toDouble()
        m.pausesLocationUpdatesAutomatically = false
        m.allowsBackgroundLocationUpdates = true
        m.showsBackgroundLocationIndicator = true
        m.startUpdatingLocation()
        // When the share's time comes, the ship is asked again: a share
        // until home has no end the phone knows.
        val wait = (until - nowMs()).coerceAtLeast(0)
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, wait * 1_000_000), dispatch_get_main_queue()) { recheck() }
    }

    /** One fix now, sent whatever the minute's rate says: a share starting has no move to wait for. */
    fun sendNow() = onMain {
        if (!sharing()) return@onMain
        askedNow = true
        manager().requestLocation()
    }

    private var askedNow = false

    private fun recheck() {
        if (liveUntil() != null) return
        scope.launch {
            val until = runCatching { withLogin { api, _, _ -> sendFastUntil(locationShares(api.locationDoc(), nowMs()), nowMs()) } }.getOrNull()
            control.live(until)
        }
    }

    private fun take(loc: CLLocation) {
        val nowMs = nowMs()
        val asked = askedNow
        askedNow = false
        if (!asked && !fixDue(lastSentMs, nowMs, fast = liveUntil() != null)) return
        lastSentMs = nowMs
        val (lat, lon) = loc.coordinate.useContents { latitude to longitude }
        // Asked for now, it is where the owner is now: iOS may hand back
        // a kept fix under its old time, which the ship reads as old.
        val at = (loc.timestamp.timeIntervalSince1970 * 1000).toLong().let { if (asked && !io.nisfeb.talon.ui.freshFix(it, nowMs)) nowMs else it }
        val fix = LocationFix(lat, lon, loc.horizontalAccuracy.takeIf { it >= 0 } ?: MAX_MATCH_M, at)
        // A wake gives the app seconds; a background task asks for the time the send takes.
        val app = UIApplication.sharedApplication
        var task = UIBackgroundTaskInvalid
        task = app.beginBackgroundTaskWithExpirationHandler { app.endBackgroundTask(task) }
        scope.launch {
            try {
                withLogin { _, session, ship ->
                    val db = createAppDatabase(ship)
                    try {
                        sendLocation(session.http, db, session.baseUrl ?: return@withLogin, ship, fix, null, sharing = ::sharing)
                            .onFailure { Log.w(TAG, "location not sent: ${it.message}") }
                    } finally {
                        db.close()
                    }
                }
            } finally {
                onMain { app.endBackgroundTask(task) }
            }
        }
    }

    /**
     * The kept login, restored for one send.
     * ponytail: a second Room instance on the ship's file while the app
     * has its own open; SQLite's locking keeps the two apart. Hand the
     * app's own over if writes from both ever clash.
     */
    private suspend fun <T> withLogin(block: suspend (OrreryApi, UrbitSession, String) -> T): T? {
        val http = createAppHttpClient()
        return try {
            val session = UrbitSession(http, createSessionStore())
            val ship = session.tryRestore() ?: return null
            val url = session.baseUrl ?: return null
            block(OrreryApi(session.http, session.http, url), session, ship)
        } finally {
            http.close()
        }
    }

    private fun manager(): CLLocationManager = manager ?: CLLocationManager().also {
        it.delegate = delegate
        manager = it
    }

    private val delegate = object : NSObject(), CLLocationManagerDelegateProtocol {
        override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
            (didUpdateLocations.lastOrNull() as? CLLocation)?.let(::take)
        }

        override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
            Log.i(TAG, "no fix: ${didFailWithError.localizedDescription}")
        }

        override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
            if (manager.authorizationStatus == kCLAuthorizationStatusNotDetermined) return
            authorized?.invoke(allowed())
            authorized = null
            resume()
        }
    }

    private fun onMain(block: () -> Unit) = dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 0), dispatch_get_main_queue(), block)
}
