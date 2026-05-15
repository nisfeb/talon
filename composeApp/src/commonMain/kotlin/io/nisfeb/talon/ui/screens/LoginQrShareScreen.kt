package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.login.QrCodeGenerator
import io.nisfeb.talon.login.TalonLoginUri

/**
 * Login-handoff QR generator. The user (typically a host helping a
 * friend onboard, or an automated agent flow) enters the new user's
 * ship URL + +code, and the screen renders a `talon://login?...` QR
 * the new user can scan from their own Talon on Android.
 *
 * Both targets render this — Desktop is the natural surface (big
 * screen, easy to point a phone at), Android works too for hand-off
 * between two phones. Generation is JVM-side via ZXing core (wired
 * through [QrCodeGenerator]); there's no platform-specific code path
 * inside this screen.
 */
@Composable
fun LoginQrShareScreen(
    onBack: () -> Unit,
    initialUrl: String = "http://localhost:8080",
    modifier: Modifier = Modifier,
) {
    var url by remember { mutableStateOf(initialUrl) }
    var code by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    var copyStatus by remember { mutableStateOf<String?>(null) }

    // Build the URI live as the user types. Both fields must be
    // non-blank — otherwise we'd render a QR for a payload that the
    // scanner would reject on decode.
    val payload = remember(url, code) {
        val u = url.trim()
        val c = code.trim()
        if (u.isBlank() || c.isBlank()) null
        else TalonLoginUri.Payload(url = u, code = c)
    }
    val uri = remember(payload) { payload?.let(TalonLoginUri::encode) }

    Column(
        modifier = modifier.windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                "Generate login QR",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        HorizontalDivider()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Enter the ship URL and +code below. The QR is generated " +
                    "live — show it to the person logging in on Android, " +
                    "or save the deep-link URI for an agent flow.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 440.dp),
            )
            Spacer(Modifier.height(20.dp))

            // Form card — same visual idiom as LoginScreen so the
            // pages feel related when the user toggles between them.
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.widthIn(max = 440.dp).fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("Ship URL") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        label = { Text("+code") },
                        // No password masking on this screen — the
                        // person creating the QR is intentionally
                        // sharing this credential and benefits from
                        // seeing the value to catch typos.
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // QR preview. Sits inside a white-background tile so dark-
            // theme users still see a high-contrast QR (cameras look
            // for dark-on-light by convention).
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color.White,
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .fillMaxWidth()
                    .aspectRatio(1f),
            ) {
                if (uri != null) {
                    val matrix = remember(uri) {
                        runCatching { QrCodeGenerator.generate(uri) }.getOrNull()
                    }
                    if (matrix != null) {
                        QrCodeMatrix(
                            matrix = matrix,
                            foreground = Color.Black,
                            background = Color.White,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                        )
                    } else {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                "Couldn't generate QR — input too long.",
                                color = Color.Black,
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                } else {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            "Fill in both fields to see a QR.",
                            color = Color.Black.copy(alpha = 0.5f),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            uri?.let { encoded ->
                OutlinedButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(encoded))
                        copyStatus = "Copied talon:// URI to clipboard"
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text("Copy talon:// URI")
                }
                copyStatus?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

/**
 * Pure-Compose renderer for a [QrCodeGenerator] matrix. Draws one
 * rectangle per dark cell into the current [Canvas] bounds; no
 * platform-specific bitmap conversion needed. The matrix is assumed
 * square (ZXing always emits square QR matrices).
 */
@Composable
fun QrCodeMatrix(
    matrix: Array<BooleanArray>,
    foreground: Color,
    background: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(background),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (matrix.isEmpty()) return@Canvas
            val rows = matrix.size
            val cols = matrix[0].size
            val cellW = size.width / cols
            val cellH = size.height / rows
            for (y in 0 until rows) {
                val row = matrix[y]
                for (x in 0 until cols) {
                    if (row[x]) {
                        drawRect(
                            color = foreground,
                            topLeft = Offset(x * cellW, y * cellH),
                            size = Size(cellW, cellH),
                        )
                    }
                }
            }
        }
    }
}
