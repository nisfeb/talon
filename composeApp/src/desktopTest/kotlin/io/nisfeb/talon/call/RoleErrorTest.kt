package io.nisfeb.talon.call

import io.nisfeb.talon.urbit.FakeShip
import io.nisfeb.talon.urbit.UrbitSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A refused gate change stays said until dismissed or a change goes
 * through. The admin screen asks the host for the gates as it opens,
 * and that read's ack cleared a refusal made while it was out.
 */
class RoleErrorTest {
    @Test
    fun `a read going through leaves a refused change said`() = runBlocking {
        val ship = FakeShip("~zod").apply {
            scries["trunk/version"] = """{"wire":9}"""
            scries["trunk/policy"] = "{}"
            scries["trunk/ice"] = "[]"
        }
        val calls = CallController(
            UrbitSession(ship.http, ship.session).apply { tryRestore("~zod") },
            CallEngineProvider { error("no media") },
        ).apply { start() }
        try {
            withTimeout(10_000) { while (!calls.connected.value) delay(20) }
            ship.refuse = { if ("set-room-access" in it.json.toString()) "no roles here" else null }
            calls.setRoomAccess("~zod", "line", listOf("admin"), null)
            val refused = assertNotNull(calls.roleError.value)
            calls.getRoomAccess("~zod", "line")
            assertEquals(refused, calls.roleError.value, "a read is not an answer to the change")
            ship.refuse = { null }
            calls.setRoomAccess("~zod", "line", listOf("admin"), null)
            assertNull(calls.roleError.value, "a change that went through")
        } finally {
            calls.stop()
        }
    }
}
