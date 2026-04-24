package io.nisfeb.talon.urbit

import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelTypeTest {

    @Test
    fun `chat nest resolves to Chat`() {
        assertEquals(
            ChannelType.Chat,
            ChannelType.fromWhom("chat/~sampel-palnet/general"),
        )
    }

    @Test
    fun `diary nest resolves to Notebook`() {
        assertEquals(
            ChannelType.Notebook,
            ChannelType.fromWhom("diary/~sampel-palnet/journal"),
        )
    }

    @Test
    fun `heap nest resolves to Gallery`() {
        assertEquals(
            ChannelType.Gallery,
            ChannelType.fromWhom("heap/~sampel-palnet/pics"),
        )
    }

    @Test
    fun `1-to-1 DM resolves to Chat`() {
        assertEquals(ChannelType.Chat, ChannelType.fromWhom("~sampel-palnet"))
    }

    @Test
    fun `club DM resolves to Chat`() {
        assertEquals(ChannelType.Chat, ChannelType.fromWhom("0v4.abcde.fghij"))
    }

    @Test
    fun `unknown prefix treated as Chat`() {
        // Defensive: anything the parser doesn't recognize falls through
        // to Chat rather than crashing.
        assertEquals(ChannelType.Chat, ChannelType.fromWhom("mystery/~ship/slug"))
        assertEquals(ChannelType.Chat, ChannelType.fromWhom("nonsense"))
    }

    @Test
    fun `agentKind maps each type to its wire string`() {
        assertEquals("/chat", ChannelType.agentKind(ChannelType.Chat))
        assertEquals("/diary", ChannelType.agentKind(ChannelType.Notebook))
        assertEquals("/heap", ChannelType.agentKind(ChannelType.Gallery))
    }
}
