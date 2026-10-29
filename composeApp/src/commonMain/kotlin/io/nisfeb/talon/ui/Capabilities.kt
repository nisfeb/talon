package io.nisfeb.talon.ui

/**
 * The platform capability registry. Every `expect val` flag below is
 * one row here; the flag's own KDoc says what it gates. Platforms:
 * A = Android, D = desktop, i = iOS.
 *
 *  - isVoiceMessagesSupported — A, i. D: no recorder pipeline on the JVM.
 *  - isOnDeviceAiSupported — A (MediaPipe); D by EmbedderProbe verdict (DJL ONNX SIGSEGVs some libstdc++ ABIs); i: no embedder port.
 *  - isAssistantSupported — A, D, i: a cloud LLM key + ship session is all it needs.
 *  - isLoopsSupported — A, D, i: AlarmManager on A; the while-open ticker (App.kt) on D/i.
 *  - isBackgroundSchedulingSupported — A (AlarmManager + BootReceiver); D/i: the ticker only runs while the app is open.
 *  - isQrScanSupported — A (ZXing), i (AVFoundation); D: no camera to assume.
 *  - isLocalCometSupported — D (runtime driven under a pseudo-terminal); A/i: no background process can host a ship.
 *  - isTouchSwipeNavSupported — A, i; D: drag detectors claim click-with-drift and deaden links/buttons.
 *  - hasSoftKeyboard — A, i; D: focusing a field pops no keyboard over the content.
 *  - isDictationSupported — A, i (the platform speech recogniser); D: no recogniser to call.
 *  - isQuickDictationSupported — A (widget, Quick Settings tile, assist role, a microphone foreground service); D/i: no such surfaces.
 *  - isTapToOpenMenuSupported — A, i; D: left-drag belongs to text selection, the menu is right-click.
 *  - isCallsSupported — A, D, i: a libwebrtc-backed CallEngine actual exists on each.
 *  - isCallRecordingSupported — A, D (party lines and 1:1 calls); i: the Swift bridge exposes no PCM tap.
 *  - isWindowFullScreenSupported — D (window placement is ours); A/i: the app already is the whole screen.
 *  - isWholeWindowZoom — D: Ctrl +/- and the slider zoom everything, as a browser does. A/i: the slider is text size; the system owns display size.
 *  - isBackgroundCallRingSupported — A (UnifiedPush), i (PushKit + CallKit). D: closing the window quits, so a ring lands only while Talon is open.
 *  - needsPushForBackgroundRing: A (without push only the open activity hears a ring, not after a reboot or a swipe). D: the calls line covers it. i: PushKit rings through the relay, set up at sign-in.
 *  - isVideoCallsSupported — A, D, i: camera capture + render end-to-end on all three.
 *  - isPartyVideoSupported — A, D, i: PeerLink publishes and renders cameras on all three.
 *  - isCameraSwitchSupported — A, i; D: a webcam has one lens and no switch.
 *  - isScreenShareSupported — D (webrtc-java captures a screen or window). A: port pending (MediaProjection behind a foreground service). i: port pending (a ReplayKit broadcast extension).
 *  - isEdgeSwipeBackSupported — i (Compose's own left-edge pan is the back gesture); A: the system back owns the edge; D: no touch edge.
 *  - isDrawerNavigation — A, i (no room for a rail); D: the permanent rail is already one click from anywhere.
 *  - isTouchPrimary — A, i (thumb-sized rows); D: mouse + close screen favour density.
 *  - needsEmojiFontSpans — D (a bundled emoji family does the work); A/i: colour emoji render in the default family.
 *  - needsManualImagePaste — D, i; A: the text field's content receiver takes pasted images.
 *  - isImmersiveCallSupported — A, i (full-screen call view); D: the inline roster toggles in place.
 *  - isUrbWebViewSupported — A, i (in-app webview popover); D: hands off to the system browser.
 *  - isLocalTriageSupported — D (llama.cpp on the JVM, or a local Ollama), A (MediaPipe LLM Inference), i (Apple's system model on iOS 26, else llama.cpp).
 *  - isLocationSharingSupported — A (LocationManager wakes a receiver on a significant move). D: a computer does not move with you. i: port pending (CLLocationManager significant-change monitoring, Always authorization, Info.plist strings).
 *  - isHealthSharingSupported — A (Health Connect: steps, exercise, sleep). D: a computer holds no health data. i: port pending (HealthKit statistics and sample queries, the HealthKit entitlement, Info.plist strings).
 *  - isRelayNotificationSetupNeeded — i: the app is suspended in the background, so the relay's push is its only notification. A: a foreground service keeps the ship's stream. D: notifies while it runs.
 *  - isAppIconBadgeSupported — i: the unread count on the app icon, a switch in Settings (off until turned on). A: launchers draw their own dot from the notifications. D: no icon to badge.
 *  - isArmillaryPurchaseSupported — A, D; i on the US App Store storefront only. Apple allows a button to an outside checkout there and nowhere else (guideline 3.1.1(a)), so elsewhere buying, its pitch and every "top up" go. The card, the balance and the history do not depend on it.
 *  - isPhoneDialSupported — A, i on an iPhone: the assistant's call_place opens the dialer at a number. D, iPad: no phone to dial, and tel: links go nowhere.
 *  - isBraveLeoSupported: D, i, where Brave takes a model of your own (Settings > Leo > Bring your own model; iOS since Brave 1.88). A: Brave for Android has no such form yet (brave-browser#59722).
 *
 * [platformLabel] and [isOnDeviceAiFeatureSupported] are declared
 * below too — a display name and a per-feature predicate, not flags.
 * So is [iosDeviceName]: "iPhone" or "iPad" on iOS, for the words only
 * iOS shows.
 */
expect val isVoiceMessagesSupported: Boolean

/** Whether a tel: link opens a dialer, so the assistant can offer call_place: a phone, not an iPad. */
expect val isPhoneDialSupported: Boolean

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
 * iOS: true, the same while-open ticker as desktop (App.kt is shared).
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
 * Whether the platform has an in-app camera QR scanner: login handoff
 * (see [io.nisfeb.talon.login.TalonLoginUri]), and joining a group or
 * inviting a ship from a scanned code. Android: true via
 * ZXing-android-embedded. iOS: true, AVFoundation's own reader
 * (QrLoginScanner.ios.kt). Desktop: false, no camera to assume.
 */
expect val isQrScanSupported: Boolean

/**
 * Whether a language model can run on this device for the orrery
 * triage (see docs/superpowers/specs/2026-09-17-orrery-client-design.md,
 * section 5). Desktop: true, the llama.cpp JVM binding behind a
 * child-process probe, or a local Ollama when one is running. Android:
 * true, MediaPipe LLM Inference with the same Qwen model. iOS: true,
 * Apple's on-device model on iOS 26 with Apple Intelligence, else
 * llama.cpp through the XCFramework with the same Qwen model.
 */
expect val isLocalTriageSupported: Boolean

/**
 * Whether Talon can run a comet on this machine for someone with no
 * ship (see [io.nisfeb.talon.comet.LocalShip]). Desktop: true, the
 * runtime is downloaded and driven under a pseudo-terminal. Android
 * and iOS: false — no background process can host a ship there.
 */
expect val isLocalCometSupported: Boolean

/**
 * Whether touch swipe-navigation gestures are wired. Android: true.
 * Desktop: false — a mouse can't "swipe" without ambiguity, and the
 * horizontal-drag detectors these gestures need compete with normal
 * clicks: a click with a few px of drift gets claimed as a
 * (sub-threshold) swipe and the child's click is cancelled, so
 * links / reactions / buttons appear dead.
 *
 * Gates two surfaces today:
 *  - swipe-a-message-row to quote it or open its thread, per the
 *    setting (DmChatScreen.MessageRow); desktop uses the ⋯ menu /
 *    right-click instead.
 *  - edge-swipe to open the ship-switcher drawer (App.kt
 *    ModalNavigationDrawer.gesturesEnabled); desktop opens it by
 *    clicking the Talon logo.
 */
expect val isTouchSwipeNavSupported: Boolean

/**
 * Whether focusing a text field raises a keyboard over the screen.
 * Android, iOS: true. Desktop: false. Decides whether a composer may
 * take focus the moment its screen opens: with a keyboard in hand
 * that is what a person wants; with one that pops up over the thing
 * they came to read, it is not.
 */
expect val hasSoftKeyboard: Boolean

/**
 * Whether the assistant can take an instruction by voice. Android and
 * iOS: true (the platform's own speech recogniser). Desktop: false, no
 * recogniser to call; the JVM has no speech API and the cloud path is
 * built for recorded calls, not a microphone.
 */
expect val isDictationSupported: Boolean

/**
 * Whether the zero-friction "dictate straight into orrery" surfaces
 * exist (home-screen widget, Quick Settings tile, assist-app). Android:
 * true, those are Android system integrations, and the recording runs in
 * a microphone foreground service. Desktop / iOS: false, no such
 * surfaces. See docs/superpowers/specs/2026-10-15-dictate-to-orrery-design.md.
 */
expect val isQuickDictationSupported: Boolean

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
 * What iOS text calls this device: "iPhone", or "iPad" on an iPad, which
 * was told about "this iPhone". "iPhone" elsewhere: those words only show
 * on iOS, and desktop tests draw them.
 */
expect val iosDeviceName: String

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
 * Whether a party line can be recorded and transcribed to Lattice
 * (wire 7), and of a 1:1 call (its peer told in the chat). True only
 * where PeerLink.onPcm and CallEngine.onPcm capture BOTH remote
 * speakers and our own mic end-to-end. Desktop: true (webrtc-java taps
 * remote tracks, and the self-mic through a standalone AudioRecorder —
 * a LOCAL track's addSink never fires). Android: true (remote via
 * AudioTrack.addSink, self-mic via JavaAudioDeviceModule's
 * samples-ready callback). iOS: false — the Swift bridge exposes no
 * PCM tap. Gates the record control per CLAUDE.md #3 (gate, don't fake).
 */
expect val isCallRecordingSupported: Boolean

/**
 * Whether the call view can take the host window full screen for a
 * meeting-style video layout. Desktop: true (the window's placement
 * is ours to set; Esc leaves). Android and iOS: false, the app is
 * already the whole screen and the immersive call view covers it.
 */
expect val isWindowFullScreenSupported: Boolean

/**
 * Whether the size setting zooms the whole window rather than the text
 * alone. Desktop: true. Ctrl +/- there is zoom, and text alone grew
 * past its icons and wrapped in boxes that stayed the same size. Android
 * and iOS: false, it is text size, beside the system's display size.
 */
expect val isWholeWindowZoom: Boolean

/**
 * Whether an incoming call can ring this device while the app is in
 * the background. Android: true (UnifiedPush wakes the process).
 * Desktop: false, closing the window quits Talon, so it rings only
 * while open (minimised or unfocused still rings). iOS: true
 * — PushKit wakes the app and CallKit rings it, over the relay's APNs
 * VoIP leg. Gates only an informational line in Settings' calls
 * section; nothing is greyed out.
 */
expect val isBackgroundCallRingSupported: Boolean

/**
 * Whether a ring with Talon not open needs a push set up. Android: true.
 * Without UnifiedPush, the call controller lives in the activity, so a
 * ring after a reboot or a swipe from recents goes unheard. Settings
 * says so while no push reaches the device (B3-3).
 */
expect val needsPushForBackgroundRing: Boolean

/**
 * Camera on a 1:1 call.
 *
 * True wherever a CallEngine can capture and render video, which is
 * currently all three platforms — Android and iOS through libwebrtc's
 * own renderer, desktop through a hand-written I420 converter. Party
 * lines stay audio-only regardless: this gates the 1:1 surface only.
 */
expect val isVideoCallsSupported: Boolean

/**
 * Camera video on a party line (conference). True where a PeerLink can
 * publish a camera and render remote cameras end-to-end: true on all
 * three — desktop (webrtc-java), Android (libwebrtc) and iOS (the
 * Swift bridge's capture + RTCMTLVideoView). Gates the party-line
 * camera control and video tiles (CLAUDE.md #3). Party audio still
 * works everywhere regardless.
 */
expect val isPartyVideoSupported: Boolean

/**
 * Sharing a screen or a window on a call or a party line, sent in place
 * of the camera. Desktop only: webrtc-java captures screens and windows
 * on X11, Wayland (through the system portal), macOS and Windows. Gates
 * the share control (CLAUDE.md #3); everyone can still see a share.
 */
expect val isScreenShareSupported: Boolean

/**
 * Whether the camera can be flipped front/back. Android: true
 * (CameraVideoCapturer.switchCamera). iOS: true (the capturer restarts
 * on the other device). Desktop: false — a webcam has one lens and no
 * switch. Gates the flip control (CLAUDE.md #3).
 */
expect val isCameraSwitchSupported: Boolean

/**
 * A swipe in from the left edge navigates *back* rather than opening
 * the ship switcher.
 *
 * True on iOS: Compose's view controller turns a pan in from the left
 * edge into back (PlatformBackHandler) — and going back is far more
 * frequent than switching ships, which keeps its tap affordance on the
 * Talon logo. False on Android, where the system back gesture
 * already owns the edge and the drawer can have the in-app swipe;
 * false on desktop, which has no touch edge at all.
 */
expect val isEdgeSwipeBackSupported: Boolean

/**
 * Whether sections are reached through a drawer rather than a rail.
 *
 * Android and iOS: true. A phone has no room for a permanent rail, so
 * a hamburger opens a drawer and that is the one way to every section.
 *
 * Desktop: false. The rail is already there and already one click from
 * anywhere; a hamburger beside it would be a second way to do the same
 * thing, and the screens keep their back buttons because a desktop has
 * neither an edge swipe nor a system back.
 */
expect val isDrawerNavigation: Boolean

/**
 * Whether the primary input is a finger rather than a pointer, so dense
 * lists get touch-sized rows.
 *
 * Android and iOS: true. Rows are read at arm's length and hit with a
 * thumb, so they need body-size text and 48dp targets.
 *
 * Desktop: false. A mouse and a close screen make the dense mail pane
 * the useful one; more rows on screen is the point there.
 */
expect val isTouchPrimary: Boolean

/** "Tap" where touch is the way in, "Click" where a pointer is: hints said tap on desktop. */
val tapWord: String get() = if (isTouchPrimary) "Tap" else "Click"

/** How a thing's own menu is opened: "Long-press" on touch, "Right-click" with a pointer. */
val holdWord: String get() = if (isTouchPrimary) "Long-press" else "Right-click"

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

/**
 * Whether a tapped `urb://` link opens in an in-app webview popover
 * (Android/iOS) versus the system browser (desktop). Both load the
 * same lattice reader URL on the viewer's ship; the phone keeps the
 * user in the app, the desktop hands off to a real browser where the
 * ship's web session already lives.
 */
expect val isUrbWebViewSupported: Boolean

/**
 * Whether this device can tell orrery where the owner is when they
 * move: the phone in their pocket, woken by the platform on a move of
 * a few hundred metres, with the app closed. Gates the switch under
 * the orrery triage row. See [LocationSharing].
 */
expect val isLocationSharingSupported: Boolean

/**
 * Whether this device can send orrery a summary of each day's health:
 * steps, workouts and sleep, read from the phone's health store. Gates
 * the switch under the location one. See [HealthSharing].
 */
expect val isHealthSharingSupported: Boolean

/**
 * Whether credit can be bought from inside the app.
 *
 * Armillary's card shows the balance, the warning, the history and the
 * vendor either way; this is the buying. Off, the sheet, the buttons,
 * the Top up on an empty balance and the pitch for Armillary all go,
 * and nothing says where else to pay: outside the US, Apple counts
 * even that as steering away from in-app purchase. Off only on iOS
 * outside the US storefront; see [outsideCheckoutAllowedOnAppStore].
 */
expect val isArmillaryPurchaseSupported: Boolean

/**
 * Whether an App Store storefront allows a button to a checkout outside
 * Apple's in-app purchase: the United States one only (App Review
 * guideline 3.1.1(a)). [storefrontCountry] is StoreKit's ISO 3166-1
 * alpha-3 code, null when StoreKit has none, which counts as not.
 */
fun outsideCheckoutAllowedOnAppStore(storefrontCountry: String?): Boolean = storefrontCountry == "USA"

/**
 * Whether this device gets notifications only through the relay, so
 * signing in asks about it once and Settings offers it plainly. Every
 * iPhone but one had never registered (2026-10-06): the way in was a
 * technical Settings panel, and they got no notifications at all.
 */
expect val isRelayNotificationSetupNeeded: Boolean

/** The unread count on the app icon, by the owner's choice (iOS). */
expect val isAppIconBadgeSupported: Boolean

/**
 * Whether Brave on this platform takes a model of the owner's own, so
 * the Armillary card can hand Leo its endpoint, model and key. Desktop
 * and iOS: Leo's "Bring your own model" form. Android: Brave has no such
 * form, so the card shows nothing to copy into it.
 */
expect val isBraveLeoSupported: Boolean
