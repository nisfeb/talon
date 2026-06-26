package io.nisfeb.talon.ai

import io.nisfeb.talon.data.LoopDao
import io.nisfeb.talon.data.LoopEntity
import io.nisfeb.talon.data.LoopRunDao
import io.nisfeb.talon.data.LoopRunEntity
import io.nisfeb.talon.data.LoopRunWithName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the cross-device write-loop dedup contract:
 *  - [decideClaim]'s four branches (the pure lease logic the real
 *    SettingsSyncImpl.claim routes through);
 *  - through [LoopRunner.runDue]: with two devices up and the same write
 *    loop due, exactly ONE runs; when the holder goes offline, the other
 *    takes over; and a read-only loop bypasses the gate and runs on both.
 */
class LoopWriteCoordinatorTest {

    private val writeLoop = LoopEntity(
        id = 7, gid = "g", name = "auto", prompt = "post it",
        intervalMinutes = 60, writesAuthorized = true,
        createdAt = 0, updatedAt = 0, lastRunAt = 0,
    )

    // interval 60 → staleMs = (60*2).coerceIn(30,720) * 60_000 = 7_200_000.
    private val t0 = 10_000_000L       // > one interval, so the loop is due
    private val pastStale = t0 + 8_000_000L

    @Test
    fun `decideClaim covers unclaimed mine stale and fresh`() {
        val stale = 7_200_000L
        assertEquals(ClaimDecision.CONTEST, decideClaim(null, "me", t0, stale))
        assertEquals(ClaimDecision.RUN, decideClaim("me" to t0, "me", t0, stale))
        assertEquals(ClaimDecision.CONTEST, decideClaim("other" to t0, "me", t0 + stale + 1, stale))
        assertEquals(ClaimDecision.SKIP, decideClaim("other" to t0, "me", t0 + 1, stale))
    }

    @Test
    fun `two devices up, only one runs the write loop`() = runBlocking {
        val lease = FakeLease(now = t0)
        val a = device("A", lease)
        val b = device("B", lease)

        a.runner.runDue()
        b.runner.runDue()

        assertEquals(1, a.runDao.inserted.size + b.runDao.inserted.size, "exactly one device ran")
    }

    @Test
    fun `holder offline, the other device takes over`() = runBlocking {
        val lease = FakeLease(now = t0)
        val a = device("A", lease)
        val b = device("B", lease)

        // Round 1: both up — A wins, B skips.
        a.runner.runDue(); b.runner.runDue()
        assertEquals(1, a.runDao.inserted.size)
        assertEquals(0, b.runDao.inserted.size)

        // Round 2: A is offline (doesn't run), time advances past stale.
        lease.now = pastStale
        b.runner.runDue()
        assertEquals(1, b.runDao.inserted.size, "B takes over the stale lease")
    }

    @Test
    fun `read-only loop runs on every device`() = runBlocking {
        // A coordinator that would block everyone — proving a read loop never
        // consults it.
        val deny = object : LoopWriteCoordinator {
            override suspend fun claim(loop: LoopEntity) = false
        }
        val readLoop = writeLoop.copy(writesAuthorized = false)
        val a = device("A", FakeLease(t0), deny, readLoop)
        val b = device("B", FakeLease(t0), deny, readLoop)

        a.runner.runDue(); b.runner.runDue()

        assertEquals(1, a.runDao.inserted.size)
        assertEquals(1, b.runDao.inserted.size)
    }

    // ── harness ──

    private class Device(val runner: LoopRunner, val runDao: LeaseFakeLoopRunDao)

    private fun device(
        id: String,
        lease: FakeLease,
        coordinator: LoopWriteCoordinator = FakeLeaseCoordinator(id, lease),
        loop: LoopEntity = writeLoop,
    ): Device {
        val runDao = LeaseFakeLoopRunDao()
        val runner = LoopRunner(
            loops = LeaseFakeLoopDao(loop),
            runs = runDao,
            tools = emptyList(),
            completer = { _, _, _ -> AgentTurn.Final("done") },
            aiConfig = { AiSettings.Config(AiSettings.Provider.Anthropic, "k", model = null) },
            notify = { _, _, _ -> },
            coordinator = coordinator,
            now = { lease.now },
        )
        return Device(runner, runDao)
    }
}

/** Shared ship-side lease store + clock for the simulated devices. */
private class FakeLease(var now: Long) {
    val claims = mutableMapOf<String, Pair<String, Long>>() // gid -> (holder, claimedAt)
}

/** Mirrors SettingsSyncImpl.claim against the in-memory [lease]. Sequential
 *  test calls ⇒ a CONTEST writer always wins (no concurrent writer between). */
private class FakeLeaseCoordinator(
    private val me: String,
    private val lease: FakeLease,
) : LoopWriteCoordinator {
    override suspend fun claim(loop: LoopEntity): Boolean {
        val staleMs = (loop.intervalMinutes.toLong() * 2).coerceIn(30, 720) * 60_000L
        return when (decideClaim(lease.claims[loop.gid], me, lease.now, staleMs)) {
            ClaimDecision.RUN -> { lease.claims[loop.gid] = me to lease.now; true }
            ClaimDecision.SKIP -> false
            ClaimDecision.CONTEST -> {
                lease.claims[loop.gid] = me to lease.now
                lease.claims[loop.gid]?.first == me
            }
        }
    }
}

private class LeaseFakeLoopDao(private var loop: LoopEntity) : LoopDao {
    override suspend fun enabled(): List<LoopEntity> = listOf(loop)
    override suspend fun markRan(id: Long, ranAt: Long) { loop = loop.copy(lastRunAt = ranAt) }
    override suspend fun get(id: Long): LoopEntity? = loop.takeIf { it.id == id }
    override suspend fun upsert(loop: LoopEntity): Long = loop.id
    override fun stream(): Flow<List<LoopEntity>> = flowOf(emptyList())
    override suspend fun getByGid(gid: String): LoopEntity? = loop.takeIf { it.gid == gid }
    override suspend fun setEnabled(id: Long, enabled: Boolean, now: Long) {}
    override suspend fun delete(id: Long) {}
    override suspend fun clearAll() {}
}

private class LeaseFakeLoopRunDao : LoopRunDao {
    val inserted = mutableListOf<LoopRunEntity>()
    override suspend fun insert(run: LoopRunEntity): Long { inserted += run; return run.id }
    override fun streamForLoop(loopId: Long, limit: Int): Flow<List<LoopRunEntity>> = flowOf(emptyList())
    override fun streamRecent(limit: Int): Flow<List<LoopRunWithName>> = flowOf(emptyList())
    override suspend fun deleteForLoop(loopId: Long) {}
    override suspend fun pruneForLoop(loopId: Long, keep: Int) {}
    override suspend fun clearAll() {}
}
