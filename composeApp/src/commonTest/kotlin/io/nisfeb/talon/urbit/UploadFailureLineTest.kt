package io.nisfeb.talon.urbit

import io.nisfeb.talon.ui.ComposerState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A user pasting a screenshot saw "image failed: image upload failed:
 * memex=memex upload-url failed: HTTP 500; storage=S3 PUT failed: HTTP
 * 403 — <?xml version="1.0" encoding="UTF-8"?><Error><Code>AccessDenied…".
 */
class UploadFailureLineTest {
    private val memex500 = IllegalStateException("memex upload-url failed: HTTP 500")
    private val s3403 = IllegalStateException(
        "S3 PUT failed: HTTP 403 — <?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>AccessDenied</Code><Message>Authentication failed</Message></Error>",
    )

    @Test
    fun the_reported_failure_is_said_plainly_with_no_markup() {
        val line = uploadFailureLine(memex500, s3403)
        assertEquals(
            "The upload didn't go through: Tlon's image hosting had a problem on its side, " +
                "and your ship's storage turned down its keys (see Storage in Landscape's settings).",
            line,
        )
        assertFalse('<' in line)
    }

    @Test
    fun a_route_the_ship_does_not_have_is_left_out() {
        assertEquals(
            "The upload didn't go through: your ship's storage turned down its keys (see Storage in Landscape's settings).",
            uploadFailureLine(IllegalStateException("no memex token"), s3403),
        )
        assertEquals(
            "The upload didn't go through: Tlon's image hosting had a problem on its side.",
            uploadFailureLine(memex500, IllegalStateException("no %storage credentials on this ship")),
        )
        assertEquals(
            "The upload didn't go through: this ship has no image storage set up.",
            uploadFailureLine(IllegalStateException("no memex token"), IllegalStateException("no %storage configuration on this ship")),
        )
    }

    @Test
    fun a_refusal_by_the_hosting_is_not_called_its_own_problem() {
        assertTrue("Tlon's image hosting refused it" in uploadFailureLine(IllegalStateException("memex upload-url failed: HTTP 413"), null))
    }

    @Test
    fun the_composer_shows_the_sentence_and_keeps_the_rest_for_copying() {
        val state = ComposerState("")
        val raw = IllegalStateException("image upload failed: memex=${memex500.message}; storage=${s3403.message}")
        state.failed("image", UploadFailed(uploadFailureLine(memex500, s3403), raw))
        assertEquals(uploadFailureLine(memex500, s3403), state.sendError)
        assertTrue("AccessDenied" in state.sendErrorDetails.orEmpty(), "the whole of it, behind Copy error details")
    }
}
