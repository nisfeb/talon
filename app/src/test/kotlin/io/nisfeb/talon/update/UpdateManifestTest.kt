package io.nisfeb.talon.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateManifestTest {

    private val sample = """
        {
          "versionCode": 21,
          "versionName": "0.5.0",
          "url": "https://github.com/sneagan/talon/releases/download/v0.5.0/talon-0.5.0.apk",
          "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
          "minSdk": 26,
          "changelog": "Polls fixed; daily digest weather is now Fahrenheit.",
          "mandatory": false
        }
    """.trimIndent()

    @Test fun `parses well-formed manifest`() {
        val m = UpdateManifest.parse(sample)!!
        assertEquals(21, m.versionCode)
        assertEquals("0.5.0", m.versionName)
        assertEquals(
            "https://github.com/sneagan/talon/releases/download/v0.5.0/talon-0.5.0.apk",
            m.url,
        )
        assertEquals(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            m.sha256,
        )
        assertEquals(26, m.minSdk)
        assertEquals(false, m.mandatory)
    }

    @Test fun `mandatory defaults to false when missing`() {
        val noMandatory = sample.replace(",\n          \"mandatory\": false", "")
        val m = UpdateManifest.parse(noMandatory)!!
        assertEquals(false, m.mandatory)
    }

    @Test fun `rejects manifest with non-https url`() {
        val httpUrl = sample.replace("https://", "http://")
        assertNull(UpdateManifest.parse(httpUrl))
    }

    @Test fun `rejects manifest with malformed sha256`() {
        val badHash = sample.replace(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            "deadbeef",
        )
        assertNull(UpdateManifest.parse(badHash))
    }

    @Test fun `rejects garbage input`() {
        assertNull(UpdateManifest.parse("not json"))
        assertNull(UpdateManifest.parse(""))
    }

    @Test fun `rejects manifest missing a required field`() {
        // Drop versionCode — required.
        val noVersionCode = sample.replace("\"versionCode\": 21,", "")
        assertNull(UpdateManifest.parse(noVersionCode))
        // Drop versionName — required.
        val noVersionName = sample.replace("\"versionName\": \"0.5.0\",", "")
        assertNull(UpdateManifest.parse(noVersionName))
        // Drop url — required.
        val noUrl = sample.replace(
            "\"url\": \"https://github.com/sneagan/talon/releases/download/v0.5.0/talon-0.5.0.apk\",",
            "",
        )
        assertNull(UpdateManifest.parse(noUrl))
        // Drop sha256 — required.
        val noSha = sample.replace(
            "\"sha256\": \"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\",",
            "",
        )
        assertNull(UpdateManifest.parse(noSha))
    }

    @Test fun `lowercases sha256 on parse`() {
        val upper = sample.replace(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF",
        )
        val m = UpdateManifest.parse(upper)!!
        assertEquals(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            m.sha256,
        )
    }

    @Test fun `defaults minSdk and changelog when missing`() {
        val stripped = sample
            .replace("\"minSdk\": 26,\n  ", "")
            .replace(
                "\"changelog\": \"Polls fixed; daily digest weather is now Fahrenheit.\",\n  ",
                "",
            )
        val m = UpdateManifest.parse(stripped)!!
        assertEquals(26, m.minSdk)
        assertEquals("", m.changelog)
    }

    @Test fun `rejects valid JSON that isn't an object`() {
        assertNull(UpdateManifest.parse("[]"))
        assertNull(UpdateManifest.parse("\"just a string\""))
        assertNull(UpdateManifest.parse("42"))
    }
}
