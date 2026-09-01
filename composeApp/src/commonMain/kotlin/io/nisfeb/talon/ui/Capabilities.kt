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
 * Whether scheduled work fires with the app closed. Android: true —
 * AlarmManager + BootReceiver. Desktop: false — the ticker only runs
 * while the window is open. Nothing is gated on this; it selects the
 * honest wording on the Loops screen so a desktop user isn't promised a
 * schedule the platform can't keep (CLAUDE.md §3).
 */
expect val isBackgroundSchedulingSupported: Boolean

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
 * Whether a plain left-tap on a message opens its action menu.
 * Android: true — tap-anywhere is the menu affordance (no right mouse
 * button, and long-press is reserved for text selection). Desktop:
 * false — a left-press-drag belongs to the SelectionContainer (select
 * message text); a row-level clickable would swallow that drag and pop
 * the menu on mouse-up instead. Desktop opens the menu via right-click
 * (onSecondaryClick) on the row. Gates the row clickable + StoryRenderer
 * onMessageTap in DmChatScreen.MessageRow.
 */
expect val isTapToOpenMenuSupported: Boolean

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
 * platform impl hasn't landed. (Currently the on-device feature is
 * SmartFeatures — the embedder suite — supported wherever the
 * embedder is.)
 */
expect fun isOnDeviceAiFeatureSupported(
    feature: io.nisfeb.talon.ai.AiSettings.Feature,
): Boolean

/**
 * Trunkline 1:1 calls (design doc: Trunkline §07). True where a
 * libwebrtc-backed CallEngine actual exists. Desktop first (webrtc-java);
 * Android lands v1 (libwebrtc), iOS v2 (WebRTC.xcframework +
 * foreground-only until APNs VoIP).
 */
expect val isCallsSupported: Boolean

/**
 * Whether an incoming call can ring this device while the app is in
 * the background. Android: true (UnifiedPush wakes the process).
 * Desktop: true (a long-running process hears the channel). iOS: false
 * — no CallKit/PushKit by design, and UIBackgroundModes audio only
 * sustains a call already in progress. Gates only an informational
 * line in Settings' calls section; nothing is greyed out.
 */
expect val isBackgroundCallRingSupported: Boolean

/**
 * A swipe in from the left edge navigates *back* rather than opening
 * the ship switcher.
 *
 * True on iOS: a Compose view controller gets none of UIKit's
 * navigation gesture, so the app supplies it — and going back is far
 * more frequent than switching ships, which keeps its tap affordance
 * on the Talon logo. False on Android, where the system back gesture
 * already owns the edge and the drawer can have the in-app swipe;
 * false on desktop, which has no touch edge at all.
 */
expect val isEdgeSwipeBackSupported: Boolean

/**
 * Whether emoji need an explicit font span to render in colour.
 *
 * True only on desktop, where [EmojiFontFamily] resolves to a bundled
 * system emoji family; Android and iOS already draw colour emoji in
 * the default family, so the span changes nothing there. It is not
 * free, though: a VisualTransformation on a text field has a long
 * history of breaking iOS's native text-actions menu (paste), so the
 * composer only pays for it where it buys something.
 */
expect val needsEmojiFontSpans: Boolean

/**
 * Whether the composer must offer its own "paste image" action.
 *
 * Android receives pasted images through the text field's content
 * receiver and desktop through its Ctrl+V intercept, so on those the
 * system paste gesture is enough. iOS has neither hook — the system
 * Paste item on a plain text field yields text only — so image paste
 * needs a button of its own, or it silently does nothing.
 */
expect val needsManualImagePaste: Boolean

/**
 * Whether the party-line / call bar's expand arrow opens a full-screen
 * call view with large touch targets (phones), versus toggling the
 * inline roster in place (desktop, where the strip has room and the
 * pointer is precise). Android/iOS: true. Desktop: false.
 */
expect val isImmersiveCallSupported: Boolean
