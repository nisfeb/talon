package io.nisfeb.talon.call

import dev.onvoid.webrtc.media.MediaDevices
import io.nisfeb.talon.util.Log

/** webrtc-java's camera list; the choice is read by the engines when a camera starts. */
class DesktopVideoDevices : VideoDevices {
    override val supported: Boolean get() = true

    override fun cameras(): List<VideoDevice> = runCatching {
        MediaDevices.getVideoCaptureDevices().map { VideoDevice(it.name, it.name) }
    }.onFailure { Log.w("VideoDevices", "camera enumeration failed", it) }.getOrDefault(emptyList())

    override val selectedCamera: String? get() = preferred

    override fun selectCamera(id: String?) {
        preferred = id
        Log.i("VideoDevices", "camera -> ${id ?: "(default)"}")
    }

    companion object {
        /** The camera name the engines open, or null for the first one found. */
        @Volatile var preferred: String? = null

        /** The device to open now: the preferred one if still present, else the first. */
        fun pick(): dev.onvoid.webrtc.media.video.VideoDevice? {
            val all = MediaDevices.getVideoCaptureDevices()
            val want = preferred
            return all.firstOrNull { want != null && it.name == want } ?: all.firstOrNull()
        }
    }
}
