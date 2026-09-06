package io.nisfeb.talon.call

/** No native call UI on desktop. */
actual fun bindNativeCallActions(
    controller: CallController,
    partyLine: PartyLine?,
    nameFor: (String) -> String,
) = Unit
