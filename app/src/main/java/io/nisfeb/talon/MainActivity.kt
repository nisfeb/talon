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
    private val pendingShare = mutableStateOf<ShareIntent?>(null)

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
            val share by pendingShare
            TalonTheme {
                TalonApp(
                    initialOpenWhom = whom,
                    initialScrollMessageId = messageId,
                    pendingShare = share,
                    onShareConsumed = { pendingShare.value = null },
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
        ShareIntent.from(intent)?.let { pendingShare.value = it }
    }

    private val requestNotificationsPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* ignore — nothing to do either way */ }
}
