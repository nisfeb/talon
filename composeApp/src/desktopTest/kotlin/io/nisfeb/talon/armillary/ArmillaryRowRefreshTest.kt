package io.nisfeb.talon.armillary

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.nisfeb.talon.ai.ARMILLARY_PROVIDER
import io.nisfeb.talon.ai.AiProfile
import io.nisfeb.talon.ai.AiProvider
import io.nisfeb.talon.ai.AiSettings
import io.nisfeb.talon.urbit.FakeAiSettings
import io.nisfeb.talon.ai.ProviderKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The vendor's picks reach a device with nothing tapped. They reach the
 * ship within five minutes, and the device's row took them only on a
 * refresh: once a process, or the Refresh button. A phone's process
 * lives for days and kept the old models, and a balance paid on another
 * device (sneagan's users, 2026-10-17: "the armillary settings aren't
 * propagating to their devices").
 */
class ArmillaryRowRefreshTest {
    @Volatile private var pick = "small/one"
    @Volatile private var balance = 0L
    @Volatile private var failing = false
    /** The ship holds no key for this device: a vendor changed, or the key revoked. */
    @Volatile private var noKey = false
    /** The vendor the ship buys from; blank for one that buys from none, which a device does not adopt. */
    @Volatile private var vendor = "~nisfeb"
    /** Checks of the ship that answer nginx's page before armillary answers. */
    private val downFor = AtomicInteger(0)
    private val inferenceReads = AtomicInteger()
    private val catalogReads = AtomicInteger()
    private val accountReads = AtomicInteger()
    private val json = headersOf("Content-Type", "application/json")

    private val http = HttpClient(MockEngine { req ->
        val path = req.url.encodedPath
        val ok = { body: String -> respond(body, HttpStatusCode.OK, json) }
        when {
            path.endsWith("/apps/armillary/api/account") && downFor.getAndDecrement() > 0 ->
                respond("<html><body>502 Bad Gateway</body></html>", HttpStatusCode.BadGateway)
            path.endsWith("/apps/armillary/api/inference") -> {
                inferenceReads.incrementAndGet()
                if (failing) respond("", HttpStatusCode.InternalServerError)
                else if (noKey) respond("""{"error":"no key yet"}""", HttpStatusCode.NotFound, json)
                else ok("""{"mode":"lease","base_url":"https://openrouter.ai/api/v1","key":"lease.key","models":["small/one","small/two"],"suggested":{"models":{"default":"$pick"}}}""")
            }
            path.endsWith("/apps/armillary/api/account") -> {
                accountReads.incrementAndGet()
                ok("""{"ship":"~zod","balance":$balance,"plan":"","subscription":{"active":false},"lease":{},"checkouts":{},"vendor":"$vendor","self":"~zod","stale":3}""")
            }
            path.endsWith("/apps/armillary/api/catalog") -> { catalogReads.incrementAndGet(); ok("[]") }
            path.endsWith("/apps/armillary/api/plans") -> ok("[]")
            else -> ok("{}")
        }
    })

    private val row = AiProvider(ARMILLARY_PROVIDER, ProviderKind.Armillary, "Armillary")

    @Volatile private var clock = 1_000_000L

    private fun rig(everyMs: Long, withRow: Boolean = true, block: suspend (ArmillaryRepo, FakeAiSettings) -> Unit) {
        val scope = CoroutineScope(SupervisorJob())
        val ai = FakeAiSettings().apply {
            applyRemote(AiSettings.Config(provider = AiSettings.Provider.Anthropic, apiKey = "", model = null, savedProfile = AiProfile(providers = listOfNotNull(row.takeIf { withRow }))))
        }
        val repo = ArmillaryRepo(http, scope, ai, picksEveryMs = everyMs, now = { clock }, probeRetryFirstMs = 50)
        try {
            repo.attach("https://ship.test", "~zod")
            runBlocking {
                withTimeout(10_000) { while (if (withRow) pickOn(ai) != "small/one" else repo.inference.value == null) delay(10) }
                block(repo, ai)
            }
        } finally {
            repo.detach()
            runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        }
    }

    private fun pickOn(ai: FakeAiSettings) = ai.state.value.savedProfile?.provider(ARMILLARY_PROVIDER)?.suggested?.get("default")

    @Test
    fun `the vendor's new pick reaches the row within a tick, with nothing tapped, and costs the vendor nothing`() = rig(everyMs = 200) { _, ai ->
        val catalogs = catalogReads.get()
        val accounts = accountReads.get()
        pick = "small/two"
        withTimeout(5_000) { while (pickOn(ai) != "small/two") delay(10) }
        assertEquals(catalogs, catalogReads.get(), "the tick reads the ship's config, not the vendor's catalog")
        assertEquals(accounts, accountReads.get())
    }

    @Test
    fun `a read that fails keeps the row as it was`() = rig(everyMs = 100) { _, ai ->
        failing = true
        val before = inferenceReads.get()
        withTimeout(5_000) { while (inferenceReads.get() < before + 2) delay(10) }
        assertEquals("small/one", pickOn(ai))
        assertEquals("lease.key", ai.state.value.savedProfile!!.provider(ARMILLARY_PROVIDER)!!.apiKey)
    }

    @Test
    fun `coming back to the front publishes a change made while away, and the account, at most once a minute`() = rig(everyMs = 3_600_000) { repo, ai ->
        pick = "small/two"
        balance = 5_000_000
        repo.resumed()
        delay(300)
        assertEquals("small/one", pickOn(ai), "just attached and read in full: nothing again yet")
        clock += 61_000
        repo.resumed()
        withTimeout(5_000) { while (pickOn(ai) != "small/two" || repo.account.value?.balanceMicro != 5_000_000L) delay(10) }
        val reads = inferenceReads.get()
        repo.resumed()
        clock += 30_000
        repo.resumed()
        delay(300)
        assertEquals(reads, inferenceReads.get(), "a menu closing is not a read")
    }

    // Every ten minutes for the life of the process, with no Armillary on
    // this device and with the app behind: the ship's cost for nobody.
    @Test
    fun `a device with no Armillary row reads no picks`() {
        vendor = ""
        rig(everyMs = 100, withRow = false) { _, _ ->
            val reads = inferenceReads.get()
            delay(500)
            assertEquals(reads, inferenceReads.get())
        }
    }

    @Test
    fun `behind, the picks wait for the app to come back, which reads them`() = rig(everyMs = 100) { repo, ai ->
        repo.paused()
        val reads = inferenceReads.get()
        pick = "small/two"
        delay(500)
        assertEquals(reads, inferenceReads.get(), "nothing read while behind")
        clock += 61_000
        repo.resumed()
        withTimeout(5_000) { while (pickOn(ai) != "small/two") delay(10) }
        val back = inferenceReads.get()
        withTimeout(5_000) { while (inferenceReads.get() < back + 2) delay(10) }
    }

    // A vendor changed or the key revoked: the old vendor's key stayed on
    // the row and the card said Connected.
    @Test
    fun `a ship that holds no key for this device takes the key off the row`() = rig(everyMs = 100) { repo, ai ->
        noKey = true
        withTimeout(5_000) { while (ai.state.value.savedProfile!!.provider(ARMILLARY_PROVIDER)!!.apiKey.isNotBlank()) delay(10) }
        assertEquals(null, repo.inference.value, "and the card does not say Connected")
        noKey = false
        repo.refresh()
        assertEquals("lease.key", ai.state.value.savedProfile!!.provider(ARMILLARY_PROVIDER)!!.apiKey, "a key read again goes back on")
    }

    // A 502 while the ship restarted left the card "Checking your ship"
    // all session, and nothing adopted the ship's account.
    @Test
    fun `a first check that failed is tried again until the ship answers`() {
        downFor.set(2)
        rig(everyMs = 3_600_000) { repo, _ ->
            assertEquals(ArmillaryAvailability.PRESENT, repo.availability.value)
            assertEquals(null, repo.error.value)
            assertTrue(accountReads.get() >= 1, "read in full once it answered")
        }
    }

    @Test
    fun `a failed check is said plainly, and that it is asked again`() {
        assertEquals("Your ship did not answer. Talon asks again by itself.", checkProblemLine(ArmillaryError.Unreachable(io.nisfeb.talon.orrery.ProxyAnswered(502))))
        assertEquals("Armillary on your ship could not answer: busy. Talon asks again by itself.", checkProblemLine(ArmillaryError.Refused(500, "busy")))
    }

    @Test
    fun `detaching stops the reads`() = rig(everyMs = 100) { repo, _ ->
        repo.detach()
        val reads = inferenceReads.get()
        delay(500)
        assertEquals(reads, inferenceReads.get())
    }
}
