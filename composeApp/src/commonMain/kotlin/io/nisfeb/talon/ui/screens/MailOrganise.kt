package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.mail.MailRepo
import io.nisfeb.talon.mail.MailingList
import io.nisfeb.talon.mail.Rule
import io.nisfeb.talon.mail.isShip
import io.nisfeb.talon.mail.newDraftId
import io.nisfeb.talon.mail.parseRecipients
import kotlinx.coroutines.launch

/**
 * Filters and mailing lists.
 *
 * Both are local and unsigned: neither travels, and two ships holding
 * the same thread will disagree about all of it. That is why they can
 * be edited freely here and why nothing waits on the network to agree.
 *
 * A filter matches on a sender, a subject substring, or both, and
 * answers by labelling and optionally archiving. One with neither is
 * refused by the ship, because it would match every message ever sent.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MailOrganiseSheet(repo: MailRepo, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val rules by repo.rules.collectAsState()
    val lists by repo.lists.collectAsState()

    LaunchedEffect(repo) {
        repo.refreshRules()
        repo.refreshLists()
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionTitle("Filters")
            Text(
                "Applied to mail as it arrives. A filter needs a sender or a " +
                    "subject; one with neither would match everything.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            rules.forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(ruleLine(r), style = MaterialTheme.typography.bodySmall)
                        if (r.add.isNotEmpty() || r.archive) {
                            Text(
                                ruleAction(r),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    TextButton(onClick = { scope.launch { repo.deleteRule(r.id) } }) {
                        Text("Delete")
                    }
                }
            }
            NewRule { r -> scope.launch { repo.saveRule(r) } }

            HorizontalDivider(Modifier.padding(vertical = 6.dp))

            SectionTitle("Lists")
            Text(
                "A name for a set of ships, used when addressing. The name " +
                    "never travels; a message carries the ships.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            lists.forEach { l ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(l.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (l.members.isEmpty()) "empty" else l.members.joinToString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { scope.launch { repo.deleteList(l.name) } }) {
                        Text("Delete")
                    }
                }
            }
            NewList { l -> scope.launch { repo.saveList(l) } }
        }
    }
}

internal fun ruleLine(r: Rule): String = listOfNotNull(
    r.from?.let { "from $it" },
    r.subject?.takeIf { it.isNotBlank() }?.let { "subject contains \"$it\"" },
).joinToString(" and ").ifBlank { "matches nothing" }

internal fun ruleAction(r: Rule): String = listOfNotNull(
    r.add.takeIf { it.isNotEmpty() }?.let { "label ${it.joinToString()}" },
    "archive".takeIf { r.archive },
).joinToString(", ")

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun NewRule(onSave: (Rule) -> Unit) {
    var from by remember { mutableStateOf("") }
    var subject by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var archive by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = from,
                onValueChange = { from = it },
                label = { Text("From") },
                placeholder = { Text("~sampel-palnet") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = subject,
                onValueChange = { subject = it },
                label = { Text("Subject contains") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Add label") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Checkbox(checked = archive, onCheckedChange = { archive = it })
            Text("Archive", style = MaterialTheme.typography.bodySmall)
        }
        problem?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
        TextButton(
            onClick = {
                val f = from.trim().takeIf { it.isNotBlank() }
                val s = subject.trim().takeIf { it.isNotBlank() }
                when {
                    f == null && s == null ->
                        problem = "Give it a sender or a subject, or it matches everything."
                    f != null && !isShip(f) -> problem = "$f is not a ship."
                    label.isNotBlank() && !labelOk(label.trim()) ->
                        problem = "A label is lower-case letters, digits and dashes."
                    else -> {
                        problem = null
                        onSave(
                            Rule(
                                id = newDraftId(),
                                from = f,
                                subject = s,
                                add = listOfNotNull(label.trim().takeIf { it.isNotBlank() }),
                                archive = archive,
                            ),
                        )
                        from = ""; subject = ""; label = ""; archive = false
                    }
                }
            },
        ) { Text("Add filter") }
    }
}

/** The ship's rule for a label, checked here so a refusal arrives at
 *  the keystroke rather than from a route that already answered. */
internal fun labelOk(s: String): Boolean =
    s.isNotEmpty() && s.length <= 32 && s.first() in 'a'..'z' &&
        s.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }

@Composable
private fun NewList(onSave: (MailingList) -> Unit) {
    var name by remember { mutableStateOf("") }
    var members by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = members,
                onValueChange = { members = it },
                label = { Text("Ships") },
                placeholder = { Text("~zod ~sampel-palnet") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        problem?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
        TextButton(
            onClick = {
                val n = name.trim()
                val (good, bad) = parseRecipients(members)
                when {
                    // The name becomes a path segment on the ship, so its
                    // rule is stricter than a label's.
                    !labelOk(n) -> problem = "A name is lower-case letters, digits and dashes."
                    bad.isNotEmpty() -> problem = "Not a ship: ${bad.joinToString(", ")}"
                    good.isEmpty() -> problem = "Put at least one ship in it."
                    else -> {
                        problem = null
                        onSave(MailingList(n, good))
                        name = ""; members = ""
                    }
                }
            },
        ) { Text("Add list") }
    }
}

/** Put a label on a thread, or take one off, from the reader. */
@Composable
fun MailLabelRow(
    labels: List<String>,
    known: List<String>,
    onToggle: (String, Boolean) -> Unit,
) {
    var adding by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            labels.forEach { l ->
                AssistChip(onClick = { onToggle(l, false) }, label = { Text(l) })
            }
            known.filter { it !in labels }.take(3).forEach { l ->
                AssistChip(
                    onClick = { onToggle(l, true) },
                    label = { Text("+ $l", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = adding,
                onValueChange = { adding = it },
                label = { Text("Label") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                enabled = labelOk(adding.trim()),
                onClick = { onToggle(adding.trim(), true); adding = "" },
            ) { Text("Add") }
        }
    }
}
