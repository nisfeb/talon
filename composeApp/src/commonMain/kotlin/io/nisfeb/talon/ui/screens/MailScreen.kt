package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.mail.MailRepo
import io.nisfeb.talon.ui.ContactMap

/**
 * Mail as one screen, for hosts with no rail: the list, a thread, and
 * the composer, with back stepping between them.
 *
 * The wide layout puts these three in the rail's list and detail panes
 * instead. This is the same pieces, stacked, so neither host carries a
 * second copy of any of them.
 */
@Composable
fun MailScreen(
    repo: MailRepo,
    contacts: ContactMap,
    ourShip: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var openThread by remember { mutableStateOf<String?>(null) }
    var composing by remember { mutableStateOf<MailIntent?>(null) }

    when {
        composing != null -> MailComposer(
            repo = repo,
            intent = composing!!,
            onSent = { composing = null },
            onCancel = { composing = null },
            modifier = modifier,
        )

        openThread != null -> MailThreadPane(
            repo = repo,
            threadId = openThread!!,
            contacts = contacts,
            ourShip = ourShip,
            onCompose = { composing = it },
            onBack = { openThread = null },
            modifier = modifier,
        )

        else -> Column(modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text(
                    "Mail",
                    style = MaterialTheme.typography.titleMedium
                        .copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            HorizontalDivider()
            MailList(
                repo = repo,
                contacts = contacts,
                onOpenThread = { openThread = it },
                onCompose = { composing = MailIntent() },
            )
        }
    }
}
