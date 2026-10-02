package io.nisfeb.talon

import io.nisfeb.talon.data.latestPerConversation
import android.content.Context
import android.content.Intent
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.ui.contactMap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
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
            // Only while the app is in sight: in the background this ran
            // on every message the service took in, for a share sheet
            // nobody was opening. Coming back publishes where things stand.
            ProcessLifecycleOwner.get().lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    db.latestPerConversation()
                        .map { rows -> rows.filterNot { it.whom.startsWith("diary/") || it.whom.startsWith("notes/") } },
                    db.contactMap(),
                ) { conversations, contactMap ->
                    conversations.take(TOP_N).map { it.whom to contactMap.conversationLabel(it.whom) }
                }
                    // What a shortcut shows: the top whoms and their names.
                    // A new contact map came with any contact or group
                    // change, and each republished all five.
                    .distinctUntilChanged()
                    .collect { publish(it) }
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

    /** [top] as (whom, label). */
    private fun publish(top: List<Pair<String, String>>) {
        val icon = IconCompat.createWithResource(context, R.drawable.ic_shortcut_chat)
        val shortcuts = top.map { (whom, label) ->
            val person = Person.Builder()
                .setKey(whom)
                .setName(label)
                .setImportant(true)
                .build()

            val intent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra(Notifications.EXTRA_OPEN_WHOM, whom)
            }

            ShortcutInfoCompat.Builder(context, whom)
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
