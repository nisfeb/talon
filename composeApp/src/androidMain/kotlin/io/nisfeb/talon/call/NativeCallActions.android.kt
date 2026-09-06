package io.nisfeb.talon.call

/** Android's native call integration is TelecomCalls, bound by TalonApp. */
actual fun bindNativeCallActions(
    controller: CallController,
    partyLine: PartyLine?,
    nameFor: (String) -> String,
) = Unit
