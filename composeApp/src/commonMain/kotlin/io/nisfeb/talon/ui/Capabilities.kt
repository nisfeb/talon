package io.nisfeb.talon.ui

/**
 * Per-platform feature flags. A flag is `true` only when the platform
 * has the infrastructure to support the feature end-to-end; UI gates
 * itself on these so half-implemented features don't surface broken.
 *
 * Today these mirror the port plan's "out of scope on desktop" list.
 * Stage F or beyond can flip individual flags as features get the
 * platform glue they need.
 */
expect val isDailyDigestSupported: Boolean
expect val isVoiceMessagesSupported: Boolean

/**
 * Whether the **on-device embedder** runs on this platform — the
 * backbone for semantic search, important-message highlights, and the
 * assistant's auto topic-grouping. Android: true (MediaPipe, 100-dim).
 * Desktop: the [io.nisfeb.talon.ai.EmbedderProbe] verdict (DJL ONNX,
 * 384-dim) — works on most hosts but SIGSEGVs on a few Linux libstdc++
 * ABIs, so it's gated on a one-time child-process probe. This is NOT
 * the gate for the assistant itself (see [isAssistantSupported]); the
 * assistant degrades to keyword search + flat history without it.
 */
expect val isOnDeviceAiSupported: Boolean

/**
 * Whether the AI **assistant** can be offered at all. True on both
 * Android and desktop: the assistant only needs a cloud LLM key + a
 * ship session, both of which every platform has. Retrieval and
 * topic-grouping are *enhanced* by [isOnDeviceAiSupported] but degrade
 * gracefully (lexical keyword search, flat history) where it's off.
 * Gates the assistant entry point and the Agent/Mcp settings toggles.
 */
expect val isAssistantSupported: Boolean

/**
 * Whether user-defined **Loops** (a saved prompt run on a schedule
 * through the assistant agent, headless) are offered. Android: true —
 * AlarmManager fires the loop even with the app closed. Desktop: true —
 * a coroutine ticker (App.kt) runs due loops while the window is open;
 * loops only advance with the app running, which the screen's copy
 * states (CLAUDE.md §3: an honest ceiling, not a faked schedule).
 * Gates the Loops screen + nav entry (which also require an LLM key).
 */
expect val isLoopsSupported: Boolean

/**
 * Whether the platform can launch an in-app QR scanner for login
 * handoff (see [io.nisfeb.talon.login.TalonLoginUri]). Android: true
 * via ML Kit's GoogleCodeScanner (Play Services). Desktop: false —
 * desktops have keyboards, the manual form is already the fast path.
 */
expect val isQrLoginScanSupported: Boolean

/**
 * Whether touch swipe-navigation gestures are wired. Android: true.
 * Desktop: false — a mouse can't "swipe" without ambiguity, and the
 * horizontal-drag detectors these gestures need compete with normal
 * clicks: a click with a few px of drift gets claimed as a
 * (sub-threshold) swipe and the child's click is cancelled, so
 * links / reactions / buttons appear dead.
 *
 * Gates two surfaces today:
 *  - swipe-a-message-row to open its thread (DmChatScreen.MessageRow);
 *    desktop uses the reply-count pill / ⋯ menu / right-click instead.
 *  - edge-swipe to open the ship-switcher drawer (App.kt
 *    ModalNavigationDrawer.gesturesEnabled); desktop opens it by
 *    clicking the Talon logo.
 */
expect val isTouchSwipeNavSupported: Boolean

/**
 * Short human-readable name for the host platform — surfaced in the
 * About panel so the user can see at a glance which build they're on.
 * Android returns "Android"; desktop returns the os.name (e.g. "Linux",
 * "Mac OS X", "Windows 11").
 */
expect val platformLabel: String

/**
 * Per-feature supported predicate. The [isOnDeviceAiSupported] flag
 * gates whether the on-device-AI section of SettingsScreen renders
 * at all; this finer predicate then hides individual toggles whose
 * platform impl hasn't landed (e.g. EntityActions wants ML Kit on
 * Android, no equivalent on desktop yet).
 */
expect fun isOnDeviceAiFeatureSupported(
    feature: io.nisfeb.talon.ai.AiSettings.Feature,
): Boolean
