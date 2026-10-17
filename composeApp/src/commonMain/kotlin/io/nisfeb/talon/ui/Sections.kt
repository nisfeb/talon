package io.nisfeb.talon.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf

/**
 * The full-screen sections a shell can have open, kept in one place.
 *
 * THE BUG THIS EXISTS FOR, which shipped with nearly every new section:
 * each section was its own `var fooOpen` flag, and a flag had to be
 * written into several hand-kept lists to behave: the list that puts
 * the last section down when the drawer picks the next one, the back
 * handlers, and the render's `when`. A new section added to some of
 * them and not all was stuck on screen. Back did nothing, because it
 * had no handler. Picking another section from the drawer did nothing,
 * because the reset list did not clear it and the render's `when`
 * shows the first flag that is true, not the newest one.
 *
 * A flag made here cannot be missed by either list. [closeAll] closes
 * every flag this registry ever made, and [closeLast] closes the one
 * opened most recently, so one back handler covers every section,
 * including the next one somebody adds. Declare a section with
 * `var fooOpen by remember { sections.flag() }` and it is done, or one
 * that opens on something with `var fooFor by remember { sections.page<String>() }`.
 */
class Sections {
    private val all = mutableListOf<Slot<*>>()

    /** Open sections, oldest first, so back closes the newest. */
    private val open = mutableStateListOf<Slot<*>>()

    /**
     * One section, open while its value is not [closed]: a flag while it
     * is true, a page (a group's, by its flag) while it names something.
     */
    inner class Slot<T> internal constructor(private val closed: T, initial: T) : MutableState<T> {
        private val state = mutableStateOf(initial)

        init {
            if (initial != closed) open.add(this)
        }

        override var value: T
            get() = state.value
            set(v) {
                if (state.value == v) return
                state.value = v
                open.remove(this)
                if (v != closed) open.add(this)
            }

        internal fun close() {
            value = closed
        }

        override fun component1(): T = value
        override fun component2(): (T) -> Unit = { value = it }
    }

    /** A new section's flag, registered so nothing can forget it. */
    fun flag(initial: Boolean = false): Slot<Boolean> = Slot(false, initial).also { all += it }

    /**
     * A section that opens on something, a group's page on the group:
     * open while it names one, closed by setting it to null. The group
     * pages were plain values, so neither the drawer nor back could
     * leave them.
     */
    fun <T : Any> page(initial: T? = null): Slot<T?> = Slot<T?>(null, initial).also { all += it }

    /** Whether any section is open, which is when back has something to close. */
    val anyOpen: Boolean get() = open.isNotEmpty()

    /**
     * Whether a section other than [under] is open. A shell that draws
     * some sections beneath the chat (Android keeps Search open under the
     * chat it opened) lets the chat's back come before those.
     */
    fun anyOpenExcept(vararg under: Slot<*>): Boolean = open.any { s -> under.none { it === s } }

    /** Put every section down, the ones added after this line was written included. */
    fun closeAll() {
        all.forEach { it.close() }
    }

    /**
     * Asked before a section is put down by back, Escape, the rail, the
     * drawer or a shortcut: a screen holding unsaved work sets it, asks
     * the owner, and runs the leave itself once they agree. The profile
     * editor's own back prompt was all it had, and desktop has no back:
     * Escape or a rail click dropped the edits without a word.
     */
    var guard: ((leave: () -> Unit) -> Unit)? = null

    /** Run [leave], which puts the open section down, once [guard] lets it. */
    fun leave(leave: () -> Unit) {
        val g = guard
        if (g == null) leave() else g(leave)
    }

    /** Close the section opened most recently. False when none is open. */
    fun closeLast(): Boolean {
        val newest = open.lastOrNull() ?: return false
        newest.close()
        return true
    }
}
