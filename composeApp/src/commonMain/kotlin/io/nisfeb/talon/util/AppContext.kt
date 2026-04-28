package io.nisfeb.talon.util

/**
 * Platform "context" abstraction. Lets commonMain APIs that need
 * platform-bound state (e.g. SharedPreferences on Android) declare
 * the dependency without importing `android.content.Context`.
 *
 * Both sides are abstract because Android's `Context` is abstract
 * and the `actual typealias` on android only matches an abstract
 * `expect class`. Practical consequence: callers on desktop don't
 * construct an `AppContext()` directly — desktop `actual` impls
 * for C1/C3/C4 either take no `AppContext` parameter (the cleanest
 * path) or commonMain declares two `expect` factory overloads (one
 * `AppContext`-taking for Android, one no-arg for desktop). C1 will
 * pick the idiom.
 *
 * - On Android, `AppContext` is a typealias for `android.content.Context`,
 *   so call sites pass `applicationContext` directly — no wrapper.
 * - On desktop, `AppContext` is an abstract marker class. If any
 *   commonMain API genuinely needs an instance, Stage D's desktop
 *   entry point can declare a concrete subclass singleton.
 */
expect abstract class AppContext
