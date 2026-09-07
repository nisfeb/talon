package io.nisfeb.talon.relay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ActivityPreviewTest {
    private fun ev(raw: String) = Json.parseToJsonElement(raw).jsonObject

    @Test
    fun `words ships and breaks flatten to one line`() {
        val e = ev("""{"dm-post":{"key":{"id":"~zod/170"},"content":[{"inline":["hey ",{"ship":"~nec"},{"break":null},{"bold":["now"]}]}]}}""")
        assertEquals("hey ~nec now", ActivityPreview.of(e))
    }

    @Test
    fun `an image-only post says so and an empty one is null`() {
        val img = ev("""{"chan-post":{"key":{"id":"~zod/1"},"content":[{"block":{"image":{"src":"x","alt":""}}}]}}""")
        assertEquals("[image]", ActivityPreview.of(img))
        assertNull(ActivityPreview.of(ev("""{"chan-post":{"key":{"id":"~zod/1"},"content":[]}}""")))
    }

    @Test
    fun `ios endpoint splits into voip and alert tokens`() {
        assertEquals("aa", Push.iosVoipToken("aa|bb"))
        assertEquals("bb", Push.iosAlertToken("aa|bb"))
        assertNull(Push.iosAlertToken("aa|"))
        assertNull(Push.iosAlertToken("aa"))
    }
}
