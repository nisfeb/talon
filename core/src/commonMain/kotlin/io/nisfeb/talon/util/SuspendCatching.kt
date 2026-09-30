package io.nisfeb.talon.util

/**
 * [runCatching] for work that suspends: a cancellation is not a failure,
 * and goes on up. Caught as one, a screen left mid-load showed "The
 * coroutine scope left the composition" as an error, a preview whose
 * row scrolled away was remembered as having none, and stopped work went
 * on to its next step.
 */
inline fun <T> runSuspendCatching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}
