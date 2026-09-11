package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.ui.HomePlace
import io.nisfeb.talon.ui.PlaceLookup
import kotlinx.coroutines.launch

/**
 * Choosing where the dial thinks you are.
 *
 * Two routes, because one of them is not always open. A device that
 * can find itself offers to; one that cannot, or whose owner said no,
 * gets to type a place instead. Desktop is always the second case, and
 * a refused permission on a phone must land in the same place rather
 * than in a dead end.
 *
 * Coordinates are always accepted directly. Looking a name up means
 * sending it somewhere, and somebody who would rather not can still
 * say exactly where they are.
 */
@Composable
fun LocationPicker(
    current: HomePlace?,
    /** Ask the device. Null where it cannot, which is the desktop case
     *  and the refused-permission case alike. */
    onUseDevice: (suspend () -> Result<HomePlace>)?,
    /** Turn a typed place into coordinates. Null where no lookup is
     *  wired, and then only coordinates are accepted. */
    lookup: PlaceLookup?,
    onPick: (HomePlace) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<HomePlace>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Where are you?") },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (current != null) {
                    Text(
                        "Now: ${current.label}" + if (current.fromGps) " (from this device)" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (onUseDevice != null) {
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            problem = null
                            scope.launch {
                                onUseDevice().fold(
                                    onSuccess = { busy = false; onPick(it) },
                                    onFailure = {
                                        busy = false
                                        // Refusal is the ordinary case, not
                                        // an error, so it says what to do
                                        // next rather than what went wrong.
                                        problem = "Could not use this device's location. " +
                                            "Type a place instead."
                                    },
                                )
                            }
                        },
                    ) { Text("Use this device's location") }
                }

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it; problem = null },
                    label = { Text(if (lookup != null) "Town, city or postcode" else "Latitude, longitude") },
                    placeholder = { Text(if (lookup != null) "Boston" else "42.36, -71.06") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    enabled = !busy && query.isNotBlank(),
                    onClick = {
                        // Coordinates first, always. Somebody who would
                        // rather not hand their town to a lookup can still
                        // say exactly where they are.
                        val typed = parseCoordinates(query)
                        if (typed != null) {
                            onPick(typed)
                            return@TextButton
                        }
                        val l = lookup
                        if (l == null) {
                            problem = "Enter coordinates as latitude, longitude."
                            return@TextButton
                        }
                        busy = true
                        problem = null
                        scope.launch {
                            l(query).fold(
                                onSuccess = {
                                    busy = false
                                    results = it
                                    if (it.isEmpty()) problem = "Nowhere by that name."
                                },
                                onFailure = {
                                    busy = false
                                    problem = "Could not look that up: ${it.message}"
                                },
                            )
                        }
                    },
                ) { Text(if (lookup != null) "Search" else "Set") }

                if (busy) CircularProgressIndicator(Modifier.padding(4.dp))
                problem?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                results.forEach { p ->
                    Column(
                        Modifier.fillMaxWidth().clickable { onPick(p) }.padding(vertical = 6.dp),
                    ) {
                        Text(
                            p.label,
                            style = MaterialTheme.typography.bodyMedium
                                .copy(fontWeight = FontWeight.Medium),
                        )
                        Text(
                            coordLabel(p),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/**
 * "42.36, -71.06", with or without the space, and either order of
 * signs. Null when it is not a pair of coordinates, which is the
 * signal to try a name lookup instead.
 */
internal fun parseCoordinates(s: String): HomePlace? {
    val parts = s.split(",", " ").map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.size != 2) return null
    val lat = parts[0].toDoubleOrNull() ?: return null
    val lon = parts[1].toDoubleOrNull() ?: return null
    if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
    return HomePlace(lat, lon, label = coordText(lat, lon), fromGps = false)
}

internal fun coordLabel(p: HomePlace): String = coordText(p.lat, p.lon) +
    (p.elevationMetres?.let { ", ${it.toInt()} m" } ?: "")

private fun coordText(lat: Double, lon: Double): String {
    fun one(v: Double, pos: String, neg: String): String {
        val d = if (v < 0) -v else v
        val whole = d.toInt()
        val frac = ((d - whole) * 100).toInt()
        val f = if (frac < 10) "0$frac" else "$frac"
        return "$whole.$f${if (v < 0) neg else pos}"
    }
    return "${one(lat, "N", "S")} ${one(lon, "E", "W")}"
}
