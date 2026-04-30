package io.nisfeb.talon.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.ai.ActionKind
import io.nisfeb.talon.ai.DetectedAction
import io.nisfeb.talon.ai.EntityActions

/**
 * Render a row of action chips for whatever ML Kit's on-device entity
 * extractor finds in [text]. Tapping a chip dispatches the relevant
 * Android intent: dates → calendar insert, addresses → maps,
 * phone numbers → dialer, email addresses → mail composer.
 *
 * Renders nothing while extraction is in flight or when no
 * actionable entities are found, so quiet messages stay quiet.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EntityActionChips(text: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var actions by remember(text) { mutableStateOf<List<DetectedAction>>(emptyList()) }
    LaunchedEffect(text) {
        actions = EntityActions.forText(text)
    }
    if (actions.isEmpty()) return
    FlowRow(
        modifier = modifier,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
    ) {
        actions.forEach { action ->
            EntityChip(action) { dispatchIntent(context, action) }
        }
    }
}

@Composable
private fun EntityChip(action: DetectedAction, onClick: () -> Unit) {
    Text(
        text = labelFor(action),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

private fun labelFor(a: DetectedAction): String = when (a.kind) {
    ActionKind.DateTime -> "📅 ${a.span}"
    ActionKind.Address -> "🗺 ${a.span}"
    ActionKind.Phone -> "📞 ${a.span}"
    ActionKind.Email -> "✉ ${a.span}"
}

private fun dispatchIntent(context: Context, action: DetectedAction) {
    val intent = when (action.kind) {
        ActionKind.DateTime -> Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, action.span)
            .also {
                action.timestampMillis?.let { ms ->
                    it.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, ms)
                    // Default 1h event — calendar app lets the user
                    // tweak before saving.
                    it.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, ms + 3_600_000L)
                }
            }
        ActionKind.Address ->
            Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(action.span)}"))
        ActionKind.Phone ->
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:${action.span.filter { it.isDigit() || it == '+' }}"))
        ActionKind.Email ->
            Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${action.span}"))
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
