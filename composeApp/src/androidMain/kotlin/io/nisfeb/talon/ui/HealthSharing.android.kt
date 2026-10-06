package io.nisfeb.talon.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.health.connect.client.PermissionController
import io.nisfeb.talon.orrery.HealthWatch
import io.nisfeb.talon.util.runSuspendCatching
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicReference

/**
 * The switch's side of [HealthWatch]: Health Connect's own permission
 * screen for steps, exercise and sleep, with reading in the background
 * offered on the same screen where this Health Connect has it.
 */
@Composable
actual fun rememberHealthSharing(): HealthSharing? {
    val ctx = LocalContext.current.applicationContext
    val pending = remember { AtomicReference<CompletableDeferred<Set<String>>?>(null) }
    DisposableEffect(Unit) { onDispose { pending.getAndSet(null)?.cancel() } }
    val launcher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        pending.getAndSet(null)?.complete(granted)
    }
    return remember(ctx) {
        object : HealthSharing {
            override val on: StateFlow<Boolean> = HealthWatch.on(ctx)
            override fun available() = HealthWatch.available(ctx)

            override suspend fun background(): Boolean = runSuspendCatching {
                HealthWatch.backgroundAvailable(ctx) && HealthWatch.BACKGROUND in HealthWatch.granted(ctx)
            }.getOrDefault(false)

            override suspend fun start(): Boolean {
                if (!HealthWatch.available(ctx)) return false
                val have = runSuspendCatching { HealthWatch.granted(ctx) }.getOrDefault(emptySet())
                val granted = if (have.containsAll(HealthWatch.PERMISSIONS)) {
                    have
                } else {
                    val want = HealthWatch.PERMISSIONS +
                        (if (runCatching { HealthWatch.backgroundAvailable(ctx) }.getOrDefault(false)) setOf(HealthWatch.BACKGROUND) else emptySet())
                    val wait = CompletableDeferred<Set<String>>()
                    pending.getAndSet(wait)?.cancel()
                    launcher.launch(want)
                    wait.await()
                }
                if (!granted.containsAll(HealthWatch.PERMISSIONS)) return false
                HealthWatch.set(ctx, true)
                return true
            }

            override fun stop() = HealthWatch.set(ctx, false)
        }
    }
}
