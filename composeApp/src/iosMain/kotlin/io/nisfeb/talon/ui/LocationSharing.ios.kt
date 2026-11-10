package io.nisfeb.talon.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.nisfeb.talon.orrery.IosLocationWatch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** The switch's side of [IosLocationWatch]: the Always prompt, then the listening. No place names on iOS. */
@Composable
actual fun rememberLocationSharing(): LocationSharing? = remember {
    object : LocationSharing {
        override val on: StateFlow<Boolean> = IosLocationWatch.on
        override fun allowed() = IosLocationWatch.allowed()
        override val names: Boolean = false
        override suspend fun start(): Boolean = suspendCancellableCoroutine { c ->
            IosLocationWatch.start { ok -> if (ok) IosLocationWatch.sendNow(); if (c.isActive) c.resume(ok) }
        }
        override fun sendNow() = IosLocationWatch.sendNow()
        override fun stop() = IosLocationWatch.set(false)
    }
}
