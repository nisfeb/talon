package io.nisfeb.talon.ui

actual val isDailyDigestSupported: Boolean = false
actual val isVoiceMessagesSupported: Boolean = false
actual val isQrLoginScanSupported: Boolean = false
// Loops run on desktop via a while-open ticker in App.kt (no
// AlarmManager off Android): due loops fire once a minute while the
// window is live, and "Run now" works any time. Honest about the
// ceiling — loops only advance while the app is open, which the
// screen's copy states (CLAUDE.md §3: gate, don't fake).
actual val isLoopsSupported: Boolean = true
// No AlarmManager analog: nothing fires with the window closed.
actual val isBackgroundSchedulingSupported: Boolean = false
// Off on desktop: horizontal-drag detectors swallow child clicks when
// the mouse drifts a few px mid-click, and edge-swipe to open the
// ship drawer is meaningless with a mouse (the logo opens it).
actual val isTouchSwipeNavSupported: Boolean = false
// Off on desktop: left-press-drag selects message text (SelectionContainer);
// the action menu opens on right-click instead. A left-tap-to-open clickable
// here swallows the selection drag and pops the menu on mouse-up.
actual val isTapToOpenMenuSupported: Boolean = false

actual val platformLabel: String = run {
    val os = System.getProperty("os.name") ?: "Desktop"
    val ver = System.getProperty("os.version") ?: ""
    if (ver.isBlank()) "Desktop ($os)" else "Desktop ($os $ver)"
}
// On-device embedder, probe-gated on desktop. The HuggingFace Rust
// tokenizers JNI native lib (pulled in by DJL's OnnxRuntime engine)
// SIGSEGVs in libstdc++ codecvt on SOME Linux ABIs — one confirmed
// Mageia/OpenMandriva host with libstdc++ 6.0.34 — but runs cleanly on
// most modern distros (verified Arch/Manjaro 6.0.35, 384-dim output).
// The crash is a hard JVM abort, uncatchable in-process, so a static
// flag can't tell good hosts from bad. EmbedderProbe runs one embed in
// a child JVM once and caches the verdict; this flag reflects it. A
// "bad" or not-yet-probed host stays off and the assistant falls back
// to keyword search (see isAssistantSupported).
//
// Pre-condition history that got us here:
// - Slim task was stripping `native/lib/tokenizers.properties` from
//   the tokenizers JAR — fixed.
// - Model URL pointed at the PyTorch zoo while the engine is
//   OnnxRuntime — fixed (now `ai.djl.huggingface.onnxruntime`).
actual val isOnDeviceAiSupported: Boolean = io.nisfeb.talon.ai.EmbedderProbe.cachedVerdict()

// The assistant runs on desktop regardless of the embedder — it only
// needs a cloud key + ship session. Search/grouping degrade without
// the embedder (see isOnDeviceAiSupported).
actual val isAssistantSupported: Boolean = true

// On-device-only features (smart search, highlights) need the embedder.
actual fun isOnDeviceAiFeatureSupported(
    @Suppress("UNUSED_PARAMETER") feature: io.nisfeb.talon.ai.AiSettings.Feature,
): Boolean = isOnDeviceAiSupported

// Trunkline calls: webrtc-java engine (v0 spike surface).
actual val isCallsSupported: Boolean = true
