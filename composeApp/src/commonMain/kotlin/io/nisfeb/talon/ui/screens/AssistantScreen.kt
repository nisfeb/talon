package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.ai.AiClient
import io.nisfeb.talon.ai.AiSettingsRepository
import io.nisfeb.talon.ai.AskUrbit
import io.nisfeb.talon.ai.SearchEmbedderClient
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.ui.ContactMap
import io.nisfeb.talon.ui.contactMapFlow
import kotlinx.coroutines.launch

/**
 * "Ask your Urbit" — Phase 1 of the Talon Assistant (docs/assistant.md).
 *
 * A grounded Q&A over the user's own chat history: retrieval is on-device
 * via [SearchEmbedderClient]; only the question + matched excerpts reach
 * the user's own LLM ([AiClient]). The answer's `[n]` citations render as
 * chips that jump to the real message.
 *
 * Reached only when the user has opted in (AskUrbit feature on + a key
 * configured), so the entry point stays invisible by default during rc
 * rollout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantScreen(
    db: AppDatabase,
    aiSettings: AiSettingsRepository,
    embedder: SearchEmbedderClient?,
    onBack: () -> Unit,
    onOpenMessage: (whom: String, postId: String, parentId: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val contactMap by remember {
        contactMapFlow(
            db.contacts().stream(),
            db.clubs().stream(),
            db.groups().streamGroups(),
            db.groups().streamChannelGroups(),
        )
    }.collectAsState(initial = ContactMap.EMPTY)

    val askUrbit = remember(aiSettings, embedder) {
        embedder?.let { AskUrbit(AiClient { aiSettings.state.value }, it) }
    }
    val scope = rememberCoroutineScope()

    var question by remember { mutableStateOf("") }
    var asking by remember { mutableStateOf(false) }
    var answer by remember { mutableStateOf<AskUrbit.Answer?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        val q = question.trim()
        if (q.isEmpty() || asking || askUrbit == null) return
        asking = true
        error = null
        answer = null
        scope.launch {
            runCatching { askUrbit.ask(q, displayName = { contactMap.displayName(it) }) }
                .onSuccess { answer = it }
                .onFailure { error = it.message ?: it::class.simpleName }
            asking = false
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Ask your Urbit") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = question,
                onValueChange = { question = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Ask about your chat history") },
                placeholder = { Text("e.g. what did we decide about the launch date?") },
                enabled = askUrbit != null && !asking,
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { submit() }),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Send),
            )

            if (askUrbit == null) {
                // No on-device embedder → no retrieval. Shouldn't happen
                // on shipping platforms, but fail honestly rather than
                // pretend the feature works.
                Text(
                    "On-device search isn't available, so the assistant can't look through your history here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Button(onClick = { submit() }, enabled = !asking && question.isNotBlank()) {
                    Text(if (asking) "Thinking…" else "Ask")
                }
            }

            if (asking) {
                CircularProgressIndicator()
            }

            error?.let {
                Text(
                    "Couldn't answer: $it",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            answer?.let { a ->
                Text(a.text, style = MaterialTheme.typography.bodyLarge)
                if (a.sources.isNotEmpty()) {
                    Text(
                        "Sources",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    a.sources.forEach { src ->
                        AssistChip(
                            onClick = { onOpenMessage(src.whom, src.postId, null) },
                            label = {
                                Text("[${src.index}] ${contactMap.displayName(src.author)}: ${src.snippet}")
                            },
                        )
                    }
                }
            }
        }
    }
}
