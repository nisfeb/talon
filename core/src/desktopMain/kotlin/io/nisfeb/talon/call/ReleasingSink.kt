package io.nisfeb.talon.call

import dev.onvoid.webrtc.media.video.VideoFrame
import dev.onvoid.webrtc.media.video.VideoTrackSink

/**
 * A video sink that hands each frame to [onFrame], then lets it go.
 *
 * webrtc-java gives every sink its own native copy of each frame and
 * holds it until the sink releases it (VideoTrackSink.cpp: I420Buffer::
 * Copy, then AddRef; devopvoid/webrtc-java#191). Two sinks that only
 * noted the time never did, and a call leaked a whole frame each time
 * one came: about 47 MB a second of a 1080p screen share, 16 GB in an
 * afternoon of calls (2026-10-09). Every sink in Talon is made here;
 * WebRtcSinksGuardTest fails the build for one that is not.
 */
fun releasingSink(onFrame: (VideoFrame) -> Unit): VideoTrackSink = VideoTrackSink { frame ->
    try {
        onFrame(frame)
    } finally {
        frame.release()
    }
}
