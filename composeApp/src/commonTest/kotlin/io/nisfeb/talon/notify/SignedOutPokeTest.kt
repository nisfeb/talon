package io.nisfeb.talon.notify

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import io.nisfeb.talon.urbit.SavedSession
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Signing out: the ship's trunk is told with the saved cookie, the app's
 *  own connection being gone by then. The poke as eyre's channel takes it. */
class SignedOutPokeTest {
    @Test
    fun the_unregister_goes_on_its_own_channel_with_the_saved_cookie() = runTest {
        val seen = mutableListOf<String>()
        val http = HttpClient(MockEngine { req ->
            seen += "${req.method.value} ${req.url.encodedPath} cookie=${req.headers["Cookie"]} ${req.body.toByteArray().decodeToString()}"
            respond("", HttpStatusCode.NoContent)
        })
        val saved = SavedSession("https://zod.test/", "~zod", "test-cookie", "test-value", "zod.test")
        pokeTrunkSignedOut(saved, TrunkPush.unregister("t-1"), http)
        assertEquals(2, seen.size)
        val (put, del) = seen
        assertTrue(put.startsWith("PUT /~/channel/talon-out-"), put)
        assertTrue("cookie=test-cookie=test-value " in put, put)
        assertTrue(
            put.endsWith("""[{"id":1,"action":"poke","ship":"zod","app":"trunk","mark":"trunk-action","json":{"push-unregister":"t-1"}}]"""),
            put,
        )
        assertTrue(del.startsWith("DELETE /~/channel/talon-out-") && "cookie=test-cookie=test-value" in del, del)
    }
}
