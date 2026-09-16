package io.nisfeb.talon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.call.saveFile
import io.nisfeb.talon.login.QrCodeGenerator
import io.nisfeb.talon.login.qrPng
import io.nisfeb.talon.ui.screens.QrCodeMatrix
import kotlinx.coroutines.launch

/**
 * A Talon link as a code another phone scans, with the link itself to
 * copy and the image to save for posting elsewhere.
 */
@Composable
fun ShareQr(
    link: String,
    title: String,
    caption: String,
    fileName: String,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var note by remember(link) { mutableStateOf<String?>(null) }
    val matrix = remember(link) { runCatching { QrCodeGenerator.generate(link) }.getOrNull() } ?: return
    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.fillMaxWidth())
        Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
        // White with a margin in any theme: a scanner needs the light quiet zone.
        Box(
            Modifier
                .widthIn(max = 240.dp)
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White)
                .padding(16.dp),
        ) {
            QrCodeMatrix(matrix = matrix, foreground = Color.Black, background = Color.White, modifier = Modifier.fillMaxSize())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { clipboard.setText(AnnotatedString(link)); note = "Link copied." }) { Text("Copy link") }
            TextButton(onClick = {
                scope.launch {
                    note = saveFile(qrPng(matrix), fileName, "png", "image/png")?.let { "Saved to $it." }
                        ?: "Could not save the image."
                }
            }) { Text("Save image") }
        }
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
