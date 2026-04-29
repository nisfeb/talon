package io.nisfeb.talon.ui.screens

// TEMPORARY DUPLICATE of app/src/main/java/io/nisfeb/talon/ui/screens/LoginScreen.kt — sync any production fixes; remove on Stage B3
// NOTE: autofill-related imports and .semantics { contentType = ... } modifiers removed (Android-internal API, not available in commonMain)

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.urbit.UrbitSession
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(
    session: UrbitSession,
    onLoggedIn: (ship: String) -> Unit,
    notice: String? = null,
) {
    var shipUrl by remember { mutableStateOf("http://localhost:8080") }
    var code by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var connecting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Connect to your ship", style = MaterialTheme.typography.headlineSmall)
        // Recovery notice from App-level state — surfaces when
        // tryRestore failed for a saved ship so the user knows why
        // they're back at login. Self-clears once they sign in.
        notice?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        OutlinedTextField(
            value = shipUrl,
            onValueChange = { shipUrl = it },
            label = { Text("Ship URL") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            enabled = !connecting,
        )
        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            label = { Text("+code") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            enabled = !connecting,
        )
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
        ) { Text(if (connecting) "Connecting…" else "Connect") }
        status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
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
