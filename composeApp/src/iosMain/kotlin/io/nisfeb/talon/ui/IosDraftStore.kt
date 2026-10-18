package io.nisfeb.talon.ui

import io.nisfeb.talon.util.IosFiles

/**
 * Drafts on disk, one file per ship next to the other per-app files
 * ([FileDraftStore]).
 *
 * iOS used to hand App a fresh InMemoryDraftStore from inside the
 * ComposeUIViewController lambda — a new, empty store on every
 * recomposition, which is why leaving a chat lost what you'd typed.
 * One instance, created once, fixed that; this one also keeps drafts
 * across a relaunch, as the Android store does.
 */
class IosDraftStore(activeShip: () -> String?) :
    FileDraftStore(activeShip, read = { IosFiles.read(it) }, write = { name, text -> IosFiles.write(name, text) }) {

    companion object {
        /** The per-ship drafts file name — shared with the ship-data eraser. */
        fun fileFor(ship: String): String = draftsFileName(ship)
    }
}
