package io.nisfeb.talon

import androidx.compose.ui.window.ComposeUIViewController
import io.nisfeb.talon.ai.createAiSettings
import io.nisfeb.talon.compose.App
import io.nisfeb.talon.data.createAppDatabase
import io.nisfeb.talon.ai.NoopDailyDigestSettings
import io.nisfeb.talon.ui.InMemoryDraftStore
import io.nisfeb.talon.ui.createUiSettings
import io.nisfeb.talon.ui.theme.IosThemePreference
import io.nisfeb.talon.update.UpdateInstallerHook
import io.nisfeb.talon.update.UpdateManifest
import io.nisfeb.talon.update.UpdateRuntime
import io.nisfeb.talon.update.UpdateState
import io.nisfeb.talon.urbit.SettingsSyncImpl
import io.nisfeb.talon.urbit.createSessionStore
import io.nisfeb.talon.util.IosFiles
import io.nisfeb.talon.util.backgroundExceptionHandler
import io.nisfeb.talon.util.createAppHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import platform.UIKit.UIViewController

/**
 * iOS entry point. The Xcode host (iosApp) calls
 * `MainViewControllerKt.MainViewController()` and embeds the returned
 * controller. Wires the six required App() dependencies with iOS-backed
 * impls; the rest take their commonMain defaults.
 *
 * Persistence today: session, assistant settings, and theme survive
 * restart (JSON under Documents). UI settings use the in-memory default
 * (persistence pending — it needs the per-ship rail-visibility DB
 * projection). Update install is a no-op — App Store owns updates.
 * On-device AI / digest / loops are gated off in Capabilities.ios.kt.
 */
fun MainViewController(): UIViewController {
    // Kotlin/Native terminates on any exception that escapes to a foreign
    // (GCD) frame — Apple review hit that as an undiagnosable SIGABRT
    // crash-loop, and the .ips logs carry no Kotlin frames. Write the
    // real stack to Documents/last-crash.txt (retrievable via the Files
    // app; UIFileSharingEnabled) before the runtime aborts.
    @OptIn(kotlin.experimental.ExperimentalNativeApi::class)
    setUnhandledExceptionHook { t ->
        val report = "Talon ${TalonBuild.versionName} uncaught: $t\n${t.stackTraceToString()}"
        println(report)
        IosFiles.write("last-crash.txt", report)
    }
    val http = createAppHttpClient()
    val sessionStore = createSessionStore()
    val aiSettings = createAiSettings()
    val themePreference = IosThemePreference()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + backgroundExceptionHandler)
    // Digest has no scheduler on iOS (gated off in Capabilities), but
    // %settings sync still needs a sink for the bucket.
    val dailyDigestSettings = NoopDailyDigestSettings()

    val updateState = UpdateState(
        scope = scope,
        runtime = object : UpdateRuntime {
            override fun installedVersionCode(): Int = TalonBuild.versionCode
            override fun supportedSdk(): Int = Int.MAX_VALUE
        },
        installer = object : UpdateInstallerHook {
            override suspend fun download(
                manifest: UpdateManifest,
                onProgress: (Int) -> Unit,
                onReady: (String) -> Unit,
                onFailure: (String) -> Unit,
            ) {
                onFailure("In-app update isn't available on iOS — updates come from the App Store.")
            }

            override fun install(apkPath: String) {
                // No sideload on iOS.
            }
        },
    )

    return ComposeUIViewController {
        App(
            http = http,
            sessionStore = sessionStore,
            aiSettings = aiSettings,
            createDb = { shipKey -> createAppDatabase(shipKey) },
            drafts = InMemoryDraftStore(),
            updateState = updateState,
            themePreference = themePreference,
            // Without these two iOS fell back to in-memory defaults:
            // folders were created into a null sink and never appeared,
            // and every per-device preference reset on relaunch.
            createSettingsSync = { db ->
                SettingsSyncImpl(
                    db = db,
                    aiSettings = aiSettings,
                    dailyDigestSettings = dailyDigestSettings,
                    rearmDailyDigest = {},
                )
            },
            createUiSettings = { db -> createUiSettings(db, scope) },
            dailyDigestSettings = dailyDigestSettings,
        )
    }
}
