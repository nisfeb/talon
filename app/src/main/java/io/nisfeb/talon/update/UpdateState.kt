package io.nisfeb.talon.update

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Process-wide source of truth for the "is there an update?" banner.
 * Owns no checkers itself; the application registers HTTP / Urbit
 * checkers and tells UpdateState when they fire. Threadsafe — all
 * mutations go through the StateFlow.
 */
class UpdateState(
    private val context: Context,
    private val scope: CoroutineScope,
    private val installer: UpdateInstaller,
) {
    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    /**
     * Called by both checkers when they observe a manifest. We only
     * surface it if it's actually newer than what's installed — so a
     * channel that re-emits the current version on reconnect doesn't
     * spam the banner.
     */
    fun onManifest(manifest: UpdateManifest) {
        val installed = installedVersionCode()
        if (manifest.versionCode <= installed) return
        // Skip manifests whose minSdk we can't satisfy. Without this,
        // the device downloads the APK and PackageInstaller rejects
        // it at install time with a generic "app not installed"
        // error. Fail-fast here keeps the banner honest.
        if (manifest.minSdk > Build.VERSION.SDK_INT) return
        // Don't clobber an in-flight download. If the user already
        // tapped to update, leave them alone.
        when (val cur = _status.value) {
            is UpdateStatus.Downloading,
            is UpdateStatus.Ready -> return
            else -> {
                if (cur is UpdateStatus.Available && cur.manifest.versionCode == manifest.versionCode) {
                    return  // same offer, no churn
                }
                _status.value = UpdateStatus.Available(manifest)
            }
        }
    }

    /**
     * User tapped the banner. Kicks off the download. Progress and
     * completion publish back into the state flow.
     */
    fun startDownload(manifest: UpdateManifest) {
        _status.value = UpdateStatus.Downloading(manifest, 0)
        scope.launch(Dispatchers.IO) {
            installer.download(
                manifest = manifest,
                onProgress = { pct ->
                    _status.value = UpdateStatus.Downloading(manifest, pct)
                },
                onReady = { apkPath ->
                    _status.value = UpdateStatus.Ready(manifest, apkPath)
                },
                onFailure = { message ->
                    _status.value = UpdateStatus.Failed(manifest, message)
                },
            )
        }
    }

    /** User tapped Install on the Ready banner — fire the system installer prompt. */
    fun launchInstaller(apkPath: String) {
        installer.install(apkPath)
    }

    fun dismiss() {
        // Allow dismissal of Available (user not interested in this
        // version yet) and Failed (let the user banish the error
        // banner). Don't allow dismissing in-flight Downloading or
        // Ready — those states represent active work the user
        // already authorized.
        when (_status.value) {
            is UpdateStatus.Available, is UpdateStatus.Failed ->
                _status.value = UpdateStatus.Idle
            else -> Unit
        }
    }

    private fun installedVersionCode(): Int = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionCode
    } catch (e: Exception) {
        0
    }
}
