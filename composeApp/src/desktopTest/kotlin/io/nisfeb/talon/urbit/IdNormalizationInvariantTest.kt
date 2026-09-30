package io.nisfeb.talon.urbit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Invariant: **no id stored in the messages or reactions table ever
 * contains a dot**. The DM dup bug was caused by two ingestion paths
 * with disagreeing id shapes — one raw, one dotted. Locking the
 * post-ingest result to undotted ids here means any future code path
 * that skips normalization trips a test, not a user.
 */
class IdNormalizationInvariantTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ─── ingestPost — bootstrap + paginate path ────────────────

    @Test
    fun `ingestedPost strips dots from nested reply seal ids`() {
        val post = json.parseToJsonElement("""
            {
              "seal": {"id":"1.000","reacts":{},"seq":1,
                "replies": {
                  "0": {
                    "seal": {"id":"2.000","reacts":{},"seq":2},
                    "reply-essay": {"content":[],"author":"~x","sent":0,"blob":null}
                  }
                }
              },
              "essay": {"content":[],"author":"~x","sent":0,"kind":"/chat","blob":null,"meta":null}
            }
        """.trimIndent())
        val out = ingestedPost("~peer", post)
        assertEquals(2, out.messages.size)
        for (m in out.messages) {
            assertFalse("id $m.id must not be dotted", m.id.contains('.'))
            m.parentId?.let {
                assertFalse("parentId $it must not be dotted", it.contains('.'))
            }
        }
    }

    @Test
    fun `ingestedPost strips dots from reaction postIds`() {
        val post = json.parseToJsonElement("""
            {
              "seal": {"id":"170.141.184",
                "reacts":{"~alice":":fire:"},
                "replies":{},"seq":1},
              "essay": {"content":[],"author":"~x","sent":0,"kind":"/chat","blob":null,"meta":null}
            }
        """.trimIndent())
        val out = ingestedPost("~peer", post)
        assertEquals(1, out.reactions.size)
        assertFalse(
            "reaction postId must not be dotted",
            out.reactions[0].postId.contains('.'),
        )
    }

    // ─── undotAtom contract ────────────────────────────────────

}
