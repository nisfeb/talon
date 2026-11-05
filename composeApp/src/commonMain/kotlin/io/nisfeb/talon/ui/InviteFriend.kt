package io.nisfeb.talon.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.urbit.TalonLink

/**
 * Inviting a friend to Groundwire: one message that carries everything
 * they need (where to get the app, and who to add once they have it),
 * handed to the system share sheet so it goes out by whatever the friend
 * already uses, to whoever the owner picks there. The share sheet is also
 * where the owner's contacts already are (recent people, Messages, mail),
 * so the app asks for no contacts permission of its own.
 */

/** Where a friend gets Groundwire. One place to change when there is a landing page. */
const val GROUNDWIRE_INSTALL_URL = "https://github.com/nisfeb/talon/releases/latest"

/** The whole invitation, ready to paste or send. [name] is how the friend knows the owner, if set. */
fun groundwireInviteMessage(ship: String, name: String? = null, installUrl: String = GROUNDWIRE_INSTALL_URL): String {
    val who = name?.trim()?.takeIf { it.isNotEmpty() && it != ship }
    val hello = if (who != null) "It's $who. " else ""
    return buildString {
        append(hello)
        append("I'm on Groundwire, a private messenger where your messages live on a server you own. Come chat with me there.\n\n")
        append("1. Get Groundwire: ").append(installUrl).append(" (on iPhone, it is Talon on the App Store)\n")
        // Only the ship: no system registers the app's talon:// links, so
        // tapped in the friend's messages app one opened nothing.
        append("2. Add me: ").append(ship)
    }
}

/**
 * Hands text to the platform's share sheet. Android wires the system
 * chooser; a host with no share sheet (desktop, or a platform not wired
 * yet) leaves [NoopTextSharer], which says it could not, and the caller
 * copies the text instead.
 */
fun interface TextSharer {
    /** True when a share sheet opened with [text]. */
    fun share(text: String, subject: String): Boolean
}

val NoopTextSharer = TextSharer { _, _ -> false }

/** The host's share sheet; Android provides its chooser. */
val LocalTextSharer = staticCompositionLocalOf { NoopTextSharer }

/**
 * One tap to invite: the share sheet where there is one, else the
 * message is copied. Returns what to tell the owner, or null when the
 * share sheet took over.
 */
@Composable
fun rememberInviteFriend(ship: String?, name: String? = null): () -> String? {
    val sharer = LocalTextSharer.current
    val clipboard = LocalClipboardManager.current
    return remember(ship, name, sharer) {
        {
            if (ship == null) {
                "Sign in to a ship first: the invite says who to add."
            } else {
                val text = groundwireInviteMessage(ship, name)
                if (sharer.share(text, "Join me on Groundwire")) null
                else {
                    clipboard.setText(AnnotatedString(text))
                    "Invite copied. Paste it to a friend in any app."
                }
            }
        }
    }
}

/**
 * The invite card at the top of Invites: what it does in a line, one
 * button that sends it, a copy of the message, and a QR to scan in
 * person.
 */
@Composable
fun InviteFriendCard(ship: String?, name: String? = null, modifier: Modifier = Modifier) {
    val invite = rememberInviteFriend(ship, name)
    val clipboard = LocalClipboardManager.current
    var note by remember(ship) { mutableStateOf<String?>(null) }
    var showQr by remember(ship) { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Invite a friend to Groundwire",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            )
            Text(
                "Sends one message with everything they need: where to get the app, and your ship to add.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = ship != null, onClick = { note = invite() }) { Text("Invite a friend") }
                if (ship != null) {
                    OutlinedButton(onClick = {
                        clipboard.setText(AnnotatedString(groundwireInviteMessage(ship, name)))
                        note = "Invite copied."
                    }) { Text("Copy invite") }
                    TextButton(onClick = { showQr = !showQr }) { Text(if (showQr) "Hide QR" else "Show QR") }
                }
            }
            note?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            if (showQr && ship != null) {
                // In person: their camera opens the download page.
                ShareQr(
                    link = GROUNDWIRE_INSTALL_URL,
                    title = "Get Groundwire",
                    caption = "Scan to download, then add $ship.",
                    fileName = "groundwire-invite",
                )
            }
        }
    }
}
