package io.nisfeb.talon.update
import io.nisfeb.talon.util.ioDispatcher

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Pluggable installer hook so commonMain can drive both the Android
 * PackageInstaller-backed download/install path and the desktop
 * self-update installer.
 */
interface UpdateInstallerHook {
    suspend fun download(
        manifest: UpdateManifest,
        onProgress: (Int) -> Unit,
        onReady: (String) -> Unit,
        onFailure: (String) -> Unit,
    )

    fun install(apkPath: String)

    /** What tapping Ready will do, in a sentence for the banner. */
    val readyHint: String
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
        scope.launch(ioDispatcher) {
            installer.download(
                manifest = manifest,
                onProgress = { pct ->
                    _status.value = UpdateStatus.Downloading(manifest, pct)
                },
                onReady = { apkPath ->
                    _status.value = UpdateStatus.Ready(manifest, apkPath, installer.readyHint)
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
 * No-op fallback for targets with no self-update path (iOS, tests).
 * Desktop self-update exists — see DesktopUpdateInstaller in
 * desktopMain — so this hook is only for platforms where installing
 * from a manifest isn't wired at all.
 */
class NoopUpdateInstallerHook : UpdateInstallerHook {
    override suspend fun download(
        manifest: UpdateManifest,
        onProgress: (Int) -> Unit,
        onReady: (String) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        onFailure("This build cannot self-update.")
    }

    override val readyHint = ""

    override fun install(apkPath: String) {
        // No-op.
    }
}

/**
 * Default runtime for targets without a real version source —
 * pretends version 0 is installed so any manifest looks newer. Pair
 * with a real installer hook (Android's UpdateInstaller, desktop's
 * DesktopUpdateInstaller); with NoopUpdateInstallerHook the banner
 * renders but install is a no-op.
 */
class StaticUpdateRuntime(
    private val versionCode: Int = 0,
    private val sdk: Int = Int.MAX_VALUE,
) : UpdateRuntime {
    override fun installedVersionCode(): Int = versionCode
    override fun supportedSdk(): Int = sdk
}
