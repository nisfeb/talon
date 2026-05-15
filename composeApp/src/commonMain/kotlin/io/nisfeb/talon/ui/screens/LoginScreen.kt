package io.nisfeb.talon.ui.screens

// Autofill content-type hints arrive via the Composable slot lambdas
// below. The slots take the field's onValueChange-equivalent setter
// and return a Modifier — Android wires Compose's older
// AutofillType+AutofillNode path (Autofill Framework, public-but-
// experimental in CMP 1.7) so password managers (Bitwarden, 1Password,
// Google Password Manager) can suggest the saved credential. Desktop
// passes the default `{ Modifier }` and the field is keyboard-only.

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.login.TalonLoginUri
import io.nisfeb.talon.ui.UpdateBanner
import io.nisfeb.talon.ui.talonLogoPainter
import io.nisfeb.talon.update.UpdateState
import io.nisfeb.talon.update.UpdateStatus
import io.nisfeb.talon.urbit.UrbitSession
import kotlinx.coroutines.launch

private const val GETTING_STARTED_URL = "https://urbit.org/overview/running-urbit"

@Composable
fun LoginScreen(
    session: UrbitSession,
    onLoggedIn: (ship: String) -> Unit,
    notice: String? = null,
    /** Optional Composable slot that wires Android's Autofill Framework
     *  to the ship-URL field. Receives the `(String) -> Unit` setter
     *  the user's typing would normally invoke and returns a Modifier
     *  that registers the field with the framework. Desktop passes
     *  the default `{ Modifier }`. */
    usernameAutofill: @Composable ((onFill: (String) -> Unit) -> Modifier) =
        { Modifier },
    /** Same pattern as [usernameAutofill] for the +code (password)
     *  field. */
    passwordAutofill: @Composable ((onFill: (String) -> Unit) -> Modifier) =
        { Modifier },
    /** Optional QR-scan integration. The composable is invoked with
     *  an `onResult` callback that LoginScreen wires to its own
     *  shipUrl/code state. The composable should set up its own
     *  Activity-result launcher (e.g. via
     *  `rememberLauncherForActivityResult`) and return a `() -> Unit`
     *  trigger — non-null when the platform supports scanning, null
     *  otherwise. LoginScreen renders the "Scan QR" button when the
     *  trigger is non-null.
     *
     *  Android wires this to ZXing-android-embedded's ScanContract
     *  (FOSS, no Google Play Services required — works on GrapheneOS).
     *  Desktop passes null. */
    qrScanIntegration: (@Composable (onResult: (TalonLoginUri.Payload?) -> Unit) -> (() -> Unit)?)? = null,
    /** Optional callback to open the "Generate handoff QR" screen.
     *  When non-null, LoginScreen shows a "Generate QR for someone"
     *  link below the main form so helpers/admins can build a QR
     *  with another user's credentials. Both targets support
     *  generation (ZXing core is JVM-only and works under both
     *  Compose Desktop and Compose Android). */
    onOpenShareQr: (() -> Unit)? = null,
    /** Optional update-status hook. When non-null, the global
     *  [UpdateBanner] renders above the login form so users see
     *  pending updates on cold launch — the check fires at app
     *  process start, but the banner only had a render path inside
     *  the post-login home list before this. Android passes the
     *  real instance; desktop passes null (no checker wired). */
    updateState: UpdateState? = null,
) {
    var shipUrl by remember { mutableStateOf("http://localhost:8080") }
    var code by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var connecting by remember { mutableStateOf(false) }
    var codeVisible by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    // Outer Column holds the update banner above the centered form.
    // Without it, the banner would sit inside the form column and
    // squeeze its width; placing it full-bleed at the top mirrors
    // where the home list already shows the banner once logged in.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        if (updateState != null) {
            val updateStatus by updateState.status.collectAsState()
            UpdateBanner(
                status = updateStatus,
                onTap = {
                    when (val s = updateStatus) {
                        is UpdateStatus.Available ->
                            updateState.startDownload(s.manifest)
                        is UpdateStatus.Ready ->
                            updateState.launchInstaller(s.apkPath)
                        is UpdateStatus.Failed -> {
                            val m = s.manifest ?: return@UpdateBanner
                            updateState.startDownload(m)
                        }
                        else -> Unit
                    }
                },
                onDismiss = { updateState.dismiss() },
            )
        }
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Brand mark — soft circular badge so the login screen
            // doesn't look like a generic form. Same logo asset the
            // home screen and the system Dock/launcher icon use, so
            // there's a continuous visual identity across surfaces.
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(96.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Image(
                        painter = talonLogoPainter(),
                        contentDescription = null,
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape),
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "Talon",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Native chat for Urbit",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(28.dp))

            // Recovery notice from App-level state — surfaces when
            // tryRestore failed for a saved ship so the user knows why
            // they're back at login. Self-clears once they sign in.
            notice?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                )
            }

            // Form card — keeps the inputs visually grouped against
            // the surrounding empty space, and gives the screen a
            // clear "this is the action area" affordance vs the
            // brand block above it.
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    val usernameAutofillModifier = usernameAutofill { shipUrl = it }
                    val passwordAutofillModifier = passwordAutofill { code = it }
                    OutlinedTextField(
                        value = shipUrl,
                        onValueChange = { shipUrl = it },
                        label = { Text("Ship URL") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        enabled = !connecting,
                        singleLine = true,
                        modifier = usernameAutofillModifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        label = { Text("+code") },
                        visualTransformation = if (codeVisible) VisualTransformation.None
                            else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        enabled = !connecting,
                        singleLine = true,
                        trailingIcon = {
                            IconButton(onClick = { codeVisible = !codeVisible }) {
                                Icon(
                                    imageVector = if (codeVisible) Icons.Filled.VisibilityOff
                                        else Icons.Filled.Visibility,
                                    contentDescription = if (codeVisible) "Hide code" else "Show code",
                                )
                            }
                        },
                        modifier = passwordAutofillModifier.fillMaxWidth(),
                    )
                    // The scanner trigger is composable-scoped (it
                    // registers an ActivityResult launcher via the
                    // Android slot) — invoke it OUTSIDE the OutlinedButton
                    // lambda so the launcher remembers itself across
                    // recompositions, and call it from onClick.
                    val triggerScan = qrScanIntegration?.invoke { payload ->
                        if (payload != null) {
                            shipUrl = payload.url
                            code = payload.code
                            status = "QR scanned — tap Connect to sign in."
                        } else {
                            // null = user cancelled OR the QR wasn't a
                            // talon:// login URI. Keep silent on cancel
                            // (common path) — the manual fields stay
                            // editable. A surface'd hint here on bad
                            // format would help, but distinguishing
                            // cancel from "wrong QR" would require a
                            // sentinel from the scanner; defer.
                        }
                    }
                    if (triggerScan != null) {
                        OutlinedButton(
                            onClick = {
                                status = null
                                triggerScan()
                            },
                            enabled = !connecting,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.QrCodeScanner,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.size(8.dp))
                            Text("Scan QR")
                        }
                    }
                    Button(
                        onClick = {
                            status = "Connecting…"
                            connecting = true
                            scope.launch {
                                session.login(shipUrl, code)
                                    .onSuccess { ship ->
                                        status = "Connected as ~$ship"
                                        onLoggedIn(ship)
                                    }
                                    .onFailure { err ->
                                        status = friendlyError(err)
                                    }
                                connecting = false
                            }
                        },
                        enabled = !connecting,
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = ButtonDefaults.ContentPadding,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (connecting) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.size(8.dp))
                            Text("Connecting…")
                        } else {
                            Text("Connect")
                        }
                    }
                    status?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    onOpenShareQr?.let { openShare ->
                        // "Helping someone else log in?" affordance —
                        // opens the QR generator screen. Lives inside
                        // the form card so it reads as a related action
                        // and not a stray link. Available on both
                        // targets since QR generation is JVM-only and
                        // ZXing core is wired into both leaves.
                        Text(
                            text = "Helping someone else? Generate a login QR →",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp)
                                .clickable(enabled = !connecting) { openShare() },
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // Don't-have-an-Urbit-yet hand-off. Single text node with
            // a styled-link span so it reads as one sentence rather
            // than a button. Tapping anywhere on the link span opens
            // the urbit.org getting-started page via the platform's
            // URI handler (Android Intent.ACTION_VIEW, desktop's
            // Desktop.browse).
            val linkText = remember {
                buildAnnotatedString {
                    append("Don't have an Urbit yet? ")
                    pushStringAnnotation(tag = "URL", annotation = GETTING_STARTED_URL)
                    withStyle(
                        SpanStyle(
                            color = Color.Unspecified,
                            fontWeight = FontWeight.Medium,
                        ),
                    ) {
                        append("Get one →")
                    }
                    pop()
                }
            }
            ClickableLinkText(
                text = linkText,
                primaryColor = MaterialTheme.colorScheme.primary,
                baseColor = MaterialTheme.colorScheme.onSurfaceVariant,
                onLinkTap = { url -> runCatching { uriHandler.openUri(url) } },
            )
        }
        }
    }
}

/**
 * Minimal styled-link text. ClickableText doesn't inherit the
 * surrounding theme color the way a plain Text does, so we re-stamp
 * the link span's color from the call site (where MaterialTheme is
 * accessible) before handing the AnnotatedString down.
 */
@Composable
private fun ClickableLinkText(
    text: AnnotatedString,
    primaryColor: Color,
    baseColor: Color,
    onLinkTap: (String) -> Unit,
) {
    // Re-build the AnnotatedString so the URL-tagged span gets the
    // theme's primary color. Avoids hard-coding a brand hex into the
    // composable that built the AnnotatedString upstream.
    val themed = remember(text, primaryColor, baseColor) {
        buildAnnotatedString {
            withStyle(SpanStyle(color = baseColor)) {
                append(text.text)
            }
            text.getStringAnnotations("URL", 0, text.length).forEach { ann ->
                addStyle(
                    style = SpanStyle(
                        color = primaryColor,
                        fontWeight = FontWeight.Medium,
                    ),
                    start = ann.start,
                    end = ann.end,
                )
                addStringAnnotation("URL", ann.item, ann.start, ann.end)
            }
        }
    }
    ClickableText(
        text = themed,
        style = MaterialTheme.typography.bodyMedium.copy(textAlign = TextAlign.Center),
        onClick = { offset ->
            themed.getStringAnnotations("URL", offset, offset)
                .firstOrNull()?.let { onLinkTap(it.item) }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Map common login failures to plain-English text. Falls through to
 * the underlying message for anything we don't recognise — which is
 * still better than `IllegalStateException: login HTTP 401`.
 */
private fun friendlyError(err: Throwable): String {
    val msg = err.message.orEmpty()
    return when {
        "HTTP 401" in msg || "HTTP 403" in msg -> "Wrong +code — try again"
        "UnknownHostException" in err::class.simpleName.orEmpty() ||
            "host" in msg.lowercase() && "resolve" in msg.lowercase() ->
            "Can't reach that ship URL — check it and your connection"
        "SSL" in err::class.simpleName.orEmpty() ||
            "certificate" in msg.lowercase() ->
            "TLS error connecting — the ship's certificate was rejected"
        "no urbauth cookie" in msg ->
            "Ship didn't return a session cookie — is the URL correct?"
        "ConnectException" in err::class.simpleName.orEmpty() ->
            "Connection refused — is the ship running?"
        msg.isNotBlank() -> "Couldn't sign in: $msg"
        else -> "Couldn't sign in: ${err::class.simpleName ?: "unknown error"}"
    }
}
