package io.nisfeb.talon.ai

import io.nisfeb.talon.orrery.Instructed
import io.nisfeb.talon.orrery.OrreryAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The Assistant's orrery_instruct, with what orrery leaves waiting put to the owner at once. */
class PuttingToOwnerTest {
    private fun action(id: String, status: String = "proposed") =
        OrreryAction(id, "message", "Tell $id", JsonObject(emptyMap()), emptyList(), null, status, "owner")

    private val asked = mutableListOf<Pair<String, List<String>>>()
    private val answered = mutableListOf<String>()

    private fun tap(word: Boolean?, said: Instructed, fail: Throwable? = null): OrreryTap {
        val t = OrreryToolsTest.Tap()
        t.instructed = said
        return t.puttingToOwner(
            decide = { reply, waiting -> asked += reply to waiting.map { it.id }; word },
            answer = { id, ok -> fail?.let { throw it }; answered += "$id $ok" },
        )
    }

    private suspend fun instruct(t: OrreryTap, apply: Boolean = false) =
        orreryTools(t).first { it.spec.name == "orrery_instruct" }.execute(buildJsonObject { put("text", "tell them"); put("apply", apply) })

    @Test
    fun `approved, each waiting one is answered and the model reads it`() = runTest {
        val said = instruct(tap(true, Instructed(reply = "On it.", actions = listOf(action("a1"), action("a2"), action("t1", "approved")))))
        assertEquals(listOf("On it." to listOf("a1", "a2")), asked, "only what waits, with orrery's reply")
        assertEquals(listOf("a1 true", "a2 true"), answered)
        assertEquals("Orrery says: On it.\nFiled:\n- message: Tell a1 (approved)\n- message: Tell a2 (approved)\n- message: Tell t1 (approved)", said)
    }

    @Test
    fun `dropped, each is dismissed`() = runTest {
        val said = instruct(tap(false, Instructed(actions = listOf(action("a1")))))
        assertEquals(listOf("Orrery took it." to listOf("a1")), asked, "a blank reply is said as the voice card says it")
        assertEquals(listOf("a1 false"), answered)
        assertTrue("Tell a1 (dismissed)" in said, said)
    }

    @Test
    fun `no word leaves it waiting as it was`() = runTest {
        val said = instruct(tap(null, Instructed(reply = "On it.", actions = listOf(action("a1")))))
        assertTrue(answered.isEmpty())
        assertEquals("Orrery says: On it.\nFiled:\n- message: Tell a1 (proposed)", said)
    }

    @Test
    fun `applied or with nothing waiting the owner is not asked`() = runTest {
        instruct(tap(true, Instructed(actions = listOf(action("a1")))), apply = true)
        instruct(tap(true, Instructed(reply = "Noted.", actions = listOf(action("t1", "approved")))))
        assertTrue(asked.isEmpty() && answered.isEmpty())
    }

    @Test
    fun `an answer orrery does not take is said and not hidden`() = runTest {
        val said = instruct(tap(true, Instructed(reply = "On it.", actions = listOf(action("a1"))), fail = IllegalStateException("the ship said 500")))
        assertTrue("orrery did not take the answer: the ship said 500. It waits under Actions." in said, said)
        assertTrue("Tell a1 (proposed)" in said, "not claimed as approved")
    }

    @Test
    fun `a stop while it asks is a stop and not an answer`() = runTest {
        val t = OrreryToolsTest.Tap().apply { instructed = Instructed(actions = listOf(action("a1"))) }
            .puttingToOwner(decide = { _, _ -> throw CancellationException("stopped") }, answer = { _, _ -> answered += "x" })
        assertFailsWith<CancellationException> { instruct(t) }
        assertTrue(answered.isEmpty())
    }
}
