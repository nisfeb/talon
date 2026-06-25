package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import io.nisfeb.talon.ai.AgentMessage
import io.nisfeb.talon.ai.AiSettingsRepository
import io.nisfeb.talon.ai.ConversationGrouper
import io.nisfeb.talon.ai.McpClient
import io.nisfeb.talon.ai.mcpAgentTools
import io.nisfeb.talon.ai.packEmbedding
import io.nisfeb.talon.ai.SearchEmbedderClient
import io.nisfeb.talon.ai.Tool
import io.nisfeb.talon.ai.ToolCall
import io.nisfeb.talon.ai.ToolCatalog
import io.nisfeb.talon.ai.unpackEmbedding
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.AssistantConversationEntity
import io.nisfeb.talon.data.AssistantHistoryEntity
import io.nisfeb.talon.data.newGid
import io.nisfeb.talon.ui.ContactMap
import io.nisfeb.talon.ui.MarkdownText
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
 * Talon Assistant (docs/assistant.md). One opt-in agent: it answers
 * questions grounded in the user's real messages (via its search/read
 * tools) and takes actions on request. Reads run freely; every write
 * surfaces a confirmation card before it executes — that gate, not a
 * mode split, is the trust boundary. Visible only when the assistant
 * feature is on and a key is set, so it stays invisible during rollout.
 */

/** A line in the agent transcript. */
private sealed interface Line {
    data class You(val text: String) : Line
    data class Note(val text: String) : Line
    data class Said(val text: String) : Line
}

private data class Pending(val call: ToolCall, val tool: Tool, val gate: CompletableDeferred<Boolean>)

/** How many turns to retain across all conversations. */
private const val HISTORY_KEEP = 100

/** How many topic conversations to retain. */
private const val CONV_KEEP = 50

/** Max chars of the first question used as a conversation's title. */
private const val CONV_TITLE_CHARS = 60

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

    val agentClient = remember(aiSettings) { AgentClient { aiSettings.state.value } }

    // MCP: if the user opted in (and is in Act mode) and the ship exposes
    // an /mcp endpoint, discover its tools and hand them to the agent.
    // Read tools run free; pokes are confirm-gated; eval/install stay
    // hidden (see McpTools). Discovery doubles as the gate — a ship with
    // no MCP server just yields no tools.
    val mcpClient = remember(repo, aiState.mcpEnabled) {
        val http = repo?.shipHttp
        val base = repo?.shipBaseUrl
        if (aiState.mcpEnabled && http != null && base != null) {
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
        // Needs a ship session for its tools; the embedder is optional
        // (search_history degrades to keyword-only, grouping to flat).
        if (repo != null) {
            AgentLoop(
                completer = { sys, msgs, tools -> agentClient.completeWithTools(sys, msgs, tools) },
                tools = ToolCatalog.default(repo, db, embedder) { contactMap.displayName(it) } + mcpTools,
            )
        } else null
    }

    var questionField by remember { mutableStateOf(TextFieldValue("")) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // The agent transcript + the in-flight write awaiting confirmation.
    val transcript = remember { mutableStateListOf<Line>() }
    var pending by remember { mutableStateOf<Pending?>(null) }

    // @p autocomplete: candidate ships are the contact book. Mirrors the
    // chat composer (ChatComposer.kt) so referring to a ship feels the
    // same here as when typing a message.
    val allShips = remember(contactMap) { contactMap.contacts.map { it.ship } }

    // Conversation grouping (docs/assistant.md): turns are clustered into
    // topic conversations on-device. The active conversation resumes from
    // the most recent one on open so a topic can continue over time.
    val historyDao = remember(db) { db.assistantHistory() }
    val convDao = remember(db) { db.assistantConversations() }
    val conversations by convDao.recent(CONV_KEEP).collectAsState(initial = emptyList())
    var currentConvId by remember { mutableStateOf<Long?>(null) }
    var currentConvGid by remember { mutableStateOf<String?>(null) }
    var currentCentroid by remember { mutableStateOf<FloatArray?>(null) }
    var currentTurnCount by remember { mutableStateOf(0) }
    var expandedConvId by remember { mutableStateOf<Long?>(null) }
    var expandedTurns by remember { mutableStateOf<List<AssistantHistoryEntity>>(emptyList()) }
    LaunchedEffect(expandedConvId) {
        expandedTurns = expandedConvId?.let { historyDao.forConversation(it) } ?: emptyList()
    }

    LaunchedEffect(Unit) {
        convDao.mostRecent()?.let { c ->
            currentConvId = c.id
            currentConvGid = c.gid.ifBlank { null }
            currentCentroid = if (c.dim > 0) unpackEmbedding(c.centroid, c.dim) else null
            currentTurnCount = c.turnCount
            // Replay the resumed conversation into the transcript so the
            // screen shows which topic you're continuing. Without this the
            // assistant opens blank even though a conversation is active,
            // and "New" (which detaches that topic) looks like it does
            // nothing — there's no visible thread for it to clear.
            if (transcript.isEmpty()) {
                historyDao.forConversation(c.id).forEach { t ->
                    transcript.add(Line.You(t.question))
                    transcript.add(Line.Said(t.answer))
                }
            }
        }
    }

    fun submit() {
        val loop = agentLoop ?: return
        val q = questionField.text.trim()
        if (q.isEmpty() || busy) return
        questionField = TextFieldValue("")
        busy = true; error = null
        transcript.add(Line.You(q))
        var finalAnswer = ""
        scope.launch {
            runCatching {
                val qVec = embedder?.embed(q)
                // Lazily rebuild a synced conversation's centroid. A
                // conversation pulled from another device has no local
                // centroid (embeddings are device-local), so without this
                // every follow-up to it would fork a new topic. Recompute
                // once from its turns, persist, and reuse thereafter.
                val activeConvId = currentConvId
                if (qVec != null && activeConvId != null && currentCentroid == null && embedder != null) {
                    val turns = historyDao.forConversation(activeConvId)
                    val rebuilt = ConversationGrouper.centroidOf(turns.mapNotNull { embedder.embed(it.question) })
                    if (rebuilt != null) {
                        currentCentroid = rebuilt
                        convDao.get(activeConvId)?.let {
                            convDao.update(it.copy(centroid = packEmbedding(rebuilt), dim = rebuilt.size))
                        }
                    }
                }
                // Continue the active topic if this question is on-topic,
                // else start fresh — which also resets the model's context.
                val continuing = currentConvId != null &&
                    ConversationGrouper.continues(qVec, currentCentroid)
                val priorTurns = if (continuing) {
                    historyDao.forConversation(currentConvId!!)
                        .takeLast(ConversationGrouper.CONTEXT_TURNS)
                        .flatMap {
                            listOf(
                                AgentMessage.User(it.question),
                                AgentMessage.Assistant(it.answer, emptyList()),
                            )
                        }
                } else {
                    emptyList()
                }

                loop.run(
                    question = q,
                    priorTurns = priorTurns,
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

                val now = System.currentTimeMillis()
                val vec = qVec ?: FloatArray(0)
                val convEntity: AssistantConversationEntity = if (continuing) {
                    val gid = currentConvGid ?: newGid().also { currentConvGid = it }
                    val merged = currentCentroid
                        ?.let { ConversationGrouper.updateCentroid(it, currentTurnCount, vec) }
                        ?: vec
                    currentCentroid = merged.takeIf { it.isNotEmpty() }
                    currentTurnCount += 1
                    val base = currentConvId?.let { convDao.get(it) }
                    if (base != null) {
                        val updated = base.copy(
                            gid = gid,
                            updatedAt = now,
                            turnCount = currentTurnCount,
                            centroid = packEmbedding(merged),
                            dim = merged.size,
                        )
                        convDao.update(updated)
                        updated
                    } else {
                        // The conversation row was trimmed away under us;
                        // recreate it (same gid) so the turn isn't orphaned.
                        val row = AssistantConversationEntity(
                            gid = gid, title = q.take(CONV_TITLE_CHARS).trim(),
                            createdAt = now, updatedAt = now,
                            centroid = packEmbedding(merged), dim = merged.size,
                            turnCount = currentTurnCount,
                        )
                        val newId = convDao.insert(row)
                        currentConvId = newId
                        row.copy(id = newId)
                    }
                } else {
                    val gid = newGid()
                    val row = AssistantConversationEntity(
                        gid = gid,
                        title = q.take(CONV_TITLE_CHARS).trim(),
                        createdAt = now,
                        updatedAt = now,
                        centroid = packEmbedding(vec),
                        dim = vec.size,
                        turnCount = 1,
                    )
                    val id = convDao.insert(row)
                    currentConvId = id
                    currentConvGid = gid
                    currentCentroid = vec.takeIf { it.isNotEmpty() }
                    currentTurnCount = 1
                    row.copy(id = id)
                }
                convDao.trim(CONV_KEEP)

                val turnEntity = AssistantHistoryEntity(
                    gid = newGid(),
                    mode = "Assistant",
                    question = q,
                    answer = finalAnswer.ifBlank { "(no reply)" },
                    createdAt = now,
                    conversationId = convEntity.id,
                    convGid = convEntity.gid,
                )
                historyDao.insert(turnEntity)
                historyDao.trim(HISTORY_KEEP)

                // Replicate to the user's other devices. No-op without a
                // synced ship session; embeddings stay local (not pushed).
                runCatching { repo?.settingsSync?.pushAssistantTurn(convEntity, turnEntity) }
            }.onFailure { error = it.message ?: it::class.simpleName }
            busy = false
        }
    }

    fun newConversation() {
        currentConvId = null
        currentCentroid = null
        currentTurnCount = 0
        transcript.clear()
        error = null
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
                actions = {
                    // Manual override for the topic heuristic: drop the
                    // active conversation so the next question starts fresh.
                    // Disabled when there's nothing to reset (no live thread
                    // and no active conversation), so it never looks inert.
                    TextButton(
                        onClick = { newConversation() },
                        enabled = !busy && (transcript.isNotEmpty() || currentConvId != null),
                    ) {
                        Text("New")
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
            // Ship-tools (MCP) connection status, when opted in.
            if (aiState.mcpEnabled) {
                mcpStatus?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            val ready = agentLoop != null
            OutlinedTextField(
                value = questionField,
                onValueChange = { questionField = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Ask or tell your assistant…") },
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
                    "The assistant needs an active ship session to run. Sign in and try again.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Button(onClick = { submit() }, enabled = !busy && questionField.text.isNotBlank()) {
                    Text(if (busy) "Working…" else "Send")
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

            // SelectionContainer makes the whole transcript (questions,
            // tool log, answers) highlightable + copyable — MarkdownText and
            // the Text lines all participate. Consecutive Note (agent-log)
            // lines are batched into a constrained, independently-scrollable
            // box so a long tool log doesn't push the answer below the fold.
            SelectionContainer {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    var i = 0
                    while (i < transcript.size) {
                        when (val line = transcript[i]) {
                            is Line.You -> {
                                Text(
                                    "You: ${line.text}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                i++
                            }
                            is Line.Said -> {
                                MarkdownText(line.text)
                                i++
                            }
                            is Line.Note -> {
                                val logLines = ArrayList<String>()
                                while (i < transcript.size && transcript[i] is Line.Note) {
                                    logLines.add((transcript[i] as Line.Note).text)
                                    i++
                                }
                                AgentLog(logLines)
                            }
                        }
                    }
                }
            }

            // Past conversations, grouped by topic. The active one shows
            // live above, so it's excluded here. Tap to read its turns.
            val pastConversations = conversations.filter { it.id != currentConvId }
            if (pastConversations.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Conversations", style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = {
                        scope.launch {
                            historyDao.clearAll(); convDao.clearAll()
                            // Clearing is explicit user intent → forget on
                            // the ship too, so it doesn't sync back / linger
                            // on other devices. (Auto-trim stays local.)
                            runCatching { repo?.settingsSync?.clearAssistantHistoryOnShip() }
                        }
                        expandedConvId = null
                        newConversation()
                    }) { Text("Clear") }
                }
                val now = remember(pastConversations) { System.currentTimeMillis() }
                pastConversations.forEach { conv ->
                    ConversationRow(
                        conversation = conv,
                        nowMs = now,
                        expanded = expandedConvId == conv.id,
                        turns = if (expandedConvId == conv.id) expandedTurns else emptyList(),
                        onToggle = { expandedConvId = if (expandedConvId == conv.id) null else conv.id },
                    )
                }
            }
        }
    }
}

/**
 * The agent's running log (thinking + tool calls) for one turn, in a
 * height-capped, independently-scrollable box so a long tool transcript
 * doesn't push the answer below the fold. Auto-scrolls to the latest line
 * as the run progresses.
 */
@Composable
private fun AgentLog(lines: List<String>) {
    val scroll = rememberScrollState()
    LaunchedEffect(lines.size) { scroll.scrollTo(scroll.maxValue) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 160.dp)
                .verticalScroll(scroll)
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            lines.forEach { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun ConversationRow(
    conversation: AssistantConversationEntity,
    nowMs: Long,
    expanded: Boolean,
    turns: List<AssistantHistoryEntity>,
    onToggle: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().clickable { onToggle() }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    shortRelativeTime(conversation.updatedAt, nowMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${conversation.turnCount} ${if (conversation.turnCount == 1) "turn" else "turns"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                conversation.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (expanded) {
                turns.forEach { t ->
                    Text(
                        "You: ${t.question}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    MarkdownText(t.answer)
                }
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
