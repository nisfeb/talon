package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.urbit.UrbitSession
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Raw event dump for week 1 bring-up. Opens a channel, subscribes to
 * %chat and %channels, and streams every SSE event into an on-screen
 * log. If you see JSON here, the protocol layer is alive.
 */
@Composable
fun ChatDumpScreen(session: UrbitSession) {
    val log = remember { mutableStateListOf<String>() }
    val listState = rememberLazyListState()

    LaunchedEffect(session) {
        val channel = runCatching { session.openChannel() }.getOrElse { err ->
            log.add("open channel failed: ${err.message}")
            return@LaunchedEffect
        }

        // Subscribe first — the PUT creates the channel on the ship. Only
        // then is GET /~/channel/{uid} valid for SSE; do it earlier and
        // Eyre returns 404.
        runCatching { channel.subscribe(app = "chat", path = "/v4") }
            .onFailure { log.add("chat subscribe failed: ${it.message}") }
        runCatching { channel.subscribe(app = "channels", path = "/v4") }
            .onFailure { log.add("channels subscribe failed: ${it.message}") }

        launch {
            channel.events()
                .catch { err -> log.add("stream error: ${err.message}") }
                .onEach { event ->
                    log.add(event.body.toString())
                    event.id?.let { channel.ack(it) }
                }
                .collect {}
        }
    }

    LaunchedEffect(log.size) {
        if (log.isNotEmpty()) listState.animateScrollToItem(log.size - 1)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Live wire events", style = MaterialTheme.typography.titleMedium)
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(log) { line ->
                Text(
                    line,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
