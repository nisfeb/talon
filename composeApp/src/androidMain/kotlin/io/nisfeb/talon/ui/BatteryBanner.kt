// TEMPORARY DUPLICATE: actual for the commonMain
// `BatteryExemptionBanner` expect Composable. Tracks the production
// banner in app/src/main/java/io/nisfeb/talon/ui/BatteryBanner.kt
// during the CMP port; keep in lockstep until Stage F deletes app/.
package io.nisfeb.talon.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.edit

@Composable
actual fun BatteryExemptionBanner(modifier: Modifier) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("talon.prefs", Context.MODE_PRIVATE) }

    var dismissed by remember { mutableStateOf(prefs.getBoolean(KEY_DISMISSED, false)) }
    var isExempt by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }

    if (dismissed || isExempt) return

    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(
                "Keep notifications reliable",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                "Android may kill Talon in the background unless you allow it to run unrestricted.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = {
                    prefs.edit { putBoolean(KEY_DISMISSED, true) }
                    dismissed = true
                }) { Text("Not now") }
                TextButton(onClick = {
                    if (requestBatteryOptimizationsExemption(context)) {
                        prefs.edit { putBoolean(KEY_DISMISSED, true) }
                        dismissed = true
                    }
                    isExempt = isIgnoringBatteryOptimizations(context)
                }) { Text("Allow") }
            }
        }
    }
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        ?: return true
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        pm.isIgnoringBatteryOptimizations(context.packageName)
    } else true
}

@SuppressWarnings("BatteryLife")
private fun requestBatteryOptimizationsExemption(context: Context): Boolean {
    return runCatching {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    }.getOrElse {
        runCatching {
            val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallback)
        }
        false
    }
}

private const val KEY_DISMISSED = "battery_exemption_dismissed"
