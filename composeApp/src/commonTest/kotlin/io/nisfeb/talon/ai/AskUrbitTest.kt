package io.nisfeb.talon.ai

import io.nisfeb.talon.data.MessageEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the pure retrieval-grounding logic behind "Ask your Urbit":
 * how excerpts get numbered for the prompt, and how the `[n]` markers
 * the model emits map back to source messages (ordered, deduped, and
 * range-checked so a hallucinated `[99]` never resolves to a real DM).
 */
class AskUrbitTest {

    @Test
    fun `numberedContext labels 1-based and flattens newlines`() {
        val rows = listOf(
            "~sampel" to "line one\nline two",
            "~bus" to "hello",
        )
        assertEquals(
            listOf("[1] ~sampel: line one line two", "[2] ~bus: hello"),
            AskUrbit.numberedContext(rows),
        )
    }

    @Test
    fun `numberedContext caps long excerpts`() {
        val long = "x".repeat(1000)
        val out = AskUrbit.numberedContext(listOf("~bus" to long)).single()
        // "[1] ~bus: " prefix + 400 capped chars.
        assertEquals("[1] ~bus: " + "x".repeat(400), out)
    }

    @Test
    fun `parseCitedIndices keeps first-mention order and dedupes`() {
        assertEquals(
            listOf(3, 1, 2),
            AskUrbit.parseCitedIndices("per [3] and [1], then [2] — also [1] again", max = 5),
        )
    }

    @Test
    fun `parseCitedIndices drops out-of-range and zero`() {
        assertEquals(
            listOf(2),
            AskUrbit.parseCitedIndices("see [2] but not [9] or [0]", max = 3),
        )
    }

    @Test
    fun `parseCitedIndices on an uncited answer is empty`() {
        assertEquals(
            emptyList(),
            AskUrbit.parseCitedIndices("I couldn't find anything about that.", max = 10),
        )
    }

    private fun m(id: String, whom: String = "~bus", sentMs: Long = 0L) =
        MessageEntity(whom = whom, id = id, author = "~bus", sentMs = sentMs, contentJson = "{}", kind = "chat")

    @Test
    fun `salientTerms drops stopwords and short words, longest first`() {
        // "what/did/say/about/the" are stopwords; "of" is short+stop.
        assertEquals(
            listOf("deadline", "sampel"),
            AskUrbit.salientTerms("What did Sampel say about the deadline?"),
        )
    }

    @Test
    fun `salientTerms dedupes and caps`() {
        val terms = AskUrbit.salientTerms("alpha bravo charlie delta echo foxtrot golf alpha", max = 3)
        assertEquals(3, terms.size)
        assertEquals(terms.distinct(), terms)
    }

    @Test
    fun `mergeHits reserves front slots for lexical then fills with semantic`() {
        val out = AskUrbit.mergeHits(
            lexical = listOf(m("L1"), m("L2"), m("L3")),
            semantic = listOf(m("S1"), m("S2"), m("S3")),
            k = 4,
            lexicalBudget = 2,
        )
        assertEquals(listOf("L1", "L2", "S1", "S2"), out.map { it.id })
    }

    @Test
    fun `mergeHits dedupes by whom and id across the two sources`() {
        val shared = m("X")
        val out = AskUrbit.mergeHits(
            lexical = listOf(shared),
            semantic = listOf(shared, m("S1")),
            k = 5,
            lexicalBudget = 2,
        )
        assertEquals(listOf("X", "S1"), out.map { it.id })
    }

    @Test
    fun `mergeHits tops up with leftover lexical when semantic is thin`() {
        val out = AskUrbit.mergeHits(
            lexical = listOf(m("L1"), m("L2"), m("L3")),
            semantic = listOf(m("S1")),
            k = 4,
            lexicalBudget = 2,
        )
        assertEquals(listOf("L1", "L2", "S1", "L3"), out.map { it.id })
    }

    @Test
    fun `mergeHits guarantees an exact-keyword hit survives a full semantic list`() {
        // The reported bug: the message the user means is lexical-only and
        // every semantic slot is taken by other content. It must still
        // reach the prompt.
        val semantic = (1..20).map { m("S$it") }
        val out = AskUrbit.mergeHits(lexical = listOf(m("TARGET")), semantic = semantic, k = 20)
        assertTrue(out.any { it.id == "TARGET" }, "target dropped: ${out.map { it.id }}")
    }

    @Test
    fun `ask with no hits returns the no-results answer without calling the model`() = runBlocking {
        // Empty retrieval short-circuits before the LLM, so the passthrough
        // AiClient (no key, would throw on a real call) is never invoked.
        val embedder = object : SearchEmbedderClient {
            override val progress = MutableStateFlow(IndexProgress())
            override suspend fun start() {}
            override suspend fun semanticSearch(query: String): List<MessageEntity> = emptyList()
            override suspend fun keywordSearch(terms: List<String>): List<MessageEntity> = emptyList()
            override suspend fun computeHighlights(): List<MessageEntity> = emptyList()
        }
        val ask = AskUrbit(AiClient { AiSettings.Config(AiSettings.Provider.Anthropic, "", null) }, embedder)
        val answer = ask.ask("anything at all", displayName = { it })
        assertTrue(answer.sources.isEmpty())
        assertTrue(answer.text.contains("couldn't find"), "got: ${answer.text}")
    }
}
