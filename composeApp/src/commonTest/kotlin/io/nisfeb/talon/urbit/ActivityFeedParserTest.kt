package io.nisfeb.talon.urbit

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pin the activity-feed wire-shape parser. The cache that backs the
 * Activity rail tab depends on this returning a stable list shape
 * regardless of how malformed the ship's response is — silent failures
 * here used to surface as "Activity tab is empty" with no other clue.
 */
class ActivityFeedParserTest {

    @Test
    fun `null body returns empty list`() {
        assertEquals(emptyList(), TlonChatRepo.parseActivityFeedBody(null))
    }

    @Test
    fun `missing all key returns empty list`() {
        val body = buildJsonObject { put("unrelated", "data") }
        assertEquals(emptyList(), TlonChatRepo.parseActivityFeedBody(body))
    }

    @Test
    fun `all is non-array returns empty list`() {
        val body = buildJsonObject { put("all", "not-an-array") }
        assertEquals(emptyList(), TlonChatRepo.parseActivityFeedBody(body))
    }

    @Test
    fun `single post-mention bundle parses to one item with the right kind`() {
        val body = buildBody {
            bundle(sourceKey = "ship/~zod") {
                event(tag = "post-mention", time = "100", eventObj = buildJsonObject {
                    put("mention-author", "~bus")
                    put("content", "[hi]")
                })
            }
        }
        val items = TlonChatRepo.parseActivityFeedBody(body)
        assertEquals(1, items.size)
        val item = items[0]
        assertEquals("Mentioned you", item.kind)
        assertEquals("~bus", item.author)
        assertEquals("~zod", item.whom)
        // contentJson is `JsonElement.toString()` of whatever was at
        // `content` — which for a JsonPrimitive string adds quotes.
        assertEquals("\"[hi]\"", item.contentJson)
        assertEquals(100L, item.sentMs)
        assertEquals("~zod", item.title)
    }

    @Test
    fun `every supported tag maps to a human label`() {
        val mapping = mapOf(
            "post-mention" to "Mentioned you",
            "dm-post-mention" to "Mentioned you",
            "reply-mention" to "Mentioned you in a reply",
            "dm-reply-mention" to "Mentioned you in a reply",
            "reply" to "Replied",
            "dm-reply" to "Replied",
            "post" to "Posted",
            "dm-post" to "Posted",
            "dm-invite" to "Invited you to a DM",
            "group-ask" to "Requested group access",
            "group-invite" to "Invited you to a group",
        )
        for ((tag, expected) in mapping) {
            val body = buildBody {
                bundle(sourceKey = "ship/~zod") {
                    event(tag = tag, time = "100", eventObj = buildJsonObject {
                        put("author", "~bus")
                    })
                }
            }
            val items = TlonChatRepo.parseActivityFeedBody(body)
            assertEquals(1, items.size, "tag $tag should produce one item")
            assertEquals(expected, items[0].kind, "tag $tag should map to '$expected'")
        }
    }

    @Test
    fun `unknown tags fall through to the raw tag as kind`() {
        val body = buildBody {
            bundle(sourceKey = "ship/~zod") {
                event(tag = "shimmer-glimmer", time = "1", eventObj = buildJsonObject {
                    put("author", "~bus")
                })
            }
        }
        val items = TlonChatRepo.parseActivityFeedBody(body)
        assertEquals(1, items.size)
        assertEquals("shimmer-glimmer", items[0].kind)
    }

    @Test
    fun `items are sorted newest-first by sentMs`() {
        val body = buildBody {
            bundle(sourceKey = "ship/~zod") {
                event(tag = "post", time = "100", eventObj = buildJsonObject {
                    put("author", "~bus"); put("content", "old")
                })
                event(tag = "post", time = "300", eventObj = buildJsonObject {
                    put("author", "~bus"); put("content", "new")
                })
                event(tag = "post", time = "200", eventObj = buildJsonObject {
                    put("author", "~bus"); put("content", "middle")
                })
            }
        }
        val items = TlonChatRepo.parseActivityFeedBody(body)
        assertEquals(listOf(300L, 200L, 100L), items.map { it.sentMs })
    }

    @Test
    fun `malformed bundle is skipped silently`() {
        // Two bundles: first is a string (wrong shape), second is valid.
        // Old behavior would have crashed; new behavior skips.
        val body = buildJsonObject {
            put("all", buildJsonArray {
                add("garbage")
                add(buildJsonObject {
                    put("source-key", "ship/~zod")
                    put("events", buildJsonArray {
                        add(buildJsonObject {
                            put("event", buildJsonObject {
                                put("post", buildJsonObject {
                                    put("author", "~bus")
                                })
                            })
                            put("time", "1")
                        })
                    })
                })
            })
        }
        val items = TlonChatRepo.parseActivityFeedBody(body)
        assertEquals(1, items.size, "the valid bundle should still parse")
    }

    @Test
    fun `dotted urbit time strings parse to numeric ms`() {
        // Ships sometimes serve `time` as a dotted-decimal urbit numeric
        // (e.g., `1.760.123.456.789`). Strip the dots before parsing.
        val body = buildBody {
            bundle(sourceKey = "ship/~zod") {
                event(tag = "post", time = "1.760.123.456.789", eventObj = buildJsonObject {
                    put("author", "~bus")
                })
            }
        }
        val items = TlonChatRepo.parseActivityFeedBody(body)
        assertEquals(1L * 1_760_123_456_789, items[0].sentMs)
    }

    @Test
    fun `non-numeric time falls back to zero without crashing`() {
        val body = buildBody {
            bundle(sourceKey = "ship/~zod") {
                event(tag = "post", time = "tomorrow", eventObj = buildJsonObject {
                    put("author", "~bus")
                })
            }
        }
        val items = TlonChatRepo.parseActivityFeedBody(body)
        assertEquals(1, items.size)
        assertEquals(0L, items[0].sentMs)
    }

    @Test
    fun `channel source-key produces a hash-prefixed title`() {
        val body = buildBody {
            bundle(sourceKey = "channel/chat/~zod/test-channel") {
                event(tag = "post", time = "1", eventObj = buildJsonObject {
                    put("author", "~bus")
                })
            }
        }
        val items = TlonChatRepo.parseActivityFeedBody(body)
        assertNotNull(items.firstOrNull())
        assertTrue(items[0].title.startsWith("#"))
    }

    @Test
    fun `mention-author wins over author when both are present`() {
        val body = buildBody {
            bundle(sourceKey = "ship/~zod") {
                event(tag = "post-mention", time = "1", eventObj = buildJsonObject {
                    put("mention-author", "~mention")
                    put("author", "~plain")
                })
            }
        }
        val items = TlonChatRepo.parseActivityFeedBody(body)
        assertEquals("~mention", items[0].author)
    }

    // ---- builders -------------------------------------------------------

    private fun buildBody(builder: BodyBuilder.() -> Unit): JsonObject {
        val b = BodyBuilder()
        b.builder()
        return buildJsonObject {
            put("all", buildJsonArray {
                b.bundles.forEach { add(it) }
            })
        }
    }

    private class BodyBuilder {
        val bundles = mutableListOf<JsonObject>()
        fun bundle(sourceKey: String, builder: BundleBuilder.() -> Unit) {
            val bb = BundleBuilder()
            bb.builder()
            bundles += buildJsonObject {
                put("source-key", sourceKey)
                put("events", buildJsonArray { bb.events.forEach { add(it) } })
            }
        }
    }

    private class BundleBuilder {
        val events = mutableListOf<JsonObject>()
        fun event(tag: String, time: String, eventObj: JsonObject) {
            events += buildJsonObject {
                put("event", buildJsonObject { put(tag, eventObj) })
                put("time", time)
            }
        }
    }
}
