package io.nisfeb.talon.urbit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tlon v11.4.0 renamed the contacts directory scry `/v1/all` →
 * `/v1/directory` and changed each entry from a flat field map to
 * `{isContact, contact, mod}` (tloncorp/tlon-apps@c2be4a06). Ships on
 * v11.3.0 and earlier still answer the old path with the old shape, so
 * both have to parse. `mod` is our local override and must win.
 */
class DirectoryFieldsTest {

    private fun obj(s: String) = Json.parseToJsonElement(s) as JsonObject

    private fun nickname(o: JsonObject): String? =
        ((directoryFields(o)["nickname"] as? JsonObject))?.get("value")?.toString()?.trim('"')

    @Test
    fun `v11_4 directory entry unwraps contact`() {
        val entry = obj(
            """{"isContact":true,
                "contact":{"nickname":{"type":"text","value":"Alice"}},
                "mod":{}}""",
        )
        assertEquals("Alice", nickname(entry))
    }

    @Test
    fun `a local pet-name in mod beats the peer's published nickname`() {
        val entry = obj(
            """{"isContact":true,
                "contact":{"nickname":{"type":"text","value":"Alice"},
                           "bio":{"type":"text","value":"hi"}},
                "mod":{"nickname":{"type":"text","value":"Al"}}}""",
        )
        val fields = directoryFields(entry)
        assertEquals("Al", nickname(entry), "mod overrides con")
        // Fields only con carries survive the merge.
        assertEquals(2, fields.size, "bio must not be dropped: $fields")
    }

    @Test
    fun `the old flat field map still parses`() {
        val entry = obj("""{"nickname":{"type":"text","value":"Alice"}}""")
        assertEquals("Alice", nickname(entry))
    }

    @Test
    fun `the intermediate contact plus mod-at shape still parses`() {
        val entry = obj(
            """{"contact":{"nickname":{"type":"text","value":"Alice"}},
                "mod-at":"1.734.890.123.456"}""",
        )
        assertEquals("Alice", nickname(entry), "mod-at is not a field overlay")
    }

    @Test
    fun `an empty entry flattens to itself rather than throwing`() {
        assertEquals(0, directoryFields(obj("{}")).size)
    }
}
