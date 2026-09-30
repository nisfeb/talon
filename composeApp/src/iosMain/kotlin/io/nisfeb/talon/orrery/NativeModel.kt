package io.nisfeb.talon.orrery

/**
 * What the Swift side gives the ladder on iOS: models, and the
 * download they need. Implemented in iosApp/TalonModels.swift and
 * handed to `MainViewController(rtc:models:)` the way the call engine
 * is. Every call here is synchronous; Kotlin moves them off the main
 * thread.
 */
interface NativeModel {
    /** The rung's name for Settings. */
    val name: String

    /** Answer [user] under [system], bounded by [grammar] where the runtime can, in at most [maxTokens]. */
    fun complete(system: String, user: String, grammar: String?, maxTokens: Int): String

    fun close()
}

interface NativeModelFactory {
    /** Apple's on-device model, on iOS 26 and an Apple Intelligence device; null elsewhere. */
    fun openSystemModel(): NativeModel?

    /** Why [openSystemModel] answers null, in words, or null when it would not. */
    fun systemModelUnavailableReason(): String?

    /** llama.cpp over the GGUF file at [path]; null when it will not load. */
    fun openLlama(path: String): NativeModel?

    /** Fetch [url] to [toPath], reporting 0 to 1, then done with null or the reason it failed. */
    fun download(url: String, toPath: String, progress: (Float) -> Unit, done: (String?) -> Unit)
}
