package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.orrery.OrreryError
import io.nisfeb.talon.orrery.SearchSettings
import kotlinx.coroutines.launch

/**
 * Hands the assistant's Brave Search key to orrery for its place
 * lookups (orrery 87), or turns them off. A deliberate button, as
 * sneagan asked: the key is the owner's, and orrery's lookups spend the
 * same Brave plan as the assistant's searches.
 *
 * [read] and [set] are the ship's calls (OrreryRepo.searchSettings and
 * setSearch, owner only), here so the row is tested without a ship.
 */
@Composable
internal fun OrrerySearchKeyRow(
    key: String,
    read: suspend () -> Result<SearchSettings>,
    set: suspend (enabled: Boolean, apiKey: String?) -> Result<SearchSettings>,
) {
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf<SearchSettings?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        read().fold({ settings = it }, { problem = searchProblem(it) })
    }
    fun change(enabled: Boolean) {
        busy = true
        problem = null
        scope.launch {
            set(enabled, if (enabled) key else null).fold(
                { settings = it; if (enabled && !it.keySet) problem = "Orrery took the change but holds no key." },
                { problem = searchProblem(it) },
            )
            busy = false
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val on = settings?.enabled == true && settings?.keySet == true
        if (on) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Orrery's place lookups are on.", style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(enabled = !busy, onClick = { change(false) }) { Text("Turn off orrery's place lookups") }
        } else {
            Button(
                enabled = !busy && key.isNotBlank() && settings != null,
                onClick = { change(true) },
            ) { Text("Use this key for orrery's place lookups") }
            if (key.isBlank()) Quiet("Save a Brave Search key above first.")
        }
        problem?.let { Quiet(it, error = true) }
        Quiet(
            "The assistant's searches and orrery's lookups share one Brave plan's limits. " +
                "Orrery looks up at most ${settings?.monthlyCap ?: 500} places a month. " +
                "When on, orrery sends Brave Search a place's name and your home's area, " +
                "for places missing an address, phone, hours or website, never people.",
        )
    }
}

/** What a refusal means here, in words. */
internal fun searchProblem(e: Throwable): String = when ((e as? OrreryError.Refused)?.status) {
    404 -> "Orrery on your ship is older than version 87, which adds place lookups."
    403 -> "Orrery refused: only the ship's owner can change this, and this login is not the owner's."
    null -> if (e is OrreryError.Unreachable) "Your ship did not answer." else e.message ?: "Something went wrong."
    else -> "Orrery refused: ${(e as OrreryError.Refused).reason}"
}
