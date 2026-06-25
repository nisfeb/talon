# Talon Assistant

Give the open Urbit backend superpowers Tlon can't or won't build: a private,
grounded assistant over your **own** chat history, that can also *act* on your
behalf through Talon's existing pokes.

Three things Talon has that Tlon's cloud client lacks:

- your full **local** message store (Room),
- an **on-device embedding index** (`SearchEmbedderClient` / `EmbeddingDao`),
- a **bring-your-own LLM key** (`AiClient`: Anthropic / OpenAI / OpenRouter / custom).

Wire them into **retrieve → reason → act** and you get something structurally
impossible for a server-rendered, keyless web client.

Shipped in three independently-useful phases, gated and rolled out behind rc
releases so we never break installed apps.

---

## Phase 1 — Answer (grounded RAG, read-only)  ← this branch

Ask in natural language; get an answer grounded in your real messages, every
claim citing the source message.

Flow:

1. `SearchEmbedderClient.semanticSearch(question)` → ranked `List<MessageEntity>`
   (this one call already does embed + cosine search on-device).
2. Number the hits into context (`StoryCache.textFor` + display names).
3. `AiClient.complete(systemPrompt, userPrompt)` with a strict *answer-only-from-
   sources, cite `[n]`, never invent* prompt.
4. Parse `[n]` markers → tappable source chips that deep-link to the message.

New code:
- `ai/AskUrbit.kt` — the chain + pure helpers (`numberedContext`, `parseCitedIndices`).
- `ai/AskUrbitPrompt.kt` — the grounded prompt.
- `AiSettings.Feature.AskUrbit` + `askUrbitEnabled` (defaults **off** — opt-in).
- An assistant screen + a gated entry point.

Reused: retrieval, `AiClient`, settings auto-render + persistence pattern,
deep-link/jump-to-message, the compound `hasKey() && <feature>Enabled` gate.

Gating / rc-safety: invisible unless the user both configures a key **and**
toggles the feature on. Default off so rc builds change nothing for anyone who
doesn't opt in.

Test: pure-logic `commonTest` over `numberedContext` + `parseCitedIndices`
(ordered, deduped, in-range only). No network / model.

---

## Phase 2 — Act (agentic tool-use)

The assistant gains hands: it calls Talon's real `TlonChatRepo` pokes as tools,
loops until done, and **never writes without explicit confirmation**.

- `AiClient.completeWithTools(system, messages, tools)` — add Anthropic
  `tool_use`/`tool_result` and OpenAI `tools`/`tool_calls`. Gate on a
  tool-capable provider/model.
- Tool catalog wraps existing functions:
  - read (auto): `search_history`, `read_thread`, `get_activity`, `read_conversation`
  - write (confirm): `send`, `reply`, `react`/`unreact`, `delete`, `edit`,
    `mark_read`, `pin`, `set_petname`, `add_contact`
  - elevated write (confirm individually): `invite`, `kick`, `ban`, `set_role`,
    `approve_request`, `create_group`/`create_channel`, `leave_group`
- Agent loop capped at ~8 iterations (cost/runaway backstop).
- **Trust boundary (non-negotiable):** reads run free; every write pauses for an
  inline confirmation card. Retrieved message bodies are untrusted *data*, never
  instructions — prompt-injection can't fire an unconfirmed write because writes
  are human-gated by construction.

Test: tool-schema (de)serialization both dialects; read-vs-write confirmation
decision per tool; loop state machine driven by a faked model script (asserts
the cap + that no write fires without an approval flag). Offline.

---

## Phase 3 — Proactive (scheduled autonomy)

Reuse the Daily Digest scheduling rails (`DigestAlarmReceiver`,
`DailyDigestSchedule`) to run the agent on a trigger: "every morning, triage
overnight activity and draft replies for my approval." Drafts queue as
confirmation cards — still human-gated. Android-first (alarm path exists);
desktop needs a scheduler analog → capability-flag it (mirror
`isDailyDigestSupported`).

---

## Cross-cutting

- Lives in `commonMain`; both platforms have on-device embedders (DJL/ONNX
  desktop, Embedder android), so retrieval works on both.
- Default model: a current tool-capable model per provider (`AiClient.kt`).
- Index coverage: `EmbeddingIndexer` must cover history for retrieval to be
  trustworthy — surface index progress in the assistant UI.

## Deferred (named, not forgotten)

- Scale > ~50K msgs → sqlite-vec / HNSW (`SemanticSearch.kt` documents the path).
- Streaming responses → one-shot first.
- Cross-agent scry (beyond chat) → chat history first.
- Remembered per-tool grants → per-action confirm first; add only if annoying.
