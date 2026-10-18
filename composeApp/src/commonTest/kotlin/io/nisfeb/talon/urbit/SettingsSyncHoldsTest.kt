package io.nisfeb.talon.urbit

import io.nisfeb.talon.urbit.SettingsSyncImpl.Companion.holds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Whether the ship already has what a kept settings write makes of its
 * bucket (review A1-2 and A1-7, 2026-10-17): a kept write the ship holds
 * is dropped, not sent and applied again, and a seen mark the ship has
 * passed is never sent to lower it.
 */
class SettingsSyncHoldsTest {
    private fun obj(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    private fun putEntry(bucket: String, entry: String, cord: String) = obj(
        """{"put-entry":{"desk":"talon","bucket-key":"$bucket","entry-key":"$entry","value":${JsonPrimitive(cord)}}}""",
    )
    private fun delEntry(bucket: String, entry: String) =
        obj("""{"del-entry":{"desk":"talon","bucket-key":"$bucket","entry-key":"$entry"}}""")
    private fun putBucket(bucket: String, entries: String) =
        obj("""{"put-bucket":{"desk":"talon","bucket-key":"$bucket","bucket":$entries}}""")
    private fun delBucket(bucket: String) = obj("""{"del-bucket":{"desk":"talon","bucket-key":"$bucket"}}""")

    private val none = putEntry("notify-prefs", "~bus", """{"level":"none"}""")

    @Test
    fun `an entry holds when the ship has the same value as a cord or as raw json`() {
        assertTrue(holds(obj("""{"~bus":"{\"level\":\"none\"}"}"""), none))
        assertTrue(holds(obj("""{"~bus":{"level":"none"}}"""), none))
        assertTrue(holds(obj("""{"~bus":"{ \"level\" : \"none\" }"}"""), none), "the same json, spaced")
    }

    @Test
    fun `an entry does not hold when the ship has another value or none`() {
        assertFalse(holds(obj("""{"~bus":"{\"level\":\"all\"}"}"""), none))
        assertFalse(holds(obj("""{"~nec":"{\"level\":\"none\"}"}"""), none))
        assertFalse(holds(obj("{}"), none))
        assertFalse(holds(null, none))
    }

    @Test
    fun `a delete holds only once the entry is gone`() {
        assertTrue(holds(obj("""{"~nec":"{}"}"""), delEntry("notify-prefs", "~bus")))
        assertTrue(holds(null, delEntry("notify-prefs", "~bus")))
        assertFalse(holds(obj("""{"~bus":"{}"}"""), delEntry("notify-prefs", "~bus")))
    }

    @Test
    fun `a whole bucket holds when every entry matches`() {
        val orders = putBucket("group-orders", """{"~bus/a":"{\"ordinal\":0}","~bus/b":"{\"ordinal\":1}"}""")
        assertTrue(holds(obj("""{"~bus/b":{"ordinal":1},"~bus/a":"{\"ordinal\":0}"}"""), orders))
        assertFalse(holds(obj("""{"~bus/a":"{\"ordinal\":0}"}"""), orders), "one missing")
        assertFalse(holds(obj("""{"~bus/a":"{\"ordinal\":1}","~bus/b":"{\"ordinal\":0}"}"""), orders), "reordered")
        assertFalse(holds(null, orders))
    }

    @Test
    fun `a cleared bucket holds when the ship has none or an empty one`() {
        assertTrue(holds(null, delBucket("loops")))
        assertTrue(holds(obj("{}"), delBucket("loops")))
        assertFalse(holds(obj("""{"g1":"{}"}"""), delBucket("loops")))
    }

    @Test
    fun `a seen mark holds when the ship's is at or above it`() {
        val seen = putEntry("status-seen", "me", """{"ms":100}""")
        assertTrue(holds(obj("""{"me":"{\"ms\":200}"}"""), seen))
        assertTrue(holds(obj("""{"me":"{\"ms\":100}"}"""), seen))
        assertFalse(holds(obj("""{"me":"{\"ms\":99}"}"""), seen))
        assertFalse(holds(obj("{}"), seen), "none on the ship: ours goes")
        assertFalse(holds(obj("""{"me":"junk"}"""), seen), "unreadable on the ship: ours goes")
        assertTrue(holds(obj("""{"me":"{\"ms\":5}"}"""), putEntry("status-seen", "me", "junk")), "ours unreadable: not sent")
    }

    @Test
    fun `a write of another shape never holds`() {
        assertFalse(holds(obj("{}"), obj("""{"other":{}}""")))
    }
}
