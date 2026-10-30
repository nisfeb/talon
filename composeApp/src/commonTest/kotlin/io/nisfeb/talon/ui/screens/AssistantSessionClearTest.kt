package io.nisfeb.talon.ui.screens

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A new conversation while orrery's proposal waits for the owner. Clearing
 * answers it, which wakes its run, and the run goes on writing. It wrote
 * into the transcript clear was still walking: a ConcurrentModification
 * exception out of the New conversation tap (CI, 2026-10-29). Unconfined
 * resumes the run inside the answer, as a run on the screen's own thread
 * can.
 */
class AssistantSessionClearTest {
    @Test
    fun aProposalLeftWaitingLetsItsRunWriteOnIntoTheNewConversation() = runTest {
        val session = AssistantSession(CoroutineScope(Dispatchers.Unconfined))
        var word: Boolean? = true
        session.transcript.add(Line.You("Tell Rose I'm late"))
        session.scope.launch {
            word = session.propose("Send this?", emptyList())
            session.transcript.add(Line.Said("It waits for you under Actions."))
        }
        session.clear()
        assertNull(word, "left waiting, under Actions")
        assertEquals(listOf<Line>(Line.Said("It waits for you under Actions.")), session.transcript.toList())
    }
}
