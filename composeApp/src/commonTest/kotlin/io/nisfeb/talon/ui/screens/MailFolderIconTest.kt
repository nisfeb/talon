package io.nisfeb.talon.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MailOutline
import io.nisfeb.talon.mail.MailFolder
import io.nisfeb.talon.mail.MailView
import io.nisfeb.talon.ui.icons.TalonIcons
import kotlin.test.Test
import kotlin.test.assertSame

/** The mail toolbar's mailbox button shows the open folder's icon, as the folder list does. */
class MailFolderIconTest {
    @Test
    fun `each folder has the icon the list gives it`() {
        assertSame(TalonIcons.Inbox, folderIcon(MailFolder.View(MailView.INBOX)))
        assertSame(Icons.AutoMirrored.Filled.Send, folderIcon(MailFolder.View(MailView.SENT)))
        assertSame(TalonIcons.Archive, folderIcon(MailFolder.View(MailView.ARCHIVED)))
        assertSame(Icons.Filled.MailOutline, folderIcon(MailFolder.View(MailView.ALL)))
        assertSame(Icons.Filled.Edit, folderIcon(MailFolder.Drafts))
        assertSame(TalonIcons.Label, folderIcon(MailFolder.Label("receipts")))
    }
}
