package io.nisfeb.talon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nisfeb.talon.ai.CardDay
import io.nisfeb.talon.ai.CardMail
import io.nisfeb.talon.ai.CardMessage
import io.nisfeb.talon.ai.CardProposal
import io.nisfeb.talon.ai.CardTask
import io.nisfeb.talon.ai.DictationCard
import io.nisfeb.talon.ui.icons.TalonIcons

/**
 * What an answer read, drawn as Talon shows it, and what orrery left
 * waiting: the dictation card's and the Assistant's, the same cards on
 * both. A tap goes to [open] with where the row lives: a [dmLink] for
 * someone, [CALENDAR_LINK] for the calendar's rows, a talon:// address
 * for a message or mail. Each host maps those.
 *
 * Made to be read in a car first (sneagan, 2026-10-16: "the cards and
 * text are too small"): large type and few words.
 */
@Composable
fun AnswerCard(card: DictationCard, open: (String) -> Unit) {
    if (card.people.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { card.people.forEach { Person(it.name, it.ship, open) } }
    card.days.forEach { Day(it, open) }
    if (card.tasks.isNotEmpty()) Tasks(card.tasks, open)
    if (card.messages.isNotEmpty()) Messages(card.messages, open)
    if (card.mail.isNotEmpty()) Mail(card.mail, open)
}

/** What a tap on a calendar row opens. */
const val CALENDAR_LINK = "calendar:"

/** One thing orrery would do, drawn as what it is. */
@Composable
fun ProposalCard(p: CardProposal, open: (String) -> Unit) {
    when (p.kind) {
        "message" -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            p.to?.let { Person(it, null, open) }
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(topStart = 6.dp, topEnd = 22.dp, bottomEnd = 22.dp, bottomStart = 22.dp), modifier = Modifier.fillMaxWidth()) {
                Text(p.text ?: p.title, fontSize = 24.sp, lineHeight = 32.sp, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(18.dp))
            }
        }
        "calendar" -> Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(18.dp)).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(Icons.Filled.DateRange, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(p.title, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium)
                p.time?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) }
                p.place?.let { Place(it) }
            }
        }
        "task" -> Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(18.dp)).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Column {
                Text(p.title, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium)
                p.time?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        else -> Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Text(p.title, fontSize = 22.sp, lineHeight = 28.sp, modifier = Modifier.padding(16.dp))
        }
    }
}

/** Someone, with a face of their initial; tapped, their chat. */
@Composable
private fun Person(name: String, ship: String?, open: (String) -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable(enabled = ship != null) { ship?.let { open(dmLink(it)) } }
            .padding(start = 6.dp, end = 18.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
            Text(name.trimStart('~').take(1).uppercase(), color = MaterialTheme.colorScheme.onPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Column {
            Text(name, fontSize = 20.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSecondaryContainer)
            if (ship != null && ship != name) Text(ship, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f))
        }
    }
}

@Composable
private fun Section(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Text(title, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.primary)
        }
        content()
    }
}

@Composable
private fun Messages(messages: List<CardMessage>, open: (String) -> Unit) = Section("Messages", TalonIcons.Chat) {
    messages.forEach { m ->
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .clickable { open(m.link) }.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("${m.author}  ·  ${m.where}  ·  ${m.time}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(m.text, fontSize = 19.sp, lineHeight = 26.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Mail(mail: List<CardMail>, open: (String) -> Unit) = Section("Mail", Icons.Filled.Email) {
    mail.forEach { t ->
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .clickable { open(t.link) }.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t.from, style = MaterialTheme.typography.titleMedium.copy(fontWeight = if (t.unread) FontWeight.Bold else FontWeight.Normal), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(t.time, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(t.subject, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = if (t.unread) FontWeight.SemiBold else FontWeight.Normal, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (t.snippet.isNotBlank()) Text(t.snippet, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Place(place: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(Icons.Filled.Place, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Text(place, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Day(day: CardDay, open: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.DateRange, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Text(day.label, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.primary)
        }
        day.events.forEach { e ->
            Surface(
                onClick = { open(CALENDAR_LINK) },
                color = if (e.now) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth().alpha(if (e.over) 0.55f else 1f),
            ) {
                Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.width(6.dp).height(52.dp).background(e.color?.let(::parseHexColor) ?: MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp)))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            if (e.now) "${e.time}  ·  now" else e.time,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = if (e.now) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(e.name, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium)
                        if (e.place.isNotBlank()) Place(e.place)
                    }
                }
            }
        }
    }
}

@Composable
private fun Tasks(tasks: List<CardTask>, open: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Text("Tasks", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.primary)
        }
        tasks.forEach { t ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.clickable { open(CALENDAR_LINK) }.padding(horizontal = 4.dp, vertical = 4.dp)) {
                Box(Modifier.size(18.dp).background(MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), CircleShape))
                Text(t.name, fontSize = 20.sp, lineHeight = 26.sp, modifier = Modifier.weight(1f))
                t.due?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (t.overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
