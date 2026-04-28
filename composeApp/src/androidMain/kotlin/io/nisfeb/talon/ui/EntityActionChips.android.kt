// Android actual for [EntityActionChips]. The production app/ module
// renders chips from on-device ML Kit Entity Extraction; that path
// requires the EntityActions helper which still lives in app/ and
// pulls in ML Kit + Play Services dependencies. composeApp can't reach
// across the module boundary, so for now the chips are a no-op here too
// — port-d5-followup will move EntityActions into composeApp/androidMain
// and restore the real chip rendering.
package io.nisfeb.talon.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun EntityActionChips(text: String, modifier: Modifier) {
    // TODO(port-d5-followup): port EntityActions/ActionKind/DetectedAction
    // into composeApp/androidMain (ML Kit) and reinstate the real chip
    // rendering matching app/ui/EntityActionChips.kt.
    @Suppress("UNUSED_PARAMETER") text
    @Suppress("UNUSED_PARAMETER") modifier
}
