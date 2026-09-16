package io.nisfeb.talon.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import io.nisfeb.talon.notify.IosVoipBridge
import io.nisfeb.talon.util.Log
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryRecord
import platform.AVFAudio.setActive
import platform.Foundation.NSTimer
import platform.Speech.SFSpeechAudioBufferRecognitionRequest
import platform.Speech.SFSpeechRecognitionTask
import platform.Speech.SFSpeechRecognizer
import platform.Speech.SFSpeechRecognizerAuthorizationStatus
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/** Apple's speech recogniser on the microphone, until the speaker stops. */
@Composable
actual fun rememberDictation(onResult: (String) -> Unit): (() -> Unit)? {
    val current = rememberUpdatedState(onResult)
    return remember { { IosDictation.start { current.value(it) } } }
}

@OptIn(ExperimentalForeignApi::class)
private object IosDictation {
    private const val TAG = "IosDictation"
    private var engine: AVAudioEngine? = null
    private var task: SFSpeechRecognitionTask? = null
    private var request: SFSpeechAudioBufferRecognitionRequest? = null
    private var quiet: NSTimer? = null

    fun start(onResult: (String) -> Unit) {
        SFSpeechRecognizer.requestAuthorization { status ->
            if (status != SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusAuthorized) {
                Log.w(TAG, "speech recognition not authorized: $status")
                return@requestAuthorization
            }
            // Speech authorization says nothing about the microphone: a
            // previously-denied mic would otherwise fail silently into a
            // fifteen-second no-op listen.
            AVAudioSession.sharedInstance().requestRecordPermission { granted ->
                if (!granted) {
                    Log.w(TAG, "microphone permission denied — dictation unavailable")
                    return@requestRecordPermission
                }
                dispatch_async(dispatch_get_main_queue()) { listen(onResult) }
            }
        }
    }

    private fun listen(onResult: (String) -> Unit) {
        stop()
        val recognizer = SFSpeechRecognizer() ?: return
        // Info.plist promises the transcription happens on this device;
        // a recognizer that can't do that has no business streaming the
        // mic to Apple's servers under that wording.
        if (!recognizer.supportsOnDeviceRecognition) {
            Log.w(TAG, "on-device speech recognition unsupported on this device — refusing to stream audio off-device")
            return
        }
        runCatching {
            val session = AVAudioSession.sharedInstance()
            session.setCategory(AVAudioSessionCategoryRecord, null)
            session.setActive(true, null)
        }
        val req = SFSpeechAudioBufferRecognitionRequest().also {
            it.shouldReportPartialResults = true
            it.requiresOnDeviceRecognition = true
        }
        val eng = AVAudioEngine()
        val input = eng.inputNode
        input.installTapOnBus(0u, 1024u, input.outputFormatForBus(0u)) { buffer, _ ->
            buffer?.let { req.appendAudioPCMBuffer(it) }
        }
        eng.prepare()
        if (!eng.startAndReturnError(null)) { input.removeTapOnBus(0u); return }
        engine = eng; request = req
        var heard = ""
        var delivered = false
        fun finish() {
            if (delivered) return
            delivered = true
            stop()
            if (heard.isNotBlank()) onResult(heard)
        }
        // The speaker stopping is the end: a second and a half without a
        // new word, or fifteen seconds in all.
        fun armQuiet() {
            quiet?.invalidate()
            quiet = NSTimer.scheduledTimerWithTimeInterval(1.5, false) { finish() }
        }
        task = recognizer.recognitionTaskWithRequest(req) { result, error ->
            // This handler runs on the recognition task's own GCD queue,
            // which has no run loop — an NSTimer armed here never fires
            // and silence detection dies with it. Do the work on main.
            dispatch_async(dispatch_get_main_queue()) {
                if (result != null) {
                    heard = result.bestTranscription.formattedString
                    if (result.isFinal()) finish() else armQuiet()
                }
                if (error != null) finish()
            }
        }
        NSTimer.scheduledTimerWithTimeInterval(15.0, false) { finish() }
    }

    private fun stop() {
        quiet?.invalidate(); quiet = null
        task?.cancel(); task = null
        request?.endAudio(); request = null
        engine?.let { it.stop(); it.inputNode.removeTapOnBus(0u) }
        engine = null
        // Hand the Record session back — unless a call took it over in
        // the meantime, in which case TalonRtc owns it and deactivating
        // here would drop the call's audio.
        if (!IosVoipBridge.callLive.value) {
            runCatching { AVAudioSession.sharedInstance().setActive(false, null) }
        }
    }
}
