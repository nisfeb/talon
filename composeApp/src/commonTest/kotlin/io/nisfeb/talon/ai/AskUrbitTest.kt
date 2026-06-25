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

    @Test
    fun `ask with no hits returns the no-results answer without calling the model`() = runBlocking {
        // Empty retrieval short-circuits before the LLM, so the passthrough
        // AiClient (no key, would throw on a real call) is never invoked.
        val embedder = object : SearchEmbedderClient {
            override val progress = MutableStateFlow(IndexProgress())
            override suspend fun start() {}
            override suspend fun semanticSearch(query: String): List<MessageEntity> = emptyList()
            override suspend fun computeHighlights(): List<MessageEntity> = emptyList()
        }
        val ask = AskUrbit(AiClient { AiSettings.Config(AiSettings.Provider.Anthropic, "", null) }, embedder)
        val answer = ask.ask("anything at all", displayName = { it })
        assertTrue(answer.sources.isEmpty())
        assertTrue(answer.text.contains("couldn't find"), "got: ${answer.text}")
    }
}
