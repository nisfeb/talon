package io.nisfeb.talon.call

/**
 * Connect a platform's native call UI to the shared call stack.
 * Called once per controller, after the party line exists.
 *
 * Only iOS implements it: CallKit answer/end/mute/hold come in as
 * actions on the [CallController] and [PartyLine], and every call and
 * line is reported out so the system treats them as phone calls.
 * Android has telecom for the same job (TelecomCalls, bound by its
 * host); desktop has no native call UI. Both actuals are no-ops.
 */
expect fun bindNativeCallActions(
    controller: CallController,
    partyLine: PartyLine?,
    nameFor: (String) -> String,
)
