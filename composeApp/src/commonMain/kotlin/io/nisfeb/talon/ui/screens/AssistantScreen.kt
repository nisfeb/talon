package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.ai.AgentClient
import io.nisfeb.talon.ai.AgentLoop
import io.nisfeb.talon.ai.AiClient
import io.nisfeb.talon.ai.AiSettingsRepository
import io.nisfeb.talon.ai.AskUrbit
import io.nisfeb.talon.ai.McpClient
import io.nisfeb.talon.ai.mcpAgentTools
import io.nisfeb.talon.ai.SearchEmbedderClient
import io.nisfeb.talon.ai.Tool
import io.nisfeb.talon.ai.ToolCall
import io.nisfeb.talon.ai.ToolCatalog
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.AssistantHistoryEntity
import io.nisfeb.talon.ui.ContactMap
import io.nisfeb.talon.ui.MentionPicker
import io.nisfeb.talon.ui.contactMapFlow
import io.nisfeb.talon.ui.detectMentionQuery
import io.nisfeb.talon.ui.shortRelativeTime
import io.nisfeb.talon.ui.suggestionsFor
import io.nisfeb.talon.urbit.TlonChatRepo
import io.nisfeb.talon.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

/**
 * Talon Assistant (docs/assistant.md). Two opt-in modes share one screen:
 *
 *  - **Ask** (Phase 1): grounded Q&A over the user's history. Retrieval
 *    is on-device; only the question + matched excerpts hit the LLM.
 *  - **Act** (Phase 2): an agent that runs tools. Reads run freely;
 *    every write surfaces a confirmation card before it executes.
 *
 * Each mode appears only when its feature flag is on and a key is set,
 * so the screen (and its entry point) stay invisible during rc rollout.
 */
private enum class Mode { Ask, Act }

/** A line in the Act-mode transcript. */
private sealed interface Line {
    data class You(val text: String) : Line
    data class Note(val text: String) : Line
    data class Said(val text: String) : Line
}

private data class Pending(val call: ToolCall, val tool: Tool, val gate: CompletableDeferred<Boolean>)

/** How many past exchanges to retain. "~50 or so, maybe more." */
private const val HISTORY_KEEP = 100

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantScreen(
    db: AppDatabase,
    aiSettings: AiSettingsRepository,
    embedder: SearchEmbedderClient?,
    onBack: () -> Unit,
    onOpenMessage: (whom: String, postId: String, parentId: String?) -> Unit,
    repo: TlonChatRepo? = null,
    modifier: Modifier = Modifier,
) {
    val aiState by aiSettings.state.collectAsState()
    val contactMap by remember {
        contactMapFlow(
            db.contacts().stream(),
            db.clubs().stream(),
            db.groups().streamGroups(),
            db.groups().streamChannelGroups(),
        )
    }.collectAsState(initial = ContactMap.EMPTY)
    val scope = rememberCoroutineScope()

    val askUrbit = remember(aiSettings, embedder) {
        embedder?.let { AskUrbit(AiClient { aiSettings.state.value }, it) }
    }
    val agentClient = remember(aiSettings) { AgentClient { aiSettings.state.value } }

    // MCP: if the user opted in (and is in Act mode) and the ship exposes
    // an /mcp endpoint, discover its tools and hand them to the agent.
    // Read tools run free; pokes are confirm-gated; eval/install stay
    // hidden (see McpTools). Discovery doubles as the gate — a ship with
    // no MCP server just yields no tools.
    val mcpClient = remember(repo, aiState.mcpEnabled, aiState.agentEnabled) {
        val http = repo?.shipHttp
        val base = repo?.shipBaseUrl
        if (aiState.mcpEnabled && aiState.agentEnabled && http != null && base != null) {
            McpClient(http, base)
        } else {
            null
        }
    }
    var mcpTools by remember { mutableStateOf<List<Tool>>(emptyList()) }
    // Surfaced in the UI + logged, so a failed connect is distinguishable
    // from "no server" (it used to silently collapse to an empty list).
    var mcpStatus by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(mcpClient) {
        val client = mcpClient
        if (client == null) {
            mcpTools = emptyList()
            mcpStatus = null
            return@LaunchedEffect
        }
        mcpStatus = "Connecting to ship tools…"
        runCatching {
            client.initialize()
            client.listTools()
        }.onSuccess { defs ->
            val tools = mcpAgentTools(client, defs)
            mcpTools = tools
            val hidden = defs.size - tools.size
            mcpStatus = "Ship tools: ${tools.size} available" +
                if (hidden > 0) " · $hidden hidden" else ""
        }.onFailure { e ->
            mcpTools = emptyList()
            mcpStatus = "Ship tools unavailable: ${e.message ?: e::class.simpleName}"
            Log.w("AssistantScreen", "MCP tool load failed", e)
        }
    }

    val agentLoop = remember(aiSettings, embedder, repo, contactMap, mcpTools) {
        if (repo != null && embedder != null) {
            AgentLoop(
                completer = { sys, msgs, tools -> agentClient.completeWithTools(sys, msgs, tools) },
                tools = ToolCatalog.default(repo, db, embedder) { contactMap.displayName(it) } + mcpTools,
            )
        } else null
    }

    val askOn = aiState.askUrbitEnabled && askUrbit != null
    val actOn = aiState.agentEnabled && agentLoop != null
    var mode by remember(askOn, actOn) {
        mutableStateOf(if (actOn && !askOn) Mode.Act else Mode.Ask)
    }

    var questionField by remember { mutableStateOf(TextFieldValue("")) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Ask-mode result.
    var answer by remember { mutableStateOf<AskUrbit.Answer?>(null) }
    // Act-mode transcript + the in-flight write awaiting confirmation.
    val transcript = remember { mutableStateListOf<Line>() }
    var pending by remember { mutableStateOf<Pending?>(null) }

    // @p autocomplete: candidate ships are the contact book. Mirrors the
    // chat composer (ChatComposer.kt) so referring to a ship feels the
    // same here as when typing a message.
    val allShips = remember(contactMap) { contactMap.contacts.map { it.ship } }

    // Local reference log of past exchanges (docs/assistant.md).
    val historyDao = remember(db) { db.assistantHistory() }
    val history by historyDao.recent(HISTORY_KEEP).collectAsState(initial = emptyList())
    var expandedHistoryId by remember { mutableStateOf<Long?>(null) }

    suspend fun record(mode: Mode, question: String, answerText: String) {
        historyDao.insert(
            AssistantHistoryEntity(
                mode = if (mode == Mode.Act) "Act" else "Ask",
                question = question,
                answer = answerText,
                createdAt = System.currentTimeMillis(),
            ),
        )
        historyDao.trim(HISTORY_KEEP)
    }

    fun submitAsk(q: String) {
        val ask = askUrbit ?: return
        busy = true; error = null; answer = null
        scope.launch {
            runCatching { ask.ask(q, displayName = { contactMap.displayName(it) }) }
                .onSuccess { answer = it; record(Mode.Ask, q, it.text) }
                .onFailure { error = it.message ?: it::class.simpleName }
            busy = false
        }
    }

    fun submitAct(q: String) {
        val loop = agentLoop ?: return
        busy = true; error = null
        transcript.add(Line.You(q))
        var finalAnswer = ""
        scope.launch {
            runCatching {
                loop.run(
                    question = q,
                    confirm = { call, tool ->
                        val gate = CompletableDeferred<Boolean>()
                        pending = Pending(call, tool, gate)
                        val ok = gate.await()
                        pending = null
                        ok
                    },
                    onEvent = { ev ->
                        if (ev is AgentLoop.Event.Answer) finalAnswer = ev.text
                        transcript.add(ev.toLine())
                    },
                )
                record(Mode.Act, q, finalAnswer.ifBlank { "(no reply)" })
            }.onFailure { error = it.message ?: it::class.simpleName }
            busy = false
        }
    }

    fun submit() {
        val q = questionField.text.trim()
        if (q.isEmpty() || busy) return
        questionField = TextFieldValue("")
        when (mode) {
            Mode.Ask -> submitAsk(q)
            Mode.Act -> submitAct(q)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Assistant") },
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
            if (askOn && actOn) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = mode == Mode.Ask, onClick = { mode = Mode.Ask }, label = { Text("Ask") })
                    FilterChip(selected = mode == Mode.Act, onClick = { mode = Mode.Act }, label = { Text("Act") })
                }
            }

            // MCP (ship tools) only apply to the Act agent. Steer the user
            // there if they enabled it but are in Ask mode / haven't turned
            // on actions, and otherwise show the live connection status.
            if (aiState.mcpEnabled) {
                val mcpHint = when {
                    !aiState.agentEnabled ->
                        "Ship tools (MCP) run in Assistant actions mode — turn that on too."
                    mode == Mode.Ask ->
                        "Ship tools (MCP) run in the Act tab, not Ask."
                    else -> mcpStatus
                }
                mcpHint?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            val ready = if (mode == Mode.Ask) askUrbit != null else agentLoop != null
            OutlinedTextField(
                value = questionField,
                onValueChange = { questionField = it },
                modifier = Modifier.fillMaxWidth(),
                label = {
                    Text(if (mode == Mode.Ask) "Ask about your chat history" else "Tell the assistant what to do")
                },
                enabled = ready && !busy,
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { submit() }),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Send),
            )

            // @p suggestions for the word the caret is in. Tapping inserts
            // the ship at the trigger and moves the caret past it.
            val mention = detectMentionQuery(questionField.text, questionField.selection.start)
            val suggestions = mention?.let { (q, _) -> suggestionsFor(q, contactMap, allShips) }.orEmpty()
            if (ready && !busy && mention != null && suggestions.isNotEmpty()) {
                val triggerStart = mention.second
                MentionPicker(
                    suggestions = suggestions,
                    onPick = { ship ->
                        val text = questionField.text
                        val caret = questionField.selection.start.coerceIn(0, text.length)
                        val before = text.substring(0, triggerStart)
                        val after = text.substring(caret)
                        val inserted = "$ship "
                        questionField = TextFieldValue(
                            text = before + inserted + after,
                            selection = TextRange(before.length + inserted.length),
                        )
                    },
                )
            }

            if (!ready) {
                Text(
                    "On-device search isn't available here, so the assistant can't run.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Button(onClick = { submit() }, enabled = !busy && questionField.text.isNotBlank()) {
                    Text(if (busy) "Working…" else if (mode == Mode.Ask) "Ask" else "Go")
                }
            }

            if (busy && pending == null) CircularProgressIndicator()

            error?.let {
                Text(
                    "Couldn't complete: $it",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // The write-confirmation card — the Phase 2 trust boundary.
            pending?.let { p ->
                ConfirmCard(
                    summary = describe(p.call, contactMap),
                    onAllow = { p.gate.complete(true) },
                    onDeny = { p.gate.complete(false) },
                )
            }

            if (mode == Mode.Ask) {
                answer?.let { a ->
                    Text(a.text, style = MaterialTheme.typography.bodyLarge)
                    if (a.sources.isNotEmpty()) {
                        Text("Sources", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
                        a.sources.forEach { src ->
                            AssistChip(
                                onClick = { onOpenMessage(src.whom, src.postId, null) },
                                label = { Text("[${src.index}] ${contactMap.displayName(src.author)}: ${src.snippet}") },
                            )
                        }
                    }
                }
            } else {
                transcript.forEach { line ->
                    when (line) {
                        is Line.You -> Text("You: ${line.text}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                        is Line.Note -> Text(line.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
                        is Line.Said -> Text(line.text, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }

            // Reference log of past exchanges. Tap a row to reveal its
            // answer; "Ask again" drops the question back into the field.
            if (history.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Recent", style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = {
                        scope.launch { historyDao.clearAll() }
                        expandedHistoryId = null
                    }) { Text("Clear") }
                }
                val now = remember(history) { System.currentTimeMillis() }
                history.forEach { h ->
                    HistoryRow(
                        entry = h,
                        nowMs = now,
                        expanded = expandedHistoryId == h.id,
                        onToggle = { expandedHistoryId = if (expandedHistoryId == h.id) null else h.id },
                        onAskAgain = {
                            questionField = TextFieldValue(h.question, TextRange(h.question.length))
                            mode = if (h.mode == "Act") Mode.Act else Mode.Ask
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(
    entry: AssistantHistoryEntity,
    nowMs: Long,
    expanded: Boolean,
    onToggle: () -> Unit,
    onAskAgain: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().clickable { onToggle() }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(entry.mode, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text(
                    shortRelativeTime(entry.createdAt, nowMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                entry.question,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (expanded) {
                Text(
                    entry.answer,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onAskAgain) { Text("Ask again") }
            }
        }
    }
}

@Composable
private fun ConfirmCard(summary: String, onAllow: () -> Unit, onDeny: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Allow this action?", style = MaterialTheme.typography.titleSmall)
            Text(summary, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAllow) { Text("Allow") }
                OutlinedButton(onClick = onDeny) { Text("Deny") }
            }
        }
    }
}

private fun AgentLoop.Event.toLine(): Line = when (this) {
    is AgentLoop.Event.Thinking -> Line.Note("💭 $text")
    is AgentLoop.Event.ToolStarted -> Line.Note("→ ${call.name}${if (write) " (needs confirmation)" else ""}")
    is AgentLoop.Event.ToolFinished -> Line.Note("✓ ${call.name}")
    is AgentLoop.Event.Declined -> Line.Note("✗ declined ${call.name}")
    is AgentLoop.Event.Answer -> Line.Said(text)
}

/** Human-readable summary of a proposed action, resolving patps to
 *  display names where the arg looks like one. */
private fun describe(call: ToolCall, contactMap: ContactMap): String {
    val args = call.args.entries.joinToString("\n") { (k, v) ->
        val raw = v.toString().trim('"')
        // Resolve conversation ids to titles so the user is approving a
        // legible target, not an opaque "chat/~zod/general" / "0v..." id.
        val shown = when {
            k == "whom" -> contactMap.conversationLabel(raw)
            raw.startsWith("~") -> contactMap.displayName(raw)
            else -> raw
        }
        "  $k: $shown"
    }
    return "${call.name}\n$args"
}
