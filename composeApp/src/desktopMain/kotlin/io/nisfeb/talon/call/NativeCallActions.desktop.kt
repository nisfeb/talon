package io.nisfeb.talon.call

/** No native call UI on desktop. */
actual fun bindNativeCallActions(
    controller: CallController,
    partyLine: PartyLine?,
    nameFor: (String) -> String,
    /** Resolves a handle the system handed back (a Recents entry may
     *  carry the display name we reported) to a ship, or null. */
    shipFor: (String) -> String?,
) = Unit
