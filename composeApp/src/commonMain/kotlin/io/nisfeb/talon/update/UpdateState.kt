package io.nisfeb.talon.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Pluggable installer hook so commonMain can drive both the Android
 * PackageInstaller-backed download/install path and a desktop no-op.
 */
interface UpdateInstallerHook {
    suspend fun download(
        manifest: UpdateManifest,
        onProgress: (Int) -> Unit,
        onReady: (String) -> Unit,
        onFailure: (String) -> Unit,
    )

    fun install(apkPath: String)
}

/** Runtime info commonMain UpdateState needs without touching Android Context. */
interface UpdateRuntime {
    /** Currently-installed app versionCode. */
    fun installedVersionCode(): Int

    /**
     * SDK level the host can satisfy. On Android this is
     * `Build.VERSION.SDK_INT`. On desktop, anything we accept (the
     * APK is meaningless there anyway) — return Int.MAX_VALUE so
     * minSdk gating never blocks a desktop banner test.
     */
    fun supportedSdk(): Int
}

/**
 * Process-wide source of truth for the "is there an update?" banner.
 */
class UpdateState(
    private val scope: CoroutineScope,
    private val runtime: UpdateRuntime,
    private val installer: UpdateInstallerHook,
) {
    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    fun onManifest(manifest: UpdateManifest) {
        val installed = runtime.installedVersionCode()
        if (manifest.versionCode <= installed) return
        if (manifest.minSdk > runtime.supportedSdk()) return
        when (val cur = _status.value) {
            is UpdateStatus.Downloading,
            is UpdateStatus.Ready -> return
            else -> {
                if (cur is UpdateStatus.Available && cur.manifest.versionCode == manifest.versionCode) {
                    return
                }
                _status.value = UpdateStatus.Available(manifest)
            }
        }
    }

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

    fun launchInstaller(apkPath: String) {
        installer.install(apkPath)
    }

    fun dismiss() {
        when (_status.value) {
            is UpdateStatus.Available, is UpdateStatus.Failed ->
                _status.value = UpdateStatus.Idle
            else -> Unit
        }
    }
}

/**
 * Desktop-friendly default. Renders the banner inert: nothing is
 * installed via this path on desktop, so the supported SDK is wide
 * open and the installer is a no-op. Stage F replaces this with a
 * real installer when desktop sideload distribution is wired up.
 */
class NoopUpdateInstallerHook : UpdateInstallerHook {
    override suspend fun download(
        manifest: UpdateManifest,
        onProgress: (Int) -> Unit,
        onReady: (String) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        onFailure("Desktop builds do not self-update yet.")
    }

    override fun install(apkPath: String) {
        // No-op.
    }
}

/**
 * Default desktop runtime — pretends version 0 is installed so any
 * manifest looks newer (the banner is still gated by the no-op
 * installer above; the user can't actually install anything from
 * desktop in this revision).
 */
class StaticUpdateRuntime(
    private val versionCode: Int = 0,
    private val sdk: Int = Int.MAX_VALUE,
) : UpdateRuntime {
    override fun installedVersionCode(): Int = versionCode
    override fun supportedSdk(): Int = sdk
}
