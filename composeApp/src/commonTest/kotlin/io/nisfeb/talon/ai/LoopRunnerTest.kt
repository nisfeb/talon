package io.nisfeb.talon.ai

import io.nisfeb.talon.data.LoopDao
import io.nisfeb.talon.data.LoopEntity
import io.nisfeb.talon.data.LoopHoldDao
import io.nisfeb.talon.data.LoopHoldEntity
import io.nisfeb.talon.data.LoopRunDao
import io.nisfeb.talon.data.LoopRunEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
            loops = loops, runs = runs, holds = FakeLoopHoldDao(), tools = emptyList(),
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
            loops = FakeLoopDao(), runs = FakeLoopRunDao(closed = true), holds = FakeLoopHoldDao(), tools = emptyList(),
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
            loops = loops, runs = runs, holds = FakeLoopHoldDao(), tools = emptyList(),
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

    // One budget for the whole fire: a slow first loop left the next none.
    @Test
    fun `each loop due in one fire gets its own time`() = runBlocking {
        val slow = loop.copy(id = 8, prompt = "hang")
        val loops = FakeLoopDao(listOf(slow, loop))
        val runs = FakeLoopRunDao()
        val runner = LoopRunner(
            loops = loops, runs = runs, holds = FakeLoopHoldDao(), tools = emptyList(),
            completer = { _, messages, _ ->
                if (messages.any { it is AgentMessage.User && "hang" in it.text }) kotlinx.coroutines.awaitCancellation()
                AgentTurn.Final("the answer")
            },
            aiConfig = { config("k") },
            notify = { _, _, _ -> },
            now = { 10_000_000 },
        )
        runner.runDue(perLoopMs = 200)
        assertEquals(listOf(8L to LoopRunner.CUT_SHORT, 7L to "the answer"), runs.inserted.map { it.loopId to it.output })
    }

    // A refusal said as the assistant says it, not the raw line.
    @Test
    fun `a failed run says why in words`() = runBlocking {
        val runs = FakeLoopRunDao()
        val runner = LoopRunner(
            loops = FakeLoopDao(), runs = runs, holds = FakeLoopHoldDao(), tools = emptyList(),
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
            loops = loops, runs = runs, holds = FakeLoopHoldDao(), tools = emptyList(),
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
            loops = loops, runs = runs, holds = FakeLoopHoldDao(), tools = emptyList(),
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
            holds = FakeLoopHoldDao(), tools = listOf(tool("readChats", write = false), tool("sendMessage", write = true)),
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
            holds = FakeLoopHoldDao(), tools = listOf(
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
            loops = loops, runs = runs, holds = FakeLoopHoldDao(), tools = emptyList(),
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

    // ─── a loop that asks before sharing (B2-6) ───────────────────

    private val shareArgs = buildJsonObject { put("calendar", "Garden"); put("ship", "~nec"); put("edit", true) }
    private val offerArgs = buildJsonObject { put("offer", "~nec/work"); put("accept", true) }
    private val sharer = loop.copy(writesAuthorized = true, askBeforeSharing = true)
    private val wantsGarden = "Test wants to share Garden with ~nec, edit on."

    /** Each call a tool was given, by name, in order. */
    private val done = mutableListOf<Pair<String, JsonObject>>()

    private fun doing(name: String, result: String = "Done.") = Tool(ToolSpec(name, "", JsonObject(emptyMap())), write = true) { args ->
        done += name to args; result
    }

    /** What the model was told of each tool call, in order. */
    private val told = mutableListOf<String>()

    /** A model that makes [calls] in one turn, then answers "done". */
    private fun calling(vararg calls: Pair<String, JsonObject>) = AgentLoop.Completer { _, messages, _ ->
        val results = messages.filterIsInstance<AgentMessage.ToolResults>()
        if (results.isEmpty()) {
            AgentTurn.Calls(null, calls.mapIndexed { i, (name, args) -> ToolCall("c$i", name, args) })
        } else {
            told += results.flatMap { r -> r.results.map { it.content } }
            AgentTurn.Final("done")
        }
    }

    private class Runner(
        val loops: FakeLoopDao,
        val runs: FakeLoopRunDao,
        val holds: FakeLoopHoldDao,
        val notified: MutableList<String>,
        var at: Long,
    )

    private fun runner(
        model: AgentLoop.Completer,
        tools: List<Tool> = listOf(doing("share_calendar", "Offered \"Garden\" to ~nec to read and edit."), doing("answer_calendar_offer"), doing("create_event")),
        due: List<LoopEntity> = listOf(sharer),
        block: suspend Runner.(LoopRunner) -> Unit,
    ) = runBlocking {
        val r = Runner(FakeLoopDao(due), FakeLoopRunDao(), FakeLoopHoldDao(), mutableListOf(), 5_000)
        val runner = LoopRunner(
            loops = r.loops, runs = r.runs, holds = r.holds, tools = tools,
            completer = model, aiConfig = { config("k") },
            notify = { _, _, body -> r.notified += body },
            now = { r.at },
        )
        r.block(runner)
    }

    @Test
    fun `with the switch off a write loop shares at once as before`() = runner(calling("share_calendar" to shareArgs)) { runner ->
        runner.runLoop(sharer.copy(askBeforeSharing = false))
        assertEquals(listOf("share_calendar" to shareArgs), done)
        assertTrue(holds.rows.isEmpty())
        assertEquals(listOf("Offered \"Garden\" to ~nec to read and edit."), told)
        assertEquals(listOf("done"), runs.inserted.map { it.output })
        assertEquals(listOf("done"), notified)
    }

    @Test
    fun `with the switch on a share is held and the run says what waits`() = runner(calling("share_calendar" to shareArgs)) { runner ->
        runner.runLoop(sharer)
        assertTrue(done.isEmpty(), "nothing shared: $done")
        val hold = holds.rows.single()
        assertEquals(LoopHoldEntity(id = 1, loopId = 7, tool = "share_calendar", args = shareArgs.toString(), what = wantsGarden, heldAt = 5_000), hold)
        assertEquals(listOf(LoopRunner.HELD), told, "the model is told it waits")
        assertEquals(listOf("done\n\nWaiting for you in Loops: $wantsGarden"), runs.inserted.map { it.output })
        assertEquals(listOf("$wantsGarden Allow it or not in Loops."), notified)
    }

    @Test
    fun `an offer answer is held too and other writes still run`() = runner(
        calling("answer_calendar_offer" to offerArgs, "create_event" to buildJsonObject { put("name", "Tea") }),
    ) { runner ->
        runner.runLoop(sharer)
        assertEquals(listOf("create_event"), done.map { it.first })
        assertEquals(listOf("Test wants to accept the calendar offer ~nec/work."), holds.rows.map { it.what })
        assertEquals(listOf(LoopRunner.HELD, "Done."), told)
    }

    @Test
    fun `what a held call wanted is said plainly`() {
        assertEquals("Lo wants to share Work with ~bus, edit off.", LoopRunner.wanted("Lo", "share_calendar", buildJsonObject { put("calendar", "Work"); put("ship", "~bus") }))
        assertEquals("Lo wants to decline the calendar offer ~nec/work.", LoopRunner.wanted("Lo", "answer_calendar_offer", buildJsonObject { put("offer", "~nec/work"); put("accept", false) }))
    }

    @Test
    fun `Allow does exactly the held call once and says what came of it`() = runner(calling("share_calendar" to shareArgs)) { runner ->
        runner.runLoop(sharer)
        at = 9_000
        runner.allow(holds.rows.single().id)
        assertEquals(listOf("share_calendar" to shareArgs), done, "the held call and its own arguments")
        assertTrue(holds.rows.isEmpty())
        val said = "You allowed it: $wantsGarden\n\nOffered \"Garden\" to ~nec to read and edit."
        assertEquals(LoopRunEntity(loopId = 7, ranAt = 9_000, ok = true, output = said), runs.inserted.last())
        assertEquals(said, notified.last())
        // A second tap finds nothing to do.
        runner.allow(1)
        assertEquals(1, done.size)
        assertEquals(2, runs.inserted.size)
    }

    @Test
    fun `an allowed call that fails is said as failed`() = runner(
        calling("share_calendar" to shareArgs, "answer_calendar_offer" to offerArgs),
        tools = listOf(Tool(ToolSpec("share_calendar", "", JsonObject(emptyMap())), write = true) { error("boom") }, doing("answer_calendar_offer")),
    ) { runner ->
        runner.runLoop(sharer)
        runner.allow(1)
        assertEquals(false to "You allowed it: $wantsGarden\n\nError: boom", runs.inserted.last().let { it.ok to it.output })
    }

    @Test
    fun `an allowed call with no tool here says so`() = runBlocking {
        val holds = FakeLoopHoldDao()
        val runs = FakeLoopRunDao()
        holds.insert(LoopHoldEntity(loopId = 7, tool = "share_calendar", args = shareArgs.toString(), what = wantsGarden, heldAt = 5_000))
        LoopRunner(
            loops = FakeLoopDao(listOf(sharer)), runs = runs, holds = holds, tools = emptyList(),
            completer = { _, _, _ -> AgentTurn.Final("") }, aiConfig = { config("k") }, notify = { _, _, _ -> }, now = { 6_000 },
        ).allow(1)
        assertEquals(false to "You allowed it: $wantsGarden\n\nError: share_calendar is not available on this device.", runs.inserted.single().let { it.ok to it.output })
    }

    @Test
    fun `Don't and expiry do nothing`() = runner(calling("share_calendar" to shareArgs)) { runner ->
        runner.runLoop(sharer)
        // Don't: the screen drops the row.
        holds.delete(1)
        runner.allow(1)
        assertTrue(done.isEmpty())
        assertEquals(1, runs.inserted.size, "nothing said of it")

        // Left a day: expired, dropped, and not done.
        holds.insert(LoopHoldEntity(loopId = 7, tool = "share_calendar", args = shareArgs.toString(), what = wantsGarden, heldAt = 5_000))
        at = 5_000 + LoopRunner.HOLD_MS
        runner.allow(2)
        assertTrue(done.isEmpty())
        assertTrue(holds.rows.isEmpty())
        assertEquals(1, runs.inserted.size)
    }

    @Test
    fun `a held call allowed just within its day is done`() = runner(calling("share_calendar" to shareArgs)) { runner ->
        runner.runLoop(sharer)
        at = 5_000 + LoopRunner.HOLD_MS - 1
        runner.allow(1)
        assertEquals(1, done.size)
    }

    @Test
    fun `a held call whose loop is gone does nothing`() = runner(calling("share_calendar" to shareArgs)) { runner ->
        runner.runLoop(sharer)
        LoopRunner(
            loops = FakeLoopDao(), runs = runs, holds = holds, tools = listOf(doing("share_calendar")),
            completer = { _, _, _ -> AgentTurn.Final("") }, aiConfig = { config("k") }, notify = { _, _, _ -> }, now = { 6_000 },
        ).allow(1)
        assertTrue(done.isEmpty())
        assertTrue(holds.rows.isEmpty())
        assertEquals(1, runs.inserted.size)
    }

    @Test
    fun `a run drops the holds that have expired`() = runner({ _, _, _ -> AgentTurn.Final("done") }) { runner ->
        holds.insert(LoopHoldEntity(loopId = 7, tool = "share_calendar", args = "{}", what = "old", heldAt = 1_000))
        holds.insert(LoopHoldEntity(loopId = 7, tool = "share_calendar", args = "{}", what = "new", heldAt = 1_001))
        at = 1_000 + LoopRunner.HOLD_MS
        runner.runLoop(sharer)
        assertEquals(listOf("new"), holds.rows.map { it.what })
    }

    // Cut short after it held a share: what waits is still said.
    @Test
    fun `a run cut short after a hold still says what waits`() = runner(
        { _, messages, _ ->
            if (messages.none { it is AgentMessage.ToolResults }) AgentTurn.Calls(null, listOf(ToolCall("c1", "share_calendar", shareArgs)))
            else kotlinx.coroutines.awaitCancellation()
        },
    ) { runner ->
        assertNull(kotlinx.coroutines.withTimeoutOrNull(200) { runner.runLoop(sharer) })
        assertEquals(listOf("${LoopRunner.CUT_SHORT}\n\nWaiting for you in Loops: $wantsGarden"), runs.inserted.map { it.output })
        assertEquals(1, holds.rows.size, "kept for the owner")
    }
}

private class FakeLoopDao(private val due: List<LoopEntity> = emptyList()) : LoopDao {
    var lastMarkRan: Pair<Long, Long>? = null
    override suspend fun upsert(loop: LoopEntity): Long = loop.id
    override fun stream(): Flow<List<LoopEntity>> = flowOf(emptyList())
    override suspend fun enabled(): List<LoopEntity> = due
    override suspend fun get(id: Long): LoopEntity? = due.firstOrNull { it.id == id }
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

/** Held calls in memory, for the runner tests here and in LoopWriteCoordinatorTest. */
internal class FakeLoopHoldDao : LoopHoldDao {
    val rows = mutableListOf<LoopHoldEntity>()
    private var next = 0L
    override suspend fun insert(hold: LoopHoldEntity): Long = (++next).also { rows += hold.copy(id = it) }
    override fun stream(since: Long): Flow<List<LoopHoldEntity>> = flowOf(rows.filter { it.heldAt > since })
    override suspend fun get(id: Long): LoopHoldEntity? = rows.firstOrNull { it.id == id }
    override suspend fun delete(id: Long): Int = if (rows.removeAll { it.id == id }) 1 else 0
    override suspend fun deleteBefore(before: Long) { rows.removeAll { it.heldAt <= before } }
}
