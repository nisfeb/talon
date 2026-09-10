package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.mail.MailRepo
import io.nisfeb.talon.ui.ContactMap

/**
 * Mail, laid out as a mail client: mailboxes, then the listing, then
 * the message.
 *
 * It takes the whole area beside the rail rather than living in the
 * chat scaffold's list slot. Three columns do not fit in thirty percent
 * of a window, and squeezing them there is what produced a mailbox
 * chooser crammed into a row of chips along the top.
 *
 * Below the width for three columns it becomes one: the listing, with
 * the mailboxes behind a control and the message replacing the list.
 * That is the same set of pieces, stacked, not a second implementation.
 */
@Composable
fun MailWorkspace(
    repo: MailRepo,
    contacts: ContactMap,
    ourShip: String,
    openThread: String?,
    onOpenThread: (String?) -> Unit,
    composing: MailIntent?,
    onCompose: (MailIntent?) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        // Enough for a mailbox column, a listing that can still show a
        // subject, and a message worth reading.
        val threeColumns = maxWidth >= 900.dp

        if (!threeColumns) {
            when {
                composing != null -> MailComposer(
                    repo = repo,
                    intent = composing,
                    onSent = { onCompose(null) },
                    onCancel = { onCompose(null) },
                )

                openThread != null -> MailThreadPane(
                    repo = repo,
                    threadId = openThread,
                    contacts = contacts,
                    ourShip = ourShip,
                    onCompose = { onCompose(it) },
                    onBack = { onOpenThread(null) },
                )

                else -> MailList(
                    repo = repo,
                    contacts = contacts,
                    onOpenThread = { onOpenThread(it) },
                    onCompose = { onCompose(MailIntent()) },
                    onOpenDraft = { d -> onCompose(draftIntent(d)) },
                )
            }
            return@BoxWithConstraints
        }

        Row(Modifier.fillMaxSize()) {
            MailList(
                repo = repo,
                contacts = contacts,
                onOpenThread = { onOpenThread(it) },
                onCompose = { onCompose(MailIntent()) },
                onOpenDraft = { d -> onCompose(draftIntent(d)) },
                modifier = Modifier.width(430.dp).fillMaxHeight(),
            )
            VerticalDivider()
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when {
                    // A message being written outranks the one it answers,
                    // so a reply cannot be lost behind its own thread.
                    composing != null -> MailComposer(
                        repo = repo,
                        intent = composing,
                        onSent = { onCompose(null) },
                        onCancel = { onCompose(null) },
                    )

                    openThread != null -> MailThreadPane(
                        repo = repo,
                        threadId = openThread,
                        contacts = contacts,
                        ourShip = ourShip,
                        onCompose = { onCompose(it) },
                        onBack = null,
                    )

                    else -> MailAbsent(
                        "Select a message to read it.",
                        actionLabel = null,
                        onAction = null,
                    )
                }
            }
        }
    }
}

internal fun draftIntent(d: io.nisfeb.talon.mail.Draft): MailIntent = MailIntent(
    prev = d.prev,
    to = d.to,
    subject = d.subject,
    body = d.body,
    draftId = d.id,
)
