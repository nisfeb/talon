package io.nisfeb.talon.ai

/**
 * Same-shape interface, impl per leaf (CLAUDE.md §2). Arms whatever
 * mechanism a platform uses to fire due loops:
 *  - Android: a single AlarmManager wake-up at the earliest next-fire,
 *    surviving app close + reboot (re-armed by BootReceiver).
 *  - Desktop: a coroutine ticker in App.kt that polls due loops while the
 *    window is open, so it has no wake-up to re-arm and wires the Noop
 *    impl below (it re-reads loops every tick).
 *
 * [reschedule] is called after any change to loop definitions (add, edit,
 * enable toggle, delete) so the next wake-up reflects the new earliest
 * due time. A polling impl (desktop ticker) can treat it as a no-op.
 */
interface LoopScheduler {
    fun reschedule()

    /** No-op default for tests and platforms without a wired scheduler. */
    companion object Noop : LoopScheduler {
        override fun reschedule() {}
    }
}
