package io.nisfeb.talon.call

/** Android's native call integration is TelecomCalls, bound by TalonApp. */
actual fun bindNativeCallActions(
    controller: CallController,
    partyLine: PartyLine?,
    nameFor: (String) -> String,
    /** Resolves a handle the system handed back (a Recents entry may
     *  carry the display name we reported) to a ship, or null. */
    shipFor: (String) -> String?,
) = Unit
