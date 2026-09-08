package io.nisfeb.talon.call

/**
 * Save a recorded party line's mixed WAV to the user's device for the
 * "keep the full recording" option. Returns a human-readable location
 * (a path or folder name) on success, or null where the platform has
 * no backend yet. Desktop writes to ~/Downloads/Talon; Android's
 * MediaStore backend is a follow-up (recording is gated off there).
 */
expect suspend fun saveWavFile(bytes: ByteArray, name: String): String?

/**
 * Saves any file next to the recordings (Downloads/Talon on desktop
 * and Android) with the given extension and MIME type. Returns where
 * it went, or null where saving is not possible (iOS today).
 */
expect suspend fun saveFile(bytes: ByteArray, name: String, extension: String, mime: String): String?
