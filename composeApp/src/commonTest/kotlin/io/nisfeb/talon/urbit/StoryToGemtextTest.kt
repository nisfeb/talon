package io.nisfeb.talon.urbit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StoryToGemtextTest {
    @Test
    fun `inline text with a link pulls the link onto its own gemtext line`() {
        val json = """[{"inline":["see ",{"link":{"href":"https://x.io","content":["the map"]}}]}]"""
        val out = StoryToGemtext.fromStoryJson(json)
        assertEquals("see the map\n=> https://x.io the map", out)
    }

    @Test
    fun `a header block becomes a gemtext heading`() {
        val json = """[{"block":{"header":{"tag":"h1","content":["Cleanup"]}}}]"""
        assertEquals("# Cleanup", StoryToGemtext.fromStoryJson(json))
    }

    @Test
    fun `a code block is fenced`() {
        val json = """[{"block":{"code":{"code":"a=1","lang":"txt"}}}]"""
        assertEquals("```\na=1\n```", StoryToGemtext.fromStoryJson(json))
    }

    @Test
    fun `bold flattens to plain text`() {
        val json = """[{"inline":[{"bold":["loud"]}]}]"""
        assertEquals("loud", StoryToGemtext.fromStoryJson(json))
    }

    @Test
    fun `a thread stacks a title and per-message bylines`() {
        val out = StoryToGemtext.thread(
            "Trail cleanup",
            listOf(
                StoryToGemtext.Entry("~sampel · Sep 1", """[{"inline":["hi"]}]"""),
                StoryToGemtext.Entry("~zod · Sep 1", """[{"inline":["yo"]}]"""),
            ),
        )
        assertTrue(out.startsWith("# Trail cleanup"), out)
        assertTrue("## ~sampel · Sep 1" in out)
        assertTrue("hi" in out && "yo" in out)
    }

    @Test
    fun `garbage json yields empty, not a crash`() {
        assertEquals("", StoryToGemtext.fromStoryJson("not json"))
    }
}
