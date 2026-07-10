package io.nisfeb.talon.ai

import io.nisfeb.talon.data.LoopDao
import io.nisfeb.talon.data.LoopEntity
import io.nisfeb.talon.data.LoopRunDao
import io.nisfeb.talon.data.LoopRunEntity
import io.nisfeb.talon.data.LoopRunWithName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the headless run contract that the adversarial review flagged:
 *  - a cancelled/timed-out run must still advance lastRunAt (stamp BEFORE
 *    the cancellable agent call) — else the loop stays due and re-fires
 *    every wake;
 *  - no AI key ⇒ no-op (no stamp, no run row, no notification);
 *  - a completed run records its outcome, prunes, and notifies with the
 *    stable loop id.
 */
class LoopRunnerTest {

    private val loop = LoopEntity(
        id = 7, gid = "g", name = "Test", prompt = "do it",
        intervalMinutes = 60, createdAt = 0, updatedAt = 0, lastRunAt = 0,
    )

    private fun config(key: String) =
        AiSettings.Config(AiSettings.Provider.Anthropic, key, model = null)

    private fun tool(name: String, write: Boolean) = Tool(
        spec = ToolSpec(name, "", JsonObject(emptyMap())),
        write = write,
        execute = { "" },
    )

    @Test
    fun `cancelled run still stamps lastRunAt and records no row`() = runBlocking {
        val loops = FakeLoopDao()
        val runs = FakeLoopRunDao()
        val runner = LoopRunner(
            loops = loops, runs = runs, tools = emptyList(),
            completer = { _, _, _ -> throw CancellationException("budget") },
            aiConfig = { config("k") },
            notify = { _, _, _ -> },
            now = { 5_000 },
        )
        // The cancellation propagates out of runLoop...
        assertFailsWith<CancellationException> { runner.runLoop(loop) }
        // ...but lastRunAt was stamped first, so the loop is no longer due.
        assertEquals(7L to 5_000L, loops.lastMarkRan)
        assertTrue(runs.inserted.isEmpty(), "no run row for a cancelled run")
    }

    @Test
    fun `no key is a no-op`() = runBlocking {
        val loops = FakeLoopDao()
        val runs = FakeLoopRunDao()
        var notified = false
        val runner = LoopRunner(
            loops = loops, runs = runs, tools = emptyList(),
            completer = { _, _, _ -> AgentTurn.Final("done") },
            aiConfig = { config("") },
            notify = { _, _, _ -> notified = true },
            now = { 5_000 },
        )
        runner.runLoop(loop)
        assertNull(loops.lastMarkRan)
        assertTrue(runs.inserted.isEmpty())
        assertTrue(!notified)
    }

    @Test
    fun `write tools reach a loop only when it is authorized`() = runBlocking {
        // Security gate (Stage 2): a read-only loop must never be handed a
        // write tool, and an opted-in loop must be. The completer sees the
        // post-filter tool specs, so it's the observation point.
        val seen = mutableListOf<List<String>>()
        fun runnerFor() = LoopRunner(
            loops = FakeLoopDao(), runs = FakeLoopRunDao(),
            tools = listOf(tool("readChats", write = false), tool("sendMessage", write = true)),
            completer = { _, _, specs -> seen += specs.map { it.name }; AgentTurn.Final("ok") },
            aiConfig = { config("k") },
            notify = { _, _, _ -> },
            now = { 1 },
        )
        runnerFor().runLoop(loop.copy(writesAuthorized = false))
        assertEquals(listOf("readChats"), seen.last(), "read-only loop saw a write tool")

        runnerFor().runLoop(loop.copy(writesAuthorized = true))
        assertEquals(listOf("readChats", "sendMessage"), seen.last())
    }

    @Test
    fun `completed run records outcome and notifies with the loop id`() = runBlocking {
        val loops = FakeLoopDao()
        val runs = FakeLoopRunDao()
        var notifiedId: Long? = null
        val runner = LoopRunner(
            loops = loops, runs = runs, tools = emptyList(),
            completer = { _, _, _ -> AgentTurn.Final("the answer") },
            aiConfig = { config("k") },
            notify = { id, _, _ -> notifiedId = id },
            now = { 5_000 },
        )
        runner.runLoop(loop)
        assertEquals(7L to 5_000L, loops.lastMarkRan)
        assertEquals(1, runs.inserted.size)
        assertTrue(runs.inserted[0].ok)
        assertEquals("the answer", runs.inserted[0].output)
        assertEquals(7L, notifiedId)
    }
}

private class FakeLoopDao : LoopDao {
    var lastMarkRan: Pair<Long, Long>? = null
    override suspend fun upsert(loop: LoopEntity): Long = loop.id
    override fun stream(): Flow<List<LoopEntity>> = flowOf(emptyList())
    override suspend fun enabled(): List<LoopEntity> = emptyList()
    override suspend fun get(id: Long): LoopEntity? = null
    override suspend fun getByGid(gid: String): LoopEntity? = null
    override suspend fun setEnabled(id: Long, enabled: Boolean, now: Long) {}
    override suspend fun markRan(id: Long, ranAt: Long) { lastMarkRan = id to ranAt }
    override suspend fun setWritesAuthorized(id: Long, authorized: Boolean) {}
    override suspend fun delete(id: Long) {}
    override suspend fun clearAll() {}
}

private class FakeLoopRunDao : LoopRunDao {
    val inserted = mutableListOf<LoopRunEntity>()
    override suspend fun insert(run: LoopRunEntity): Long { inserted += run; return run.id }
    override fun streamForLoop(loopId: Long, limit: Int): Flow<List<LoopRunEntity>> = flowOf(emptyList())
    override fun streamRecent(limit: Int): Flow<List<LoopRunWithName>> = flowOf(emptyList())
    override suspend fun deleteForLoop(loopId: Long) {}
    override suspend fun pruneForLoop(loopId: Long, keep: Int) {}
    override suspend fun clearAll() {}
}
