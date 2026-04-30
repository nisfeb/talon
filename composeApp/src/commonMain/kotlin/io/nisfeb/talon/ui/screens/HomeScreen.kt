// Adapted: none — pure Compose, no Android-only dependencies. Keep in
// sync with production until app/ is removed in Stage F.
package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun HomeScreen(ship: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("~$ship", style = MaterialTheme.typography.headlineSmall)
        Text(
            "DM + channel list lands in week 2.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
