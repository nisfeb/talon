package io.nisfeb.talon.ui

actual val isDailyDigestSupported: Boolean = false
actual val isVoiceMessagesSupported: Boolean = false

actual val platformLabel: String = run {
    val os = System.getProperty("os.name") ?: "Desktop"
    val ver = System.getProperty("os.version") ?: ""
    if (ver.isBlank()) "Desktop ($os)" else "Desktop ($os $ver)"
}
// On-device AI shut off on desktop. The HuggingFace Rust tokenizers
// JNI native lib (pulled in by DJL's OnnxRuntime engine for sentence
// embedding) SIGSEGVs in libstdc++ codecvt against modern Linux
// distros — confirmed reproduction on a user's Mageia/OpenMandriva
// host with libstdc++ 6.0.34. Until DJL ships a Rust JNI built
// against a newer libstdc++ ABI (or we swap to a non-Rust tokenizer),
// shipping the embedder would only give the user "Indexing forever"
// or a hard JVM abort. Off everywhere on desktop is honest.
//
// Pre-condition history that got us here:
// - Slim task was stripping `native/lib/tokenizers.properties` from
//   the tokenizers JAR — fixed in the same rc.
// - Model URL pointed at the PyTorch zoo while the engine is
//   OnnxRuntime — fixed (now `ai.djl.huggingface.onnxruntime`).
// - Even with both fixed, the Rust tokenizer crashes on first use.
actual val isOnDeviceAiSupported: Boolean = false

actual fun isOnDeviceAiFeatureSupported(
    @Suppress("UNUSED_PARAMETER") feature: io.nisfeb.talon.ai.AiSettings.Feature,
): Boolean = false
