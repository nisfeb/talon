package io.nisfeb.talon.ui

actual val isDailyDigestSupported: Boolean = false
actual val isVoiceMessagesSupported: Boolean = false
// Smart search + important-message highlights via DJL ONNX in
// desktopMain/.../ai/DesktopEmbedder.kt. Model + tokenizer download
// on first use (~30 MB cached at ~/.djl.ai/cache/). The other
// on-device AI features (entity extraction chips) still need ML Kit
// — keep them off until/unless that ports.
actual val isOnDeviceAiSupported: Boolean = true

actual fun isOnDeviceAiFeatureSupported(
    feature: io.nisfeb.talon.ai.AiSettings.Feature,
): Boolean = when (feature) {
    // Sentence embedder is the prerequisite for both — wired today.
    io.nisfeb.talon.ai.AiSettings.Feature.SemanticSearch -> true
    io.nisfeb.talon.ai.AiSettings.Feature.ImportantMessages -> true
    // Topics also rides the embedder — kMeansAssign + chat-topic
    // clustering work the same way on both platforms.
    io.nisfeb.talon.ai.AiSettings.Feature.TopicClusters -> true
    // ML Kit Entity Extraction has no desktop port yet.
    io.nisfeb.talon.ai.AiSettings.Feature.EntityActions -> false
    // Cloud-key features are filtered out before this is consulted.
    else -> true
}
