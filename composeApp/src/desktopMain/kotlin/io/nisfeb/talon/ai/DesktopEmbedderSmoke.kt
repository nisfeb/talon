package io.nisfeb.talon.ai

import kotlinx.coroutines.runBlocking

/**
 * Manual smoke for [DesktopEmbedder] — answers one question: does DJL's
 * ONNX Runtime + HuggingFace tokenizer actually run on THIS host, or
 * does the Rust JNI SIGSEGV against the local libstdc++ (the reason
 * `isOnDeviceAiSupported` is off on desktop)? Not on any lifecycle:
 *
 *     ./gradlew :composeApp:embedderSmoke
 *
 * Prints the dims and a cosine sanity check (related > unrelated). A
 * hard JVM abort here = the documented crash still reproduces; a clean
 * vector = the embedder is shippable on this platform.
 */
fun main() = runBlocking {
    val e = DesktopEmbedder()
    println("[embedderSmoke] loading model + tokenizer (cold = ~80MB download)…")
    val a = e.embed("the weather in London is rainy today")
    val b = e.embed("forecast says rain over the UK")
    val c = e.embed("my favorite pasta recipe uses garlic")
    if (a == null || b == null || c == null) {
        println("[embedderSmoke] FAILED — embed returned null (see logged cause chain).")
        return@runBlocking
    }
    fun cos(x: FloatArray, y: FloatArray): Float {
        var d = 0f; for (i in x.indices) d += x[i] * y[i]; return d
    }
    println("[embedderSmoke] OK dim=${a.size}  related=${cos(a, b)}  unrelated=${cos(a, c)}")
    println("[embedderSmoke] ${if (cos(a, b) > cos(a, c)) "PASS (related > unrelated)" else "WEAK (ordering off)"}")
}
