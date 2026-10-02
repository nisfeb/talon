package io.nisfeb.talon.ui.screens

import io.nisfeb.talon.ai.ModelInfo
import kotlin.test.Test
import kotlin.test.assertEquals

class RankModelsTest {
    private val models = listOf(
        ModelInfo("anthropic/claude-3-haiku", "Anthropic: Claude 3 Haiku"),
        ModelInfo("anthropic/claude-sonnet-4", "Anthropic: Claude Sonnet 4"),
        ModelInfo("openai/gpt-4o", "OpenAI: GPT-4o"),
        ModelInfo("openai/gpt-4o-mini", "OpenAI: GPT-4o mini"),
        ModelInfo("typesafe/jev-1.13", "TypeSafe: Jev 1.13"),
    )
    private fun ids(q: String) = rankModels(q, models).map { it.id }

    // A substring filter missed "cl son" and "gpt4o".
    @Test
    fun `each word fits fuzzily, in the id or the name, best first`() {
        assertEquals(listOf("anthropic/claude-sonnet-4"), ids("cl son"))
        assertEquals(listOf("openai/gpt-4o", "openai/gpt-4o-mini"), ids("gpt4o"))
        assertEquals(listOf("typesafe/jev-1.13"), ids("jev"))
        assertEquals(emptyList(), ids("llama"))
    }

    @Test
    fun `a model the word starts comes before one it is only inside`() {
        // "gpt" starts openai/gpt-4o's tail; "o" mini is only strewn through others.
        assertEquals("openai/gpt-4o", ids("gpt").first())
        assertEquals("openai/gpt-4o-mini", ids("mini").first())
    }

    @Test
    fun `nothing typed lists them all, as listed`() {
        assertEquals(models.map { it.id }, ids("  "))
    }
}
