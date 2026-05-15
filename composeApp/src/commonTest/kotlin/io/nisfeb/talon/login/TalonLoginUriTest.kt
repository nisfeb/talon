package io.nisfeb.talon.login

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TalonLoginUriTest {

    @Test
    fun roundTripsHttpsAndCode() {
        val original = TalonLoginUri.Payload(
            url = "https://ship.example.com",
            code = "foo-bar-baz-quux",
        )
        val encoded = TalonLoginUri.encode(original)
        val decoded = assertNotNull(TalonLoginUri.decode(encoded))
        assertEquals(original, decoded)
    }

    @Test
    fun roundTripsLocalhostWithPort() {
        val original = TalonLoginUri.Payload(
            url = "http://localhost:8080",
            code = "lidlut-tabwed-pillex-ridrup",
        )
        val encoded = TalonLoginUri.encode(original)
        val decoded = assertNotNull(TalonLoginUri.decode(encoded))
        assertEquals(original, decoded)
    }

    @Test
    fun escapesSpecialCharactersInUrl() {
        // Edge case: a URL with characters that must be percent-encoded
        // (query string, path with spaces). Trip through encode/decode
        // to verify both legs use compatible escaping.
        val original = TalonLoginUri.Payload(
            url = "https://ship.example.com/path with spaces?x=1&y=2",
            code = "abcd-efgh",
        )
        val encoded = TalonLoginUri.encode(original)
        // Spaces and reserved chars must NOT appear unescaped in the
        // encoded form — otherwise a real QR/URI parser splits on them.
        check(' ' !in encoded) { "encoded URI contains literal space: $encoded" }
        val decoded = assertNotNull(TalonLoginUri.decode(encoded))
        assertEquals(original, decoded)
    }

    @Test
    fun rejectsForeignScheme() {
        assertNull(TalonLoginUri.decode("https://login?url=foo&code=bar"))
        assertNull(TalonLoginUri.decode("matrix://login?url=foo&code=bar"))
    }

    @Test
    fun rejectsWrongHost() {
        assertNull(TalonLoginUri.decode("talon://elsewhere?url=foo&code=bar"))
    }

    @Test
    fun rejectsMissingUrl() {
        assertNull(TalonLoginUri.decode("talon://login?code=foo"))
    }

    @Test
    fun rejectsMissingCode() {
        assertNull(TalonLoginUri.decode("talon://login?url=http://x"))
    }

    @Test
    fun rejectsBlankFields() {
        // URL-encoded empty values shouldn't sneak past the blank check.
        assertNull(TalonLoginUri.decode("talon://login?url=&code=abc"))
        assertNull(TalonLoginUri.decode("talon://login?url=abc&code="))
    }

    @Test
    fun tolerantOfExtraParams() {
        // Future versions may add params; older scanners ignore them.
        val decoded = assertNotNull(
            TalonLoginUri.decode(
                "talon://login?url=http%3A%2F%2Flocalhost%3A8080&code=abc-def&v=2&extra=ignored",
            )
        )
        assertEquals("http://localhost:8080", decoded.url)
        assertEquals("abc-def", decoded.code)
    }

    @Test
    fun decodeIgnoresWhitespacePadding() {
        val decoded = assertNotNull(
            TalonLoginUri.decode("  talon://login?url=http%3A%2F%2Fx&code=y  ")
        )
        assertEquals("http://x", decoded.url)
        assertEquals("y", decoded.code)
    }

    @Test
    fun schemeMatchIsCaseInsensitive() {
        // QR-encoded URIs from random generators sometimes uppercase the
        // scheme. RFC 3986 says scheme is case-insensitive; honor that.
        val decoded = assertNotNull(
            TalonLoginUri.decode("TALON://login?url=http%3A%2F%2Fx&code=y")
        )
        assertEquals("http://x", decoded.url)
    }
}
