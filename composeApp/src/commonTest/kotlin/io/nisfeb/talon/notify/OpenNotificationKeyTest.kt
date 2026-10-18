package io.nisfeb.talon.notify

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A clicked desktop notification only raised the window. By its key, a
 * chat now opens and a notice's tag lands where its Activity row does.
 */
class OpenNotificationKeyTest {
    @Test
    fun `a chat key opens the chat and a notice tag its place and mail nothing`() = runTest {
        val chats = mutableListOf<OpenChatRequests.Request>()
        val notices = mutableListOf<String>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { OpenChatRequests.requests.collect { chats += it } }
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { OpenNoticeRequests.requests.collect { notices += it } }
        for (key in listOf("~bus", "chat/~bus/general", "0v4.club", "invite:~bus/crew", "orrery:a1", "mail:t1", "action:a1")) {
            openNotificationKey(key)
        }
        testScheduler.runCurrent()
        assertEquals(listOf("~bus", "chat/~bus/general", "0v4.club"), chats.map { it.whom })
        assertEquals(listOf(null, null, null), chats.map { it.forShip })
        assertEquals(listOf("invite:~bus/crew", "orrery:a1"), notices)
    }
}
