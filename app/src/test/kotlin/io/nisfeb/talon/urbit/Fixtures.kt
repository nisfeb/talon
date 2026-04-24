package io.nisfeb.talon.urbit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Tiny helper so tests read JSON from `src/test/resources/fixtures/`
 * by a logical name instead of inlining giant blobs.
 *
 *   val blob = Fixtures.load("activity/channel-unread.json")
 *
 * Each fixture should be a real captured payload from adb logcat or
 * curl. When the upstream shape changes, update the fixture — the
 * parser tests will tell you exactly which field moved.
 */
internal object Fixtures {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun load(path: String): String {
        val resource = javaClass.classLoader.getResourceAsStream("fixtures/$path")
            ?: error("fixture not found: fixtures/$path")
        return resource.bufferedReader().use { it.readText() }
    }

    fun loadJson(path: String): JsonElement = json.parseToJsonElement(load(path))

    fun loadObject(path: String): JsonObject = loadJson(path).jsonObject
}
