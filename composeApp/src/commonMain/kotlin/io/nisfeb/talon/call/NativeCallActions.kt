package io.nisfeb.talon.call

/**
 * Connect a platform's native call UI (iOS CallKit) to the shared
 * [CallController]. Called once when the controller is created.
 *
 * Only iOS implements it — it routes the CallKit answer/decline the
 * user taps on the system call screen into accept()/reject(). Android
 * and desktop have no native call screen, so their actual is a no-op.
 */
expect fun bindNativeCallActions(controller: CallController)
