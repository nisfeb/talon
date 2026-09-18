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
 * `var fooOpen by remember { sections.flag() }` and it is done.
 */
class Sections {
    private val all = mutableListOf<Flag>()

    /** Open flags, oldest first, so back closes the newest. */
    private val open = mutableStateListOf<Flag>()

    inner class Flag internal constructor(initial: Boolean) : MutableState<Boolean> {
        private val state = mutableStateOf(initial)

        init {
            if (initial) open.add(this)
        }

        override var value: Boolean
            get() = state.value
            set(v) {
                if (state.value == v) return
                state.value = v
                open.remove(this)
                if (v) open.add(this)
            }

        override fun component1(): Boolean = value
        override fun component2(): (Boolean) -> Unit = { value = it }
    }

    /** A new section's flag, registered so nothing can forget it. */
    fun flag(initial: Boolean = false): Flag = Flag(initial).also { all += it }

    /** Whether any section is open, which is when back has something to close. */
    val anyOpen: Boolean get() = open.isNotEmpty()

    /** Put every section down, the ones added after this line was written included. */
    fun closeAll() {
        all.forEach { it.value = false }
    }

    /** Close the section opened most recently. False when none is open. */
    fun closeLast(): Boolean {
        val newest = open.lastOrNull() ?: return false
        newest.value = false
        return true
    }
}
