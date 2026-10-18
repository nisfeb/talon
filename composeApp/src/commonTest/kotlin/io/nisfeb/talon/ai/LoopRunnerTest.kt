package io.nisfeb.talon.ai

import io.nisfeb.talon.data.LoopDao
import io.nisfeb.talon.data.LoopEntity
import io.nisfeb.talon.data.LoopRunDao
import io.nisfeb.talon.data.LoopRunEntity
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

    /** An Armillary key, and the assistant on: loops run on it, and only while it is on. */
    private fun config(key: String, assistant: Boolean = true) = paidSettings(key, assistant)

    private fun tool(name: String, write: Boolean) = Tool(
        spec = ToolSpec(name, "", JsonObject(emptyMap())),
        write = write,
        execute = { "" },
    )

    // A run cut short (its budget, or Android stopping its worker) went
    // unsaid: stamped, so never run again, and no result anywhere.
    @Test
    fun `cancelled run still stamps lastRunAt, and says it was cut short`() = runBlocking {
        val loops = FakeLoopDao()
        val runs = FakeLoopRunDao()
        val told = mutableListOf<Triple<Long, String, String>>()
        val runner = LoopRunner(
            loops = loops, runs = runs, tools = emptyList(),
            completer = { _, _, _ -> throw CancellationException("budget") },
            aiConfig = { config("k") },
            notify = { id, title, body -> told += Triple(id, title, body) },
            now = { 5_000 },
        )
        // The cancellation propagates out of runLoop...
        assertFailsWith<CancellationException> { runner.runLoop(loop) }
        // ...but lastRunAt was stamped first, so the loop is no longer due.
        assertEquals(7L to 5_000L, loops.lastMarkRan)
        assertEquals(listOf(LoopRunEntity(loopId = 7, ranAt = 5_000, ok = false, output = LoopRunner.CUT_SHORT)), runs.inserted)
        assertEquals(listOf(Triple(7L, "Loop: Test", LoopRunner.CUT_SHORT.take(LoopRunner.NOTIFY_BODY_CHARS))), told)
    }

    // Desktop closes the database as it quits, maybe mid-run: the record
    // failing must not replace the cancellation, or runDue went on.
    @Test
    fun `a cut short run whose record fails still ends as cancelled`() = runBlocking {
        val runner = LoopRunner(
            loops = FakeLoopDao(), runs = FakeLoopRunDao(closed = true), tools = emptyList(),
            completer = { _, _, _ -> throw CancellationException("quit") },
            aiConfig = { config("k") },
            notify = { _, _, _ -> },
        )
        assertFailsWith<CancellationException> { runner.runLoop(loop) }
        Unit
    }

    @Test
    fun `a run its budget cuts short in the middle is said, and the loop is not due again`() = runBlocking {
        val loops = FakeLoopDao(listOf(loop))
        val runs = FakeLoopRunDao()
        var told = 0
        val runner = LoopRunner(
            loops = loops, runs = runs, tools = emptyList(),
            completer = { _, _, _ -> kotlinx.coroutines.awaitCancellation() },
            aiConfig = { config("k") },
            notify = { _, _, _ -> told++ },
            now = { 10_000_000 },
        )
        assertNull(kotlinx.coroutines.withTimeoutOrNull(200) { runner.runDue() })
        assertEquals(7L to 10_000_000L, loops.lastMarkRan)
        assertEquals(listOf(false to LoopRunner.CUT_SHORT), runs.inserted.map { it.ok to it.output })
        assertEquals(1, told)
    }

    // A refusal said as the assistant says it, not the raw line.
    @Test
    fun `a failed run says why in words`() = runBlocking {
        val runs = FakeLoopRunDao()
        val runner = LoopRunner(
            loops = FakeLoopDao(), runs = runs, tools = emptyList(),
            completer = { _, _, _ -> throw ModelHttpError(503, "llm.example 503: upstream connect error") },
            aiConfig = { config("k") },
            notify = { _, _, _ -> },
            now = { 5_000 },
        )
        runner.runLoop(loop)
        assertEquals(listOf(false to "Error: the model's provider is having trouble. Try again later."), runs.inserted.map { it.ok to it.output })
    }

    // The Assistant row says loops run on it, and they ran with it off,
    // without its web tools (2026-10-12).
    @Test
    fun `with the assistant off no loop runs, by hand or on schedule`() = runBlocking {
        // Due: never run, an hour apart, and it is well past the hour.
        val loops = FakeLoopDao(listOf(loop))
        val runs = FakeLoopRunDao()
        var asked = false
        val runner = LoopRunner(
            loops = loops, runs = runs, tools = emptyList(),
            completer = { _, _, _ -> asked = true; AgentTurn.Final("done") },
            aiConfig = { config("k", assistant = false) },
            notify = { _, _, _ -> },
            now = { 10_000_000 },
        )
        runner.runLoop(loop)
        runner.runDue()
        assertNull(loops.lastMarkRan)
        assertTrue(runs.inserted.isEmpty())
        assertTrue(!asked, "the model was never asked")
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
    fun `destructive tools are filtered even for a writes-authorized loop`() = runBlocking {
        // delete_event is irreversible and a loop auto-confirms its own
        // writes, so it must never reach the model — even for a loop that
        // opted into writes. The completer sees the post-filter specs.
        val seen = mutableListOf<List<String>>()
        val runner = LoopRunner(
            loops = FakeLoopDao(), runs = FakeLoopRunDao(),
            tools = listOf(
                tool("list_events", write = false),
                tool("delete_event", write = true),
                tool("create_event", write = true),
            ),
            completer = { _, _, specs -> seen += specs.map { it.name }; AgentTurn.Final("ok") },
            aiConfig = { config("k") },
            notify = { _, _, _ -> },
            now = { 1 },
        )
        runner.runLoop(loop.copy(writesAuthorized = true))
        assertEquals(listOf("list_events", "create_event"), seen.last(), "an authorized loop still saw delete_event")
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

private class FakeLoopDao(private val due: List<LoopEntity> = emptyList()) : LoopDao {
    var lastMarkRan: Pair<Long, Long>? = null
    override suspend fun upsert(loop: LoopEntity): Long = loop.id
    override fun stream(): Flow<List<LoopEntity>> = flowOf(emptyList())
    override suspend fun enabled(): List<LoopEntity> = due
    override suspend fun get(id: Long): LoopEntity? = null
    override suspend fun getByGid(gid: String): LoopEntity? = null
    override suspend fun setEnabled(id: Long, enabled: Boolean, now: Long) {}
    override suspend fun markRan(id: Long, ranAt: Long) { lastMarkRan = id to ranAt }
    override suspend fun setWritesAuthorized(id: Long, authorized: Boolean) {}
    override suspend fun delete(id: Long) {}
    override suspend fun clearAll() {}
}

private class FakeLoopRunDao(private val closed: Boolean = false) : LoopRunDao {
    val inserted = mutableListOf<LoopRunEntity>()
    override suspend fun insert(run: LoopRunEntity): Long {
        check(!closed) { "the database is closed" }
        inserted += run; return run.id
    }
    override fun streamForLoop(loopId: Long, limit: Int): Flow<List<LoopRunEntity>> = flowOf(emptyList())
    override suspend fun deleteForLoop(loopId: Long) {}
    override suspend fun pruneForLoop(loopId: Long, keep: Int) {}
    override suspend fun clearAll() {}
}
