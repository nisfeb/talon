package io.nisfeb.talon.call

// Android call recording is gated off (isCallRecordingSupported=false)
// until self-mic capture + a MediaStore save backend land. Returning
// null keeps the seam honest rather than faking a save.
actual suspend fun saveWavFile(bytes: ByteArray, name: String): String? = null
