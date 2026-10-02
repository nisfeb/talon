package io.nisfeb.talon.ui

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** The fetch half of iOS's image save (the Photos half is device wiring, untested here). */
class FetchImageBytesTest {
    private fun http(status: HttpStatusCode, body: ByteArray) = HttpClient(MockEngine { respond(body, status) })

    @Test
    fun `an image's bytes come back as they are`() = runTest {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        assertContentEquals(png, fetchImageBytes(http(HttpStatusCode.OK, png), "https://s3.test/a.png").getOrThrow())
    }

    @Test
    fun `a refusal or an empty answer is said in words, without the raw reply`() = runTest {
        val gone = fetchImageBytes(http(HttpStatusCode.Forbidden, "<Error>AccessDenied</Error>".encodeToByteArray()), "https://s3.test/a.png")
        assertEquals("Couldn't download the image: The server answered 403.", gone.exceptionOrNull()?.message)
        val empty = fetchImageBytes(http(HttpStatusCode.OK, ByteArray(0)), "https://s3.test/a.png")
        assertEquals("Couldn't download the image: The image was empty.", empty.exceptionOrNull()?.message)
    }
}
