package io.nisfeb.talon.call

data class VideoDevice(val id: String, val label: String)

/**
 * The cameras a call can send from, the way [AudioDevices] lists
 * microphones and speakers. Desktop enumerates real devices; phones
 * keep their front/back flip and leave this unsupported.
 */
interface VideoDevices {
    val supported: Boolean get() = false
    fun cameras(): List<VideoDevice> = emptyList()
    val selectedCamera: String? get() = null
    /** Remembered for the next camera start; a live camera is restarted by the caller. */
    fun selectCamera(id: String?) = Unit

    companion object {
        val Noop: VideoDevices = object : VideoDevices {}
    }
}
