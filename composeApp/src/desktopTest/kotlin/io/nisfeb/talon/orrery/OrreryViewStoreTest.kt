package io.nisfeb.talon.orrery

import io.nisfeb.talon.data.OrreryCacheDao
import io.nisfeb.talon.data.OrreryCacheEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Orrery section's data: the last answer at once, the ship asked
 * behind it, both reads together, one ask however many opens. It used to
 * block on a 220 KB read of a busy ship every time it opened.
 */
class OrreryViewStoreTest {
    private class Kept : OrreryCacheDao {
        val rows = java.util.concurrent.ConcurrentHashMap<String, OrreryCacheEntity>()
        override suspend fun get(kind: String) = rows[kind]
        override suspend fun put(row: OrreryCacheEntity) { rows[row.kind] = row }
    }

    private fun obj(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    private val old = obj("""{"bodies":[{"id":"activity/a","kind":"activity","name":"Old"}]}""")
    private val fresh = obj("""{"bodies":[{"id":"activity/a","kind":"activity","name":"Fresh"}]}""")
    private val plan = obj("""{"next":{"key":"activity/a@1","leave_by":"2026-10-05T19:32:37Z"}}""")
    private var clock = 1_000_000L

    private suspend fun until(what: () -> Boolean) = withTimeout(5_000) { while (!what()) delay(10) }

    @Test
    fun `it opens on the kept answer before the ship answers, then shows the ship's and keeps it`() = runBlocking {
        val kept = Kept().apply {
            rows["state"] = OrreryCacheEntity("state", old.toString(), 5)
            rows["plan"] = OrreryCacheEntity("plan", plan.toString(), 5)
        }
        val answer = CompletableDeferred<JsonObject>()
        val scope = CoroutineScope(SupervisorJob())
        val s = OrreryViewStore(kept, scope, readState = { answer.await() }, readPlan = { plan }, now = { clock })
        s.open()
        until { s.view.value != null }
        assertEquals(old, s.view.value!!.state, "the kept answer, while the ship has not answered")
        assertEquals(plan, s.view.value!!.plan)
        assertTrue(s.refreshing.value)
        answer.complete(fresh)
        until { s.view.value?.state == fresh }
        until { kept.rows["state"]?.json == fresh.toString() }
        assertEquals(clock, kept.rows["state"]!!.atMs)
        until { !s.refreshing.value }
        scope.cancel()
    }

    // A Refresh during a read did nothing: what showed was the read begun
    // before whatever the owner knew had changed.
    @Test
    fun `a Refresh during a read reads again once it ends, however many taps`() = runBlocking {
        val asked = AtomicInteger()
        val release = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob())
        val s = OrreryViewStore(
            Kept(), scope,
            readState = { if (asked.incrementAndGet() == 1) release.await(); fresh },
            readPlan = { plan },
            now = { clock },
        )
        s.open()
        until { asked.get() == 1 }
        repeat(3) { s.open(force = true) }
        assertEquals(1, asked.get(), "not while the first is out")
        release.complete(Unit)
        until { asked.get() == 2 }
        until { !s.refreshing.value }
        delay(200)
        assertEquals(2, asked.get(), "one more read, not one per tap")
        scope.cancel()
    }

    @Test
    fun `the two reads go out together, and many opens make one ask`() = runBlocking {
        val asked = AtomicInteger()
        val planAsked = CompletableDeferred<Unit>()
        val stateAsked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob())
        val s = OrreryViewStore(
            Kept(), scope,
            readState = { asked.incrementAndGet(); stateAsked.complete(Unit); release.await(); fresh },
            readPlan = { planAsked.complete(Unit); release.await(); plan },
            now = { clock },
        )
        repeat(5) { s.open() }
        withTimeout(5_000) { stateAsked.await(); planAsked.await() }
        repeat(5) { s.open() }
        release.complete(Unit)
        until { s.view.value?.state == fresh }
        assertEquals(1, asked.get())
        scope.cancel()
    }

    @Test
    fun `an answer under a minute old is not asked for again on an open, only on Refresh`() = runBlocking {
        val asked = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob())
        val s = OrreryViewStore(Kept(), scope, readState = { asked.incrementAndGet(); fresh }, readPlan = { plan }, now = { clock })
        s.open()
        until { asked.get() == 1 && !s.refreshing.value }
        clock += 30_000
        s.open()
        delay(200)
        assertEquals(1, asked.get(), "half a minute later, an open asks nothing")
        s.open(force = true)
        until { asked.get() == 2 && !s.refreshing.value }
        clock += OrreryViewStore.FRESH_MS
        s.open()
        until { asked.get() == 3 }
        scope.cancel()
    }

    @Test
    fun `a ship that does not answer leaves the kept answer up and says why`() = runBlocking {
        val kept = Kept().apply { rows["state"] = OrreryCacheEntity("state", old.toString(), 5) }
        val scope = CoroutineScope(SupervisorJob())
        val s = OrreryViewStore(kept, scope, readState = { error("502 Bad Gateway") }, readPlan = { null }, now = { clock })
        s.open()
        until { s.problem.value != null }
        assertEquals(old, s.view.value?.state)
        assertEquals("502 Bad Gateway", s.problem.value)
        assertEquals(old.toString(), kept.rows["state"]!!.json, "nothing written over what was kept")
        scope.cancel()
    }

    @Test
    fun `a plan that does not answer leaves the last plan up`() = runBlocking {
        var planUp = true
        val scope = CoroutineScope(SupervisorJob())
        val s = OrreryViewStore(Kept(), scope, readState = { fresh }, readPlan = { if (planUp) plan else null }, now = { clock })
        s.open()
        until { s.view.value?.plan == plan && !s.refreshing.value }
        planUp = false
        s.open(force = true)
        delay(200)
        until { !s.refreshing.value }
        assertEquals(plan, s.view.value?.plan)
        assertNull(s.problem.value)
        scope.cancel()
    }

    // ---- the change beacon (orrery's /beacon/rev, read by scry) ----

    /** A store whose state reads are counted, over a beacon the test moves. */
    private class Beaconed(val kept: Kept = Kept(), var beacon: Long? = 7) {
        val stateReads = AtomicInteger()
        val planReads = AtomicInteger()
    }

    private fun CoroutineScope.store(b: Beaconed, state: JsonObject, now: () -> Long) = OrreryViewStore(
        b.kept, this,
        readState = { b.stateReads.incrementAndGet(); state },
        readPlan = { b.planReads.incrementAndGet(); plan },
        readBeacon = { b.beacon },
        now = now,
    )

    @Test
    fun `a beacon that has not moved skips the state read and reads only the plan`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val b = Beaconed()
        val s = scope.store(b, fresh) { clock }
        s.open()
        until { b.stateReads.get() == 1 && !s.refreshing.value }
        clock += OrreryViewStore.FRESH_MS
        s.open()
        until { b.planReads.get() == 2 && !s.refreshing.value }
        assertEquals(1, b.stateReads.get(), "orrery wrote nothing: no 3.5 s state read")
        assertEquals(clock, s.view.value!!.atMs, "the kept state is current as of now")
        b.beacon = 8
        clock += OrreryViewStore.FRESH_MS
        s.open()
        until { b.stateReads.get() == 2 && !s.refreshing.value }
        assertEquals(8L, s.view.value!!.beacon)
        scope.cancel()
    }

    @Test
    fun `no beacon, a Refresh, or a state read whole too long ago reads the state`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val none = Beaconed(beacon = null)
        val s1 = scope.store(none, fresh) { clock }
        s1.open(); until { none.stateReads.get() == 1 && !s1.refreshing.value }
        clock += OrreryViewStore.FRESH_MS
        s1.open(); until { none.stateReads.get() == 2 && !s1.refreshing.value }

        val still = Beaconed()
        val s2 = scope.store(still, fresh) { clock }
        s2.open(); until { still.stateReads.get() == 1 && !s2.refreshing.value }
        s2.open(force = true); until { still.stateReads.get() == 2 && !s2.refreshing.value }
        clock += OrreryViewStore.FULL_EVERY_MS
        s2.open(); until { still.stateReads.get() == 3 && !s2.refreshing.value }
        scope.cancel()
    }

    @Test
    fun `the beacon is kept with the state, so a restart still skips an unchanged read`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val first = Beaconed()
        val s1 = scope.store(first, fresh) { clock }
        s1.open(); until { first.stateReads.get() == 1 && !s1.refreshing.value }
        until { first.kept.rows["beacon"] != null }
        // A cold start over the same database.
        val again = Beaconed(kept = first.kept)
        clock += OrreryViewStore.FRESH_MS
        val s2 = scope.store(again, fresh) { clock }
        s2.open(); until { again.planReads.get() == 1 && !s2.refreshing.value }
        assertEquals(0, again.stateReads.get())
        assertEquals(fresh, s2.view.value!!.state)
        scope.cancel()
    }
}
