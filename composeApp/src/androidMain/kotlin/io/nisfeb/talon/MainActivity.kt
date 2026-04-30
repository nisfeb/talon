package io.nisfeb.talon

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import io.nisfeb.talon.ui.TalonApp
import io.nisfeb.talon.ui.theme.TalonTheme

class MainActivity : ComponentActivity() {

    private val deepLinkWhom = mutableStateOf<String?>(null)
    private val deepLinkMessageId = mutableStateOf<String?>(null)
    private val deepLinkThreadParent = mutableStateOf<String?>(null)
    private val deepLinkThreadAnchor = mutableStateOf<String?>(null)
    /** Holds the ship from a Daily Digest notification tap; non-null
     *  asks TalonApp to navigate straight to DailyDigestScreen on next
     *  composition. The value is the ship the digest belongs to —
     *  stashed for completeness even though the screen reads the
     *  active ship's digest itself. */
    private val deepLinkOpenDigest = mutableStateOf<String?>(null)
    private val pendingShare = mutableStateOf<ShareIntent?>(null)
    /** When the system share sheet routes through a published Sharing
     *  Shortcut (one of the per-channel shortcuts ShortcutsPublisher
     *  emits), Android adds [Intent.EXTRA_SHORTCUT_ID] alongside
     *  ACTION_SEND. The id is the channel's `whom`, so we use it to
     *  bypass the picker and deliver the share directly. */
    private val pendingShareTarget = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        consumeIntent(intent)

        // Android 13+ gates notifications behind a runtime prompt.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) requestNotificationsPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            val whom by deepLinkWhom
            val messageId by deepLinkMessageId
            val threadParent by deepLinkThreadParent
            val threadAnchor by deepLinkThreadAnchor
            val openDigest by deepLinkOpenDigest
            val share by pendingShare
            val shareTarget by pendingShareTarget
            TalonTheme {
                TalonApp(
                    initialOpenWhom = whom,
                    initialScrollMessageId = messageId,
                    initialOpenThread = threadParent,
                    initialThreadAnchor = threadAnchor,
                    initialOpenDigest = openDigest,
                    pendingShare = share,
                    pendingShareTarget = shareTarget,
                    onShareConsumed = {
                        pendingShare.value = null
                        pendingShareTarget.value = null
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeIntent(intent)
    }

    private fun consumeIntent(intent: Intent?) {
        if (intent == null) return
        intent.getStringExtra(Notifications.EXTRA_OPEN_WHOM)?.let {
            deepLinkWhom.value = it
        }
        intent.getStringExtra(Notifications.EXTRA_SCROLL_TO_MESSAGE)?.let {
            deepLinkMessageId.value = it
        }
        intent.getStringExtra(Notifications.EXTRA_OPEN_THREAD)?.let {
            deepLinkThreadParent.value = it
        }
        intent.getStringExtra(Notifications.EXTRA_THREAD_ANCHOR)?.let {
            deepLinkThreadAnchor.value = it
        }
        intent.getStringExtra(Notifications.EXTRA_OPEN_DIGEST)?.let {
            deepLinkOpenDigest.value = it
        }
        ShareIntent.from(intent)?.let {
            pendingShare.value = it
            // Sharing Shortcut routing: Android attaches the picked
            // shortcut id to the ACTION_SEND intent. We publish one
            // per channel keyed on `whom`, so this is the conversation
            // the user already chose in the system share sheet — skip
            // the in-app picker and deliver straight there.
            intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID)?.let { id ->
                pendingShareTarget.value = id
            }
        }
    }

    private val requestNotificationsPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* ignore — nothing to do either way */ }
}
