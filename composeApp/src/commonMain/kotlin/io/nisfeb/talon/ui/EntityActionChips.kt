// Action chips surfaced from on-device ML Kit Entity Extraction
// (dates, addresses, phone numbers, emails). Android-only — desktop
// has no equivalent yet, so the desktop actual is a no-op.
//
// commonMain just declares the expect surface; the Android actual
// duplicates the production app/EntityActionChips body verbatim because
// composeApp can't depend on app/.
package io.nisfeb.talon.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun EntityActionChips(text: String, modifier: Modifier = Modifier)
