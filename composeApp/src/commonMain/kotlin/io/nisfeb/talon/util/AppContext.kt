package io.nisfeb.talon.util

/**
 * Platform "context" abstraction. Lets commonMain APIs that need
 * platform-bound state (e.g. SharedPreferences on Android) declare
 * the dependency without importing `android.content.Context`.
 *
 * - On Android, `AppContext` is a typealias for `android.content.Context`.
 *   Existing call sites that pass `applicationContext` keep working.
 * - On desktop, `AppContext` is an empty marker class. Construct one
 *   with `AppContext()` and pass it through; the receiving APIs
 *   ignore it (desktop platforms don't need a context for the
 *   commonMain abstractions C1/C3/C4 introduce).
 */
expect abstract class AppContext
