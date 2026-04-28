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
fun LoginScreen(session: UrbitSession, onLoggedIn: (ship: String) -> Unit) {
    var shipUrl by remember { mutableStateOf("http://localhost:8080") }
    var code by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
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
        OutlinedTextField(
            value = shipUrl,
            onValueChange = { shipUrl = it },
            label = { Text("Ship URL") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            label = { Text("+code") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        Button(onClick = {
            status = "Connecting…"
            scope.launch {
                session.login(shipUrl, code)
                    .onSuccess { ship ->
                        status = "Connected as ~$ship"
                        onLoggedIn(ship)
                    }
                    .onFailure { err ->
                        status = "Failed: ${err::class.simpleName}: ${err.message ?: "(no message)"}"
                    }
            }
        }) { Text("Connect") }
        status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}
