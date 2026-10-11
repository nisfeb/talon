package io.nisfeb.talon.urbit

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Uploading a picture: through Tlon's hosting when the ship has a token
 * for it, else straight to the ship's own S3 bucket, and a failure that
 * says what each way said.
 */
class TlonChatRepoUploadTest {
    private val db: AppDatabase = createTempDirectory(prefix = "talon-upload-").toFile().let { dir ->
        Room.databaseBuilder<AppDatabase>(File(dir, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
    }
    private val ship = FakeShip("~zod")
    private val repo = TlonChatRepo(db).apply { attachForTest(ship.channel, "~zod", http = ship.http) }
    private val png = byteArrayOf(1, 2, 3, 4)

    @AfterTest
    fun close() = db.close()

    private fun live(body: suspend CoroutineScope.() -> Unit) = runBlocking<Unit> {
        val events = ship.channel.events().launchIn(this)
        try { body() } finally { events.cancel() }
    }

    private fun hosted() {
        ship.scries["genuine/secret"] = "\"tok-1\""
        ship.answerApi = { method, path, _ ->
            when {
                method == "PUT" && path == "/v1/zod/upload" ->
                    """{"uploadUrl":"https://bucket.test/put-here?sig=1","hostedUrl":"https://cdn.test/zod/cat.png"}"""
                method == "PUT" && path == "/put-here" -> ""
                else -> null
            }
        }
    }

    private fun ownBucket(presigned: Boolean = false) {
        ship.scries["storage/credentials"] = if (presigned) """{"storage-update":{"credentials":{"endpoint":"","access-key-id":"","secret-access-key":""}}}"""
            else """{"storage-update":{"credentials":{"endpoint":"https://s3.test","access-key-id":"AK","secret-access-key":"SK"}}}"""
        ship.scries["storage/configuration"] =
            """{"storage-update":{"configuration":{"current-bucket":"pics","region":"","public-url-base":"https://pics.test","service":"${if (presigned) "presigned-url" else "credentials"}"}}}"""
    }

    @Test
    fun `with a hosting token the picture goes to Tlon's hosting`() = live {
        hosted()
        assertEquals("https://cdn.test/zod/cat.png", repo.uploadImage(png, "image/png", "photos/cat.png"))
        val asked = ship.api.first { it.startsWith("PUT /v1/zod/upload") }
        for (part in listOf("\"token\":\"tok-1\"", "\"contentLength\":4", "\"contentType\":\"image/png\"")) {
            assertTrue(part in asked, "$part in $asked")
        }
        // Tlon's hosting is told a name of chance, not the file's (2026-10-11).
        assertTrue(Regex("\"fileName\":\"[0-9a-f]{32}\\.png\"").containsMatchIn(asked) && "cat" !in asked, asked)
        assertTrue(ship.api.any { it.startsWith("PUT /put-here") }, "the bytes go to the URL it gave")
    }

    @Test
    fun `without hosting, the picture goes to the ship's own bucket`() = live {
        ownBucket()
        ship.answerApi = { method, path, _ -> if (method == "PUT" && path.startsWith("/pics/talon/")) "" else null }
        val url = repo.uploadImage(png, "image/png", "cat photo.png")
        assertTrue(Regex("https://pics\\.test/talon/[0-9a-f]{32}\\.png").matches(url), url)
    }

    // The bucket is public at the URL: neither the file's name nor where
    // the photo was taken goes there (2026-10-11).
    @Test
    fun `a photo reaches the bucket without its name or its location`() = live {
        ownBucket()
        ship.answerApi = { method, path, _ -> if (method == "PUT" && path.startsWith("/pics/talon/")) "" else null }
        val exif = "Exif\u0000\u0000II*\u0000\u0008\u0000\u0000\u0000\u0000\u0000GPS 40.7N 74.0W".encodeToByteArray()
        fun b(vararg x: Int) = ByteArray(x.size) { x[it].toByte() }
        val photo = b(0xFF, 0xD8, 0xFF, 0xE1, 0, exif.size + 2) + exif + b(0xFF, 0xDA, 0, 4, 1, 2, 0x5C, 0x5D, 0xFF, 0xD9)
        val url = repo.uploadImage(photo, "image/jpeg", "IMG_20261011_143012.jpg")
        assertTrue("IMG" !in url && "2026" !in url, url)
        val put = ship.api.single { it.startsWith("PUT /pics/talon/") }
        assertTrue("GPS" !in put, put)
        // The scan's two bytes, 0x5C 0x5D, are the picture itself.
        assertTrue("\\]" in put, "the picture itself went: $put")
    }

    @Test
    fun `when neither way works, the failure says what each said`() = live {
        ownBucket(presigned = true)
        val e = assertFailsWith<UploadFailed> { repo.uploadImage(png, "image/png", "cat.png") }
        // Said plainly; what each route said is behind Copy error details.
        assertTrue(e.message!!.startsWith("The upload didn't go through:") && "memex=" !in e.message!!, e.message)
        val details = io.nisfeb.talon.util.errorDetailsOf(e)
        assertTrue("memex=" in details && "presigned-url mode" in details, details)
    }

    @Test
    fun `an empty file is refused before anything is sent`() = live {
        hosted()
        assertFailsWith<IllegalArgumentException> { repo.uploadImage(ByteArray(0), "image/png", "cat.png") }
        assertTrue(ship.api.isEmpty())
    }
}
