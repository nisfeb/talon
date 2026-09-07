package io.nisfeb.talon.call

import kotlin.test.Test
import kotlin.test.assertEquals

class OpusSdpTest {
    @Test
    fun replacesOrAddsTheBitrateOnOpusOnly() {
        val withFmtp = "m=audio 9 UDP/TLS/RTP/SAVPF 111 63\r\n" +
            "a=rtpmap:111 opus/48000/2\r\n" +
            "a=fmtp:111 minptime=10;useinbandfec=1;maxaveragebitrate=20000\r\n" +
            "a=rtpmap:63 red/48000/2\r\n" +
            "a=fmtp:63 111/111\r\n"
        assertEquals(
            "m=audio 9 UDP/TLS/RTP/SAVPF 111 63\r\n" +
                "a=rtpmap:111 opus/48000/2\r\n" +
                "a=fmtp:111 minptime=10;useinbandfec=1;maxaveragebitrate=128000\r\n" +
                "a=rtpmap:63 red/48000/2\r\n" +
                "a=fmtp:63 111/111\r\n",
            withOpusBitrate(withFmtp, 128_000),
        )
        val bare = "a=rtpmap:111 opus/48000/2\na=rtcp-fb:111 transport-cc\n"
        assertEquals(
            "a=rtpmap:111 opus/48000/2\na=fmtp:111 maxaveragebitrate=96000\na=rtcp-fb:111 transport-cc\n",
            withOpusBitrate(bare, 96_000),
        )
        assertEquals("m=video 9\r\n", withOpusBitrate("m=video 9\r\n", 96_000))
    }
}
