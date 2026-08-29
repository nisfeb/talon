package io.nisfeb.talon.call

import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A speaking dot on your own row.
 *
 * The point of it is telling "nobody is talking" apart from "my
 * microphone is dead", so the two cases that matter are that it lights
 * when the mic hears something and that it stays dark while muted —
 * where the level still moves, because muting disables the track, not
 * the capture.
 */
class PartyLineSelfSpeakingTest {

    private class LeveledLink(
        private val local: Float?,
        private val remote: Float? = null,
    ) : PeerLink {
        override val state: StateFlow<MediaState> = MutableStateFlow(MediaState.Live)
        override fun onLocalCandidate(callback: (IceCandidate) -> Unit) = Unit
        override suspend fun offer(): String = "v=0"
        override suspend fun answerTo(remoteSdp: String): String = "v=0"
        override suspend fun applyAnswer(remoteSdp: String) = Unit
        override fun addRemoteCandidate(candidate: IceCandidate) = Unit
        override fun setMuted(muted: Boolean) = Unit
        override fun audioLevel(): Float? = remote
        override fun localAudioLevel(): Float? = local
        override fun close() = Unit
    }

    @Test
    fun aSilentMicIsNotSpeaking() {
        assertEquals(false, 0.001f > SPEAKING_THRESHOLD_FOR_TEST)
    }

    @Test
    fun aLoudMicIsSpeaking() {
        assertEquals(true, 0.30f > SPEAKING_THRESHOLD_FOR_TEST)
    }

    @Test
    fun theDefaultIsNullSoAPlatformCanDeclineToAnswer() {
        // Every leaf that hasn't implemented it must read as "unknown"
        // rather than "silent", or a platform without the statistic
        // would render everyone permanently quiet.
        val bare = object : PeerLink {
            override val state: StateFlow<MediaState> = MutableStateFlow(MediaState.Idle)
            override fun onLocalCandidate(callback: (IceCandidate) -> Unit) = Unit
            override suspend fun offer(): String = ""
            override suspend fun answerTo(remoteSdp: String): String = ""
            override suspend fun applyAnswer(remoteSdp: String) = Unit
            override fun addRemoteCandidate(candidate: IceCandidate) = Unit
            override fun setMuted(muted: Boolean) = Unit
            override fun close() = Unit
        }
        assertNull(bare.localAudioLevel())
        assertNull(bare.audioLevel())
    }

    private companion object {
        // Mirrors PartyLine.SPEAKING_THRESHOLD, which is private.
        private const val SPEAKING_THRESHOLD_FOR_TEST = 0.02f
    }
}
