package io.nisfeb.talon

import android.content.Context
import android.content.Intent
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.ui.ContactMap
import io.nisfeb.talon.ui.contactMapFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Publishes the top-N conversations as dynamic Sharing Shortcuts so
 * Android's system share sheet offers them as direct-send targets
 * (Android 10+ behavior). Shortcut IDs are conversation `whom` values,
 * which MainActivity reads via `Notifications.EXTRA_OPEN_WHOM` to route
 * the share payload into the right chat.
 *
 * Long-lived shortcuts are a prerequisite for direct share and also
 * enable chat-bubble conversations on Android 11+.
 */
class ShortcutsPublisher(private val context: Context, private val db: AppDatabase) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            combine(
                db.messages().conversationLatest(),
                contactMapFlow(
                    db.contacts().stream(),
                    db.clubs().stream(),
                    db.groups().streamGroups(),
                    db.groups().streamChannelGroups(),
                ),
            ) { conversations, contactMap -> conversations to contactMap }
                .distinctUntilChanged { (aC, aM), (bC, bM) ->
                    // Cheap keyed comparison — only the top-N whoms drive
                    // shortcut identity. Re-publishing on every minor change
                    // is noisy and rate-limited by the system.
                    aC.take(TOP_N).map { it.whom } == bC.take(TOP_N).map { it.whom } &&
                        aM === bM
                }
                .collect { (conversations, contactMap) ->
                    publish(conversations.take(TOP_N), contactMap)
                }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        // Clear shortcuts on sign out so the previous ship's chats don't
        // leak into a different account's share sheet.
        ShortcutManagerCompat.removeAllDynamicShortcuts(context)
    }

    private fun publish(
        conversations: List<io.nisfeb.talon.data.MessageEntity>,
        contactMap: ContactMap,
    ) {
        val icon = IconCompat.createWithResource(context, R.drawable.ic_shortcut_chat)
        val shortcuts = conversations.map { m ->
            val label = contactMap.conversationLabel(m.whom)
            val person = Person.Builder()
                .setKey(m.whom)
                .setName(label)
                .setImportant(true)
                .build()

            val intent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra(Notifications.EXTRA_OPEN_WHOM, m.whom)
            }

            ShortcutInfoCompat.Builder(context, m.whom)
                .setShortLabel(label)
                .setLongLabel(label)
                .setIcon(icon)
                .setIntent(intent)
                .setPerson(person)
                .setLongLived(true)
                .setCategories(setOf(CATEGORY_SHARE))
                .build()
        }
        // Replace the full set each pass — cheaper than diffing and the
        // platform rate-limits us anyway.
        ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
    }

    companion object {
        // Must match the category declared in shortcuts.xml.
        const val CATEGORY_SHARE = "io.nisfeb.talon.category.SHARE"
        // Most launchers surface ~4 direct-share rows; 5 covers the common cases.
        private const val TOP_N = 5
    }
}
